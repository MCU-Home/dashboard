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
Supervisor gateway. That peer check is the control — ``X-Remote-User-Id``
and ``X-Remote-User-Name`` are attacker-supplied the moment it fails, so
nothing outside a trusted-peer request may read them as anything.

Once the peer *is* the Supervisor, those headers are trustworthy: the
Supervisor strips whatever the client sent and re-injects the values from
the authenticated ingress session, so a non-admin cannot forge a colleague's
name. ADR 0014 turns that into authorization: dashboard access in Home
Assistant is admin-only, and the admin decision is not the header itself
(identity) but the answer the Supervisor's authenticated ``/auth/list``
gives for that username (:mod:`mcuhome.ui.admin`). The header says
*which* user; the API says *whether that user is an admin*. When the
answer cannot be had, the user is treated as non-admin — fail closed.

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
import math
import secrets
import time
from collections import deque
from dataclasses import dataclass, field, replace
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
    "LoginThrottle",
    "Session",
    "SessionStore",
    "TrustMode",
    "auth_middleware",
    "authenticate",
    "check_origin",
    "identity_of",
    "is_trusted_peer",
    "peer_address",
    "require_csrf",
    "requires_authentication",
    "trust_mode_of",
]

logger = logging.getLogger(__name__)

#: The only address a Home Assistant ingress request can come from.
SUPERVISOR_GATEWAY = "172.30.32.2"

SESSION_COOKIE = "mcuhome.ui_session"
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


#: Application keys, set by the factory in :mod:`mcuhome.ui.app`.
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

    ``kind`` says how the request authenticated; ``is_admin`` says what it
    may do. ``user_id`` and ``user_name`` come from ingress headers and
    exist to be displayed — and, for ``user_name``, to be matched against
    the Supervisor's admin roster (ADR 0014), which is a lookup key rather
    than the decision itself.

    ``is_admin`` is ``True`` for ``open`` (a loopback deployment is
    unconditionally trusted) and ``password`` (ADR 0009's single password
    already means "the operator"), and for ``ingress`` **only** when
    :mod:`mcuhome.ui.admin` resolved the user to a Home Assistant
    administrator. It fails closed: an ingress user whose status could not
    be resolved is not an admin.
    """

    #: ``ingress`` · ``password`` · ``open`` (loopback, no password set)
    kind: str
    user_id: str | None = None
    user_name: str | None = None
    #: Whether this request may run the admin-only verbs (ADR 0014).
    is_admin: bool = False
    session: Session | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "kind": self.kind,
            "user_id": self.user_id,
            "user_name": self.user_name,
            "is_admin": self.is_admin,
        }


# --------------------------------------------------------------------------
# Failed-login throttling for the public site (ADR 0014)
# --------------------------------------------------------------------------

#: Failed public-site sign-ins from one source address before a temporary
#: lockout begins. High enough that an operator's honest fat-fingering
#: never trips it, low enough that a script guessing the password does
#: within a handful of tries.
LOGIN_FAILURE_THRESHOLD = 5
#: The first lockout once the threshold is crossed, in seconds; it doubles
#: with each further failure up to :data:`LOGIN_BACKOFF_MAX`.
LOGIN_BACKOFF_BASE = 1.0
LOGIN_BACKOFF_MAX = 300.0
#: A source with no failure for this long, and not currently locked, is
#: forgotten — so the table cannot grow without bound.
LOGIN_FAILURE_TTL = 900.0
#: The global backstop, for the distributed guess a per-source counter
#: cannot see: this many failures across *all* sources within
#: :data:`LOGIN_GLOBAL_WINDOW` seconds slow the whole process down for
#: :data:`LOGIN_GLOBAL_LOCKOUT` seconds.
LOGIN_GLOBAL_THRESHOLD = 100
LOGIN_GLOBAL_WINDOW = 60.0
LOGIN_GLOBAL_LOCKOUT = 30.0


@dataclass
class _Attempts:
    failures: int = 0
    locked_until: float = 0.0
    last_seen: float = 0.0


class LoginThrottle:
    """Failed-password tracking with exponential backoff and a backstop.

    Keyed on the source address, with a process-wide backstop underneath
    (ADR 0014). The public site's two password paths — ``POST /auth/login``
    and a bearer token on a protected request — both feed one instance, so
    an attacker cannot dodge the counter by switching between them. The
    clock is injectable so the lockout arithmetic is testable without
    sleeping.

    The ingress site never consults this: its trust is the peer check, and
    throttling the Supervisor gateway would throttle every user at once.
    """

    def __init__(self, *, clock: Any = time.monotonic) -> None:
        self._clock = clock
        self._by_source: dict[str, _Attempts] = {}
        self._global: deque[float] = deque()
        self._global_until = 0.0

    def retry_after(self, source: str | None) -> float:
        """Seconds this source must wait, or ``0.0`` when it may try now."""
        now = self._clock()
        self._prune(now)
        waits = [0.0]
        record = self._by_source.get(self._key(source))
        if record is not None and record.locked_until > now:
            waits.append(record.locked_until - now)
        if self._global_until > now:
            waits.append(self._global_until - now)
        return max(waits)

    def record_failure(self, source: str | None) -> None:
        """Count one failed attempt, arming a lockout once past the threshold."""
        now = self._clock()
        self._prune(now)
        record = self._by_source.setdefault(self._key(source), _Attempts())
        record.failures += 1
        record.last_seen = now
        if record.failures >= LOGIN_FAILURE_THRESHOLD:
            over = record.failures - LOGIN_FAILURE_THRESHOLD
            backoff = min(LOGIN_BACKOFF_BASE * (2**over), LOGIN_BACKOFF_MAX)
            record.locked_until = now + backoff
        self._global.append(now)
        if len(self._global) >= LOGIN_GLOBAL_THRESHOLD:
            self._global_until = now + LOGIN_GLOBAL_LOCKOUT

    def record_success(self, source: str | None) -> None:
        """A correct password clears this source's failures."""
        self._by_source.pop(self._key(source), None)

    @staticmethod
    def _key(source: str | None) -> str:
        return source if source is not None else ""

    def _prune(self, now: float) -> None:
        stale = [
            key
            for key, record in self._by_source.items()
            if record.locked_until <= now and now - record.last_seen > LOGIN_FAILURE_TTL
        ]
        for key in stale:
            del self._by_source[key]
        while self._global and now - self._global[0] > LOGIN_GLOBAL_WINDOW:
            self._global.popleft()


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


