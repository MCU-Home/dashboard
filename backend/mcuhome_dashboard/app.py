# SPDX-FileCopyrightText: 2026 The MCUHome Contributors
# SPDX-License-Identifier: Apache-2.0
"""Shared state and the two application factories (ADR 0009 decision 1).

One process, one :class:`AppState`, **two** :class:`aiohttp.web.Application`
objects built from it — an ingress one that trusts its peer and a public
one that does not. They share the event bus, the device store and the
session store, because they are two doors into the same application, not
two applications.

Building them separately is what makes ADR 0009's "the two-site split
has to be real in the code" true: the trusting policy is not a branch
inside a shared handler that a refactor could take on the wrong site, it
is a different application object with a different middleware stack and
a different route table.
"""

from __future__ import annotations

import logging
import secrets
import time
from dataclasses import dataclass, field
from typing import Any

from aiohttp import web

from mcuhome_dashboard import versions, ws
from mcuhome_dashboard.builder import MCUHOME_VERSION
from mcuhome_dashboard.config import Config
from mcuhome_dashboard.devices import DeviceStore
from mcuhome_dashboard.events import EventBus
from mcuhome_dashboard.security import (
    CSRF_HEADER,
    SESSION_COOKIE,
    STATE_KEY,
    TRUST_KEY,
    SessionStore,
    TrustMode,
    auth_middleware,
    check_origin,
    identity_of,
    require_csrf,
)
from mcuhome_dashboard.web import base_path, static_handler

__all__ = ["AppState", "create_app"]

logger = logging.getLogger(__name__)


@dataclass
class AppState:
    """Everything both sites share, created once per process."""

    config: Config
    bus: EventBus = field(default_factory=EventBus)
    sessions: SessionStore = field(default_factory=SessionStore)
    devices: DeviceStore = field(init=False)
    started_at: float = field(default_factory=time.monotonic)

    #: There is no build client here. ADR 0012 decision 3 replaced the
    #: job protocol of ADR 0006 with the session protocol of firmware
    #: ADR 0019, and the old client was removed rather than migrated, so
    #: this process currently holds no connection to a build server and
    #: no way to open one. ``config.build_server_url``/``_token`` are
    #: still resolved (including the pairing file) because the session
    #: client will need exactly them; nothing reads them yet.

    def __post_init__(self) -> None:
        self.devices = DeviceStore(
            self.config.config_root,
            self.bus,
            poll_interval=self.config.poll_interval,
        )

    async def start(self) -> None:
        versions.check_mcuhome_version(MCUHOME_VERSION)
        await self.devices.start()

    async def stop(self) -> None:
        await self.devices.stop()


# --------------------------------------------------------------------------
# REST — deliberately tiny (ADR 0004 decision 4)
# --------------------------------------------------------------------------


async def health(request: web.Request) -> web.Response:
    """``GET /health`` — for an orchestrator, before anyone has logged in.

    Says what is running and nothing about what it holds.
    """
    state = request.app[STATE_KEY]
    return web.json_response(
        {
            "status": "ok",
            "dashboard": versions.DASHBOARD_VERSION,
            "mcuhome": MCUHOME_VERSION,
            "model_version": versions.MODEL_VERSION,
            "trust": request.app[TRUST_KEY].value,
            "uptime_seconds": round(time.monotonic() - state.started_at, 3),
        }
    )


async def login(request: web.Request) -> web.Response:
    """``POST /auth/login`` — exchange the password for a session cookie.

    The one state-changing REST endpoint the public site needs. ADR 0004
    decision 4 keeps REST to "where a browser primitive needs a URL",
    and a login is exactly that: a WebSocket cannot set a cookie, and a
    password kept in JavaScript instead of an ``HttpOnly`` cookie is
    strictly worse.

    It issues the CSRF token that ADR 0009 decision 3 requires on
    state-changing requests; it does not require one itself, because
    there is no session yet to forge a request from. Its own protection
    is the origin check plus the password.
    """
    state = request.app[STATE_KEY]
    if not check_origin(request, allowed=state.config.allowed_origins):
        raise web.HTTPForbidden(text="This origin may not log in here.")
    if state.config.password is None:
        raise web.HTTPBadRequest(text="This dashboard is not password-protected.")

    try:
        body = await request.json()
    except (ValueError, TypeError):
        body = {}
    presented = body.get("password") if isinstance(body, dict) else None
    if not isinstance(presented, str) or not _matches(presented, state.config.password):
        # Deliberately identical for "no password" and "wrong password".
        raise web.HTTPUnauthorized(text="Wrong password.")

    session = state.sessions.create()
    response = web.json_response({"csrf_token": session.csrf_token})
    response.set_cookie(
        SESSION_COOKIE,
        session.token,
        httponly=True,
        samesite="Lax",
        secure=request.secure,
        path=f"{base_path(request)}/",
    )
    return response


async def logout(request: web.Request) -> web.Response:
    """``POST /auth/logout`` — drop the session. Needs the CSRF token."""
    require_csrf(request)
    state = request.app[STATE_KEY]
    identity = identity_of(request)
    if identity is not None and identity.session is not None:
        state.sessions.drop(identity.session.token)
    response = web.json_response({"status": "ok"})
    response.del_cookie(SESSION_COOKIE, path=f"{base_path(request)}/")
    return response


#: ``GET /api/builds/{job}/artifacts/{path}`` used to live here: the
#: browser primitive of ADR 0004 decision 4 that served the local,
#: verified, locally signed copy of a finished build. It went with the
#: job protocol (ADR 0012 decision 3), because nothing writes into
#: ``config.artifact_root`` any more — the route could only ever have
#: answered 404, and a route table that lists an endpoint with no
#: possible content is a worse lie than a missing one. What has to come
#: back with the session protocol's ``get-artifact`` is not just the
#: handler but its refusal: a path that resolves outside the job's own
#: directory is a 404 and never a 403, because naming which guess
#: escaped is free reconnaissance.


def _matches(presented: str, expected: str) -> bool:
    return secrets.compare_digest(presented, expected)


# --------------------------------------------------------------------------
# Factories
# --------------------------------------------------------------------------


def create_app(state: AppState, trust: TrustMode) -> web.Application:
    """Build one of the two sites."""
    app = web.Application(middlewares=[auth_middleware])
    app[STATE_KEY] = state
    app[TRUST_KEY] = trust

    app.router.add_get("/health", health)
    app.router.add_get("/ws", ws.websocket_handler)
    if trust is TrustMode.PUBLIC:
        # Ingress has no password to exchange and no session to drop.
        app.router.add_post("/auth/login", login)
        app.router.add_post("/auth/logout", logout)
    app.router.add_get("/{path:.*}", static_handler(state.config.static_root))

    app.on_response_prepare.append(_security_headers)
    return app


async def _security_headers(request: web.Request, response: web.StreamResponse) -> None:
    """Headers that cost nothing and close whole classes of mistake.

    No CSP here yet: the frontend does not exist (Block 3), and a policy
    written against a placeholder would be a policy written against
    nothing. It lands with the assets it has to allow.
    """
    response.headers.setdefault("X-Content-Type-Options", "nosniff")
    response.headers.setdefault("Referrer-Policy", "same-origin")


def csrf_header_name() -> str:
    """Exported so the frontend and the tests name the header once."""
    return CSRF_HEADER


def app_state_of(app: web.Application) -> Any:
    return app[STATE_KEY]
