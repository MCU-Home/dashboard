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

import asyncio
import logging
import math
import os
import secrets
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from aiohttp import web

from mcuhome.ui import versions, ws
from mcuhome.ui.admin import AdminOracle, build_admin_oracle
from mcuhome.ui.builder import MCUHOME_VERSION
from mcuhome.ui.builds import BuildRegistry, UnknownBuild
from mcuhome.ui.config import Config
from mcuhome.ui.devices import DeviceStore
from mcuhome.ui.events import EventBus
from mcuhome.ui.security import (
    CSRF_HEADER,
    SESSION_COOKIE,
    STATE_KEY,
    TRUST_KEY,
    LoginThrottle,
    SessionStore,
    TrustMode,
    auth_middleware,
    check_origin,
    identity_of,
    peer_address,
    require_csrf,
)
from mcuhome.ui.web import base_path, static_handler

__all__ = ["AppState", "create_app"]

logger = logging.getLogger(__name__)

#: The most CPU-bound builder calls (``device/validate``,
#: ``device/commissioning``) that may run at once across every connection
#: (the concurrency finding). Smaller than the default thread pool on
#: purpose, so a client firing validates cannot exhaust the pool the rest
#: of the app's file I/O shares and stall every socket. Modest on a Home
#: Assistant box, which is where this runs (the build-RAM note).
BUILDER_CONCURRENCY = max(1, min(4, os.cpu_count() or 2))


@dataclass
class AppState:
    """Everything both sites share, created once per process."""

    config: Config
    bus: EventBus = field(default_factory=EventBus)
    sessions: SessionStore = field(default_factory=SessionStore)
    #: Failed public-site password attempts, so a guesser is slowed and
    #: then locked out (ADR 0014). Shared by both password paths.
    throttle: LoginThrottle = field(default_factory=LoginThrottle)
    devices: DeviceStore = field(init=False)
    #: Builds, and the one slot they take turns in (ADR 0013). A sibling
    #: of :attr:`devices` and not a sub-object of it: both are long-lived,
    #: process-owned publishers on their own topic, and a build outlives
    #: the socket that asked for it.
    builds: BuildRegistry = field(init=False)
    #: Resolves ingress users to their Home Assistant admin status (ADR
    #: 0014). Supervisor-backed when a token is configured, fail-closed
    #: otherwise. A test replaces it to script an admin/non-admin user.
    admin_oracle: AdminOracle = field(init=False)
    #: Caps concurrent CPU-bound builder work so one client cannot exhaust
    #: the shared thread pool and stall every socket (the concurrency
    #: finding). Created here so all connections share one bound.
    builder_gate: asyncio.Semaphore = field(init=False)
    started_at: float = field(default_factory=time.monotonic)

    def __post_init__(self) -> None:
        self.devices = DeviceStore(
            self.config.config_root,
            self.bus,
            poll_interval=self.config.poll_interval,
        )
        self.builds = BuildRegistry(self.config, self.bus, devices=self.devices)
        self.admin_oracle = build_admin_oracle(
            supervisor_url=self.config.supervisor_url,
            supervisor_token=self.config.supervisor_token,
        )
        self.builder_gate = asyncio.Semaphore(BUILDER_CONCURRENCY)

    async def start(self) -> None:
        versions.check_mcuhome_version(MCUHOME_VERSION)
        await self.devices.start()
        await self.builds.start()

    async def stop(self) -> None:
        # Builds first: one may be holding the device store's tree open,
        # and a cancelled build should stop before the thing it reads.
        await self.builds.stop()
        await self.devices.stop()
        await self.admin_oracle.aclose()


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

    # Failed-attempt throttling (ADR 0014). Checked before the password is
    # weighed, so a locked source cannot keep guessing; the sibling bearer
    # path feeds the same counter through the auth middleware.
    source = peer_address(request)
    wait = state.throttle.retry_after(source)
    if wait > 0:
        raise web.HTTPTooManyRequests(
            text="Too many failed sign-in attempts. Try again later.",
            headers={"Retry-After": str(math.ceil(wait))},
        )

    try:
        body = await request.json()
    except (ValueError, TypeError):
        body = {}
    presented = body.get("password") if isinstance(body, dict) else None
    if not isinstance(presented, str) or not _matches(presented, state.config.password):
        # Deliberately identical for "no password" and "wrong password".
        state.throttle.record_failure(source)
        raise web.HTTPUnauthorized(text="Wrong password.")

    state.throttle.record_success(source)
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


