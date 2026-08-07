# SPDX-FileCopyrightText: 2026 The MCUHome Contributors
# SPDX-License-Identifier: Apache-2.0
"""Trust modes, origin checks, sessions and CSRF (ADR 0009).

ADR 0009 decision 1 asks for two sites with two trust assumptions, and
its consequences section says the split "has to be real in the code".
Here it is real as a :class:`TrustMode` carried by the aiohttp
application object, so no handler can accidentally serve the trusting
policy on the untrusting site: they are different applications, built by
different calls, and the mode is read from the application, never from
the request.

**Ingress site.** Home Assistant authenticated the user before the
request left the Supervisor, so this site adds none of its own. What it
does check is *who is talking to it*: the peer must be loopback or the
Supervisor gateway. ``X-Remote-User-Id`` and ``X-Remote-User-Name`` are
best-effort display strings and are never an authorization input — they
are attacker-supplied the moment the peer check fails, which is why the
peer check is the control and the headers are decoration.

**Public site.** A password, per ADR 0009 decision 2, presented either as
``Authorization: Bearer`` (a script, the future VS Code extension) or
exchanged once at ``POST /auth/login`` for a session cookie (a browser).
Sessions live in memory only: one process, one password, no user
database, no session store on disk, and a restart logs everybody out —
all of which ADR 0009's consequences accept explicitly.
"""

from __future__ import annotations

import enum
import logging
import secrets
import time
from dataclasses import dataclass, field
from typing import Any
from urllib.parse import urlsplit

from aiohttp import web

__all__ = [
    "CSRF_HEADER",
    "IDENTITY_KEY",
    "SESSION_COOKIE",
    "STATE_KEY",
    "SUPERVISOR_GATEWAY",
    "TRUST_KEY",
    "Identity",
    "Session",
    "SessionStore",
    "TrustMode",
    "auth_middleware",
    "authenticate",
    "check_origin",
    "identity_of",
    "is_trusted_peer",
    "require_csrf",
    "requires_authentication",
    "trust_mode_of",
]

logger = logging.getLogger(__name__)

#: The only address a Home Assistant ingress request can come from.
SUPERVISOR_GATEWAY = "172.30.32.2"

SESSION_COOKIE = "mcuhome_dashboard_session"
CSRF_HEADER = "X-CSRF-Token"

#: Request key holding the resolved :class:`Identity`.
IDENTITY_KEY = "mcuhome_identity"

#: What the public site refuses without an identity. Everything else it
#: serves is the static shell and the login exchange — a placeholder
#: today, a login screen tomorrow, and neither is a secret. The list is
#: a whitelist of *protected* paths rather than of open ones so that a
#: new API path is protected by default if someone forgets this file.
PROTECTED_PATHS = frozenset({"/ws", "/auth/logout"})
PROTECTED_PREFIXES = ("/api/",)


class TrustMode(enum.Enum):
    """Which of ADR 0009's two sites an application is."""

    #: Bound to loopback plus the Supervisor network; Home Assistant has
    #: already authenticated the user.
    INGRESS = "ingress"
    #: Reachable by anyone who can route to it; authenticates itself.
    PUBLIC = "public"


#: Application keys, set by the factory in :mod:`mcuhome_dashboard.app`.
#: The trust mode is a property of the *application*, never of the
#: request — that is what makes the two-site split unforgeable.
TRUST_KEY: web.AppKey[TrustMode] = web.AppKey("mcuhome_trust")
STATE_KEY: web.AppKey[Any] = web.AppKey("mcuhome_state")


@dataclass(frozen=True)
class Session:
    """One logged-in browser on the public site."""

    token: str
    csrf_token: str
    created: float = field(default_factory=time.time)


@dataclass(frozen=True)
class Identity:
    """Who the current request is, as far as anything may act on it.

    ``kind`` is the only authorization-relevant field. ``user_id`` and
    ``user_name`` come from ingress headers and exist to be displayed.
    """

    #: ``ingress`` · ``password`` · ``open`` (loopback, no password set)
    kind: str
    user_id: str | None = None
    user_name: str | None = None
    session: Session | None = None

    def to_dict(self) -> dict[str, Any]:
        return {"kind": self.kind, "user_id": self.user_id, "user_name": self.user_name}


class SessionStore:
    """In-memory session tokens for the public site."""

    def __init__(self) -> None:
        self._sessions: dict[str, Session] = {}

    def create(self) -> Session:
        session = Session(token=secrets.token_urlsafe(32), csrf_token=secrets.token_urlsafe(32))
        self._sessions[session.token] = session
        return session

    def get(self, token: str | None) -> Session | None:
        if not token:
            return None
        return self._sessions.get(token)

    def drop(self, token: str | None) -> None:
        if token:
            self._sessions.pop(token, None)

    def clear(self) -> None:
        self._sessions.clear()

    def __len__(self) -> int:
        return len(self._sessions)