def peer_address(request: web.Request) -> str | None:
    """The source address of *request*, or ``None`` when there is none.

    The throttle key of ADR 0014, and the same value the peer check reads.
    A reverse proxy in front (ADR 0009) makes this the proxy's address, so
    the global backstop is what covers a whole site behind one hop —
    ``X-Forwarded-For`` is client-settable and deliberately not trusted.
    """
    return _peer_address(request)


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
        # `is_admin` stays False here: it is resolved from the Supervisor
        # in the middleware, which is async and this is not (ADR 0014).
        return Identity(
            kind="ingress",
            user_id=request.headers.get("X-Remote-User-Id"),
            user_name=request.headers.get("X-Remote-User-Name"),
        )

    if password is None:
        # Loopback-only bind: ADR 0009 decision 2 leaves this open, and
        # nothing but this machine can reach it, so it is the operator.
        return Identity(kind="open", is_admin=True)

    session = sessions.get(request.cookies.get(SESSION_COOKIE))
    if session is not None:
        return Identity(kind="password", is_admin=True, session=session)

    presented = _bearer_password(request)
    if presented is not None and secrets.compare_digest(presented, password):
        return Identity(kind="password", is_admin=True)
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


def _throttle_public_password(request: web.Request, state: Any, identity: Identity | None) -> None:
    """Rate-limit the public site's password paths (ADR 0014).

    Only a *bearer* attempt counts here: it is the guessable credential (a
    script trying passwords), whereas a session cookie is a random token
    and an anonymous GET is no attempt at all. A locked source is refused
    with ``429`` and a ``Retry-After`` before its guess is even weighed;
    otherwise the outcome ``authenticate`` already reached is recorded.
    The sibling path, ``POST /auth/login``, feeds the same throttle from
    its own handler.
    """
    if _bearer_password(request) is None:
        return
    source = _peer_address(request)
    wait = state.throttle.retry_after(source)
    if wait > 0:
        raise web.HTTPTooManyRequests(
            text="Too many failed sign-in attempts. Try again later.",
            headers={"Retry-After": str(math.ceil(wait))},
        )
    if identity is None:
        state.throttle.record_failure(source)
    else:
        state.throttle.record_success(source)


async def _resolve_admin(state: Any, identity: Identity) -> Identity:
    """Fill in an ingress identity's admin status from the Supervisor (ADR 0014)."""
    admin = await state.admin_oracle.is_admin(user_id=identity.user_id, username=identity.user_name)
    return replace(identity, is_admin=bool(admin))


@web.middleware
async def auth_middleware(request: web.Request, handler: Any) -> web.StreamResponse:
    """Resolve an identity, and enforce it where it is required.

    The two sites enforce differently, which is the whole point of the
    split: the ingress site refuses an untrusted **peer** on every path,
    including the static shell, because such a peer has no business
    reaching this socket at all. The public site refuses a missing
    **password**, and only on the paths that do something.

    Two ADR 0014 additions ride along: the public site's password guesses
    are throttled here (the one place both credential paths pass through),
    and an ingress user's admin status is resolved from the Supervisor and
    written onto the identity, so every downstream handler reads one
    already-decided flag rather than re-deriving it.
    """
    state = request.app[STATE_KEY]
    trust = trust_mode_of(request)
    identity = authenticate(request, password=state.config.password, sessions=state.sessions)

    if trust is TrustMode.PUBLIC:
        _throttle_public_password(request, state, identity)
    elif identity is not None and identity.kind == "ingress":
        identity = await _resolve_admin(state, identity)

    request[IDENTITY_KEY] = identity

    if identity is None:
        if trust is TrustMode.INGRESS:
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