async def build_artifact(request: web.Request) -> web.StreamResponse:
    """``GET /api/builds/{build}/artifacts/{path}`` — one file of a build.

    REST for the reason ADR 0004 decision 4 gives, and the only reason:
    ``<a download>``, the flash-tool hand-off of ADR 0010 and ``curl``
    all take a URL, and none of them can take a WebSocket frame. It came
    back with ADR 0013 together with the thing that fills the directory.

    **It serves the artifacts the record declares, and no other file.**
    ADR 0013 decision 5's "only what the build method declared and
    verified" is a rule about the *reader* as much as about the copier:
    a build method puts its scratch area inside the build directory, and
    that area holds the build context — the resolved device model, which
    carries the Matter pairing tuple. Serving the directory would put it
    one plain ``GET`` away, in a codebase where commissioning codes
    travel only when a user asked for them (AGENTS.md). So the requested
    path has to *be* one of ``record.artifacts``; anything else is not
    there.

    **Everything unservable is a 404, and nothing is a 403.** An unknown
    build id, a build that produced no artifacts, an undeclared file, a
    file that is not there, and a path that climbs out of the build's own
    directory all answer identically. Distinguishing them would confirm
    which guess was close — free reconnaissance — and there is no case
    where telling the caller *why* helps a legitimate one, because a
    legitimate one follows a link out of a build record it was given.

    It is under ``/api/``, so
    :data:`mcuhome.ui.security.PROTECTED_PREFIXES` already makes
    it need an identity on the public site — the same door as ``/ws``,
    which matters because these bytes are firmware signed with this
    installation's key.

    **And it needs an administrator** (ADR 0014). An artifact carries the
    device's Matter pairing tuple resolved into it, so on ingress it is
    gated the same as ``device/commissioning``: a non-admin is refused
    with ``403`` before the build id is even looked at, which is a blanket
    authorization answer that leaks nothing about which builds exist. The
    public site's identities are administrators by construction, so this
    changes only the ingress door.
    """
    state = request.app[STATE_KEY]
    identity = identity_of(request)
    if not (identity and identity.is_admin):
        raise web.HTTPForbidden(text="Downloading build artifacts needs an administrator.")
    try:
        record = state.builds.get(request.match_info["build"])
    except UnknownBuild:
        raise web.HTTPNotFound() from None
    if record.out_dir is None:
        raise web.HTTPNotFound()

    wanted = request.match_info["path"].lstrip("/")
    declared = {str(entry.get("path")) for entry in record.artifacts}
    if wanted not in declared:
        raise web.HTTPNotFound()

    root = Path(record.out_dir).resolve()
    candidate = (root / wanted).resolve()
    # Containment is kept as the cheap second answer: a declared name is
    # a name the build method chose, and this handler is not the place to
    # find out that one of them was `../`.
    if not candidate.is_relative_to(root) or not candidate.is_file():
        raise web.HTTPNotFound()

    # The name reaches a header, so it is reduced to characters that
    # cannot end the quoted string or start a second header. A build
    # method names its own artifacts and none of them contain anything
    # exotic — which is exactly why an artifact that did would be worth
    # neutering rather than trusting.
    safe = "".join(char for char in candidate.name if char.isalnum() or char in "._-") or "artifact"
    return web.FileResponse(
        candidate,
        headers={
            # Firmware, not a page: never rendered, never sniffed, and
            # named after the file it is rather than the URL it came from.
            "Content-Type": "application/octet-stream",
            "Content-Disposition": f'attachment; filename="{safe}"',
            "Cache-Control": "no-store",
        },
    )


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
    app.router.add_get("/api/builds/{build}/artifacts/{path:.*}", build_artifact)
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