def trust_mode_of(request: web.Request) -> TrustMode:
    return request.app[TRUST_KEY]


def identity_of(request: web.Request) -> Identity | None:
    return request.get(IDENTITY_KEY)


def _peer_address(request: web.Request) -> str | None:
    peer = request.transport.get_extra_info("peername") if request.transport else None
    if isinstance(peer, tuple) and peer:
        return str(peer[0])
    return None


def is_trusted_peer(address: str | None) -> bool:
    """Whether *address* may use the ingress site.

    An unknown peer (a Unix socket, an aiohttp test transport) is
    trusted: the ingress site is only ever bound to loopback and the
    Supervisor network in the first place, so the binding is the outer
    control and this check is the inner one.
    """
    if address is None:
        return True
    normalized = address.removeprefix("::ffff:")
    return normalized in {"127.0.0.1", "::1", SUPERVISOR_GATEWAY}


def check_origin(request: web.Request, *, allowed: tuple[str, ...] = ()) -> bool:
    """Origin check for WebSocket upgrades and state-changing requests.

    Absent ``Origin`` means a non-browser client and is allowed — a
    browser always sends one on a WebSocket upgrade, so absence cannot
    be a browser trying to sneak past.

    On the **ingress site** any origin is accepted: the page is an
    iframe served from the Home Assistant origin, which by construction
    never matches this server's ``Host``. The control there is the peer
    check, not the origin.
    """
    origin = request.headers.get("Origin")
    if not origin:
        return True
    if trust_mode_of(request) is TrustMode.INGRESS:
        return True
    if origin in allowed:
        return True

    parts = urlsplit(origin)
    if not parts.scheme or not parts.netloc:
        return False
    host = request.headers.get("Host")
    return bool(host) and parts.netloc == host


def _bearer_password(request: web.Request) -> str | None:
    header = request.headers.get("Authorization", "")
    scheme, _, value = header.partition(" ")
    if scheme.lower() != "bearer" or not value.strip():
        return None
    return value.strip()


def authenticate(
    request: web.Request, *, password: str | None, sessions: SessionStore
) -> Identity | None:
    """Resolve the identity of *request*, or ``None`` when there is none."""
    if trust_mode_of(request) is TrustMode.INGRESS:
        if not is_trusted_peer(_peer_address(request)):
            return None
        return Identity(
            kind="ingress",
            user_id=request.headers.get("X-Remote-User-Id"),
            user_name=request.headers.get("X-Remote-User-Name"),
        )

    if password is None:
        # Loopback-only bind: ADR 0009 decision 2 leaves this open, and
        # nothing but this machine can reach it.
        return Identity(kind="open")

    session = sessions.get(request.cookies.get(SESSION_COOKIE))
    if session is not None:
        return Identity(kind="password", session=session)

    presented = _bearer_password(request)
    if presented is not None and secrets.compare_digest(presented, password):
        return Identity(kind="password")
    return None


def require_csrf(request: web.Request) -> None:
    """Refuse a state-changing request without a matching CSRF token.

    ADR 0009 decision 3. The REST surface is tiny by ADR 0004 decision 4,
    so this is applied per endpoint rather than as a blanket policy — and
    it applies only to cookie-authenticated sessions, because a bearer
    token is not something a browser attaches on its own.
    """
    identity = identity_of(request)
    if identity is None or identity.session is None:
        return
    presented = request.headers.get(CSRF_HEADER, "")
    if not presented or not secrets.compare_digest(presented, identity.session.csrf_token):
        raise web.HTTPForbidden(text=f"Missing or wrong {CSRF_HEADER}.")


def requires_authentication(path: str) -> bool:
    """Whether the *public* site refuses this path without an identity."""
    return path in PROTECTED_PATHS or path.startswith(PROTECTED_PREFIXES)


@web.middleware
async def auth_middleware(request: web.Request, handler: Any) -> web.StreamResponse:
    """Resolve an identity, and enforce it where it is required.

    The two sites enforce differently, which is the whole point of the
    split: the ingress site refuses an untrusted **peer** on every path,
    including the static shell, because such a peer has no business
    reaching this socket at all. The public site refuses a missing
    **password**, and only on the paths that do something.
    """
    state = request.app[STATE_KEY]
    identity = authenticate(request, password=state.config.password, sessions=state.sessions)
    request[IDENTITY_KEY] = identity

    if identity is None:
        if trust_mode_of(request) is TrustMode.INGRESS:
            logger.warning(
                "ingress site refused a request from an untrusted peer %s",
                _peer_address(request),
            )
            raise web.HTTPForbidden(text="This site only serves Home Assistant ingress.")
        if requires_authentication(request.path):
            raise web.HTTPUnauthorized(
                text="This dashboard needs a password.",
                headers={"WWW-Authenticate": 'Bearer realm="MCUHome Dashboard"'},
            )
    return await handler(request)
