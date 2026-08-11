# SPDX-FileCopyrightText: 2026 The MCUHome Contributors
# SPDX-License-Identifier: Apache-2.0
"""ADR 0009: the two sites, the password rules, origin and CSRF.

ADR 0009's consequences name the failure worth a test explicitly — "a
misconfiguration that exposes the trusting site". So the tests here are
mostly about *refusals*: the ingress site refusing a peer that is not
the Supervisor, the public site refusing a request without the password,
and the origin check refusing a page that is not ours.
"""

from __future__ import annotations

import logging
from pathlib import Path

import pytest
from aiohttp import WSServerHandshakeError

from mcuhome_dashboard import security, server
from mcuhome_dashboard.app import AppState, create_app
from mcuhome_dashboard.config import Config, is_loopback_host, load_config, resolve_password
from mcuhome_dashboard.security import CSRF_HEADER, SESSION_COOKIE, TrustMode
from tests.conftest import call

PASSWORD = "correct-horse-battery-staple"


# --------------------------------------------------------------------------
# Decision 2 — the password rules
# --------------------------------------------------------------------------


@pytest.mark.parametrize("host", ["127.0.0.1", "127.0.0.5", "::1", "localhost"])
def test_a_loopback_bind_needs_no_password(host: str) -> None:
    assert is_loopback_host(host)
    assert resolve_password(None, host) == (None, False)


@pytest.mark.parametrize("host", ["0.0.0.0", "192.168.1.10", "::"])
def test_any_other_bind_gets_a_password_generated_for_it(host: str) -> None:
    assert not is_loopback_host(host)
    password, generated = resolve_password(None, host)
    assert generated is True
    assert password and len(password) >= 16


def test_a_configured_password_always_wins() -> None:
    assert resolve_password(PASSWORD, "127.0.0.1") == (PASSWORD, False)
    assert resolve_password(PASSWORD, "0.0.0.0") == (PASSWORD, False)


def test_the_rules_survive_the_trip_through_the_command_line() -> None:
    assert load_config([], env={}).password is None
    exposed = load_config(["--host", "0.0.0.0"], env={})
    assert exposed.password_generated is True
    assert exposed.auth_required is True

    from_env = load_config([], env={"MCUHOME_DASHBOARD_PASSWORD": PASSWORD})
    assert from_env.password == PASSWORD
    assert from_env.password_generated is False


def test_a_generated_password_is_logged_once_and_visibly(caplog) -> None:
    config = load_config(["--host", "0.0.0.0"], env={})
    with caplog.at_level(logging.INFO, logger="mcuhome_dashboard.server"):
        server._announce(config)

    generated = [record for record in caplog.records if config.password in record.getMessage()]
    assert len(generated) == 1
    # WARNING, not INFO: an operator who cannot find this line cannot
    # log in at all.
    assert generated[0].levelno == logging.WARNING


def test_a_password_free_loopback_run_says_so(caplog) -> None:
    with caplog.at_level(logging.INFO, logger="mcuhome_dashboard.server"):
        server._announce(load_config([], env={}))
    assert any("without a password" in record.getMessage() for record in caplog.records)


def test_serving_nothing_is_refused_rather_than_started() -> None:
    with pytest.raises(SystemExit):
        load_config(["--no-public-site"], env={})


# --------------------------------------------------------------------------
# The public site
# --------------------------------------------------------------------------


@pytest.fixture
async def secure_client(aiohttp_client, tree: Path):
    """A public site that is exposed, and therefore password-protected."""
    state = AppState(Config(config_root=tree, host="0.0.0.0", password=PASSWORD, poll_interval=0.0))
    await state.start()
    client = await aiohttp_client(create_app(state, TrustMode.PUBLIC))
    yield client
    await state.stop()


async def test_the_websocket_is_refused_without_the_password(secure_client) -> None:
    with pytest.raises(WSServerHandshakeError) as caught:
        await secure_client.ws_connect("/ws")
    assert caught.value.status == 401


async def test_health_stays_open_so_an_orchestrator_can_probe_it(secure_client) -> None:
    response = await secure_client.get("/health")
    assert response.status == 200
    body = await response.json()
    assert body["status"] == "ok"
    assert body["trust"] == "public"


async def test_a_bearer_token_is_enough_for_a_script(secure_client) -> None:
    async with secure_client.ws_connect(
        "/ws", headers={"Authorization": f"Bearer {PASSWORD}"}
    ) as ws:
        assert (await call(ws, "ping"))["payload"]["pong"] is True


async def test_a_wrong_bearer_token_is_not(secure_client) -> None:
    with pytest.raises(WSServerHandshakeError):
        await secure_client.ws_connect("/ws", headers={"Authorization": "Bearer nope"})


async def test_login_hands_out_a_cookie_and_a_csrf_token(secure_client) -> None:
    response = await secure_client.post("/auth/login", json={"password": PASSWORD})
    assert response.status == 200
    assert SESSION_COOKIE in response.cookies
    assert (await response.json())["csrf_token"]

    # The cookie alone now opens the socket.
    async with secure_client.ws_connect("/ws") as ws:
        assert (await call(ws, "ping"))["payload"]["pong"] is True


async def test_login_refuses_the_wrong_password(secure_client) -> None:
    assert (await secure_client.post("/auth/login", json={"password": "nope"})).status == 401
    assert (await secure_client.post("/auth/login", json={})).status == 401


async def test_logout_needs_the_csrf_token(secure_client) -> None:
    token = (await (await secure_client.post("/auth/login", json={"password": PASSWORD})).json())[
        "csrf_token"
    ]

    assert (await secure_client.post("/auth/logout")).status == 403
    assert (await secure_client.post("/auth/logout", headers={CSRF_HEADER: "wrong"})).status == 403

    response = await secure_client.post("/auth/logout", headers={CSRF_HEADER: token})
    assert response.status == 200

    # The session is really gone, not just un-cookied on the client.
    with pytest.raises(WSServerHandshakeError):
        await secure_client.ws_connect("/ws", headers={"Cookie": f"{SESSION_COOKIE}=whatever"})


async def test_a_password_free_loopback_site_serves_without_a_login(client) -> None:
    async with client.ws_connect("/ws") as ws:
        info = (await call(ws, "server/info"))["payload"]
    assert info["identity"]["kind"] == "open"


# --------------------------------------------------------------------------
# Decision 3 — the origin check
# --------------------------------------------------------------------------


async def test_a_foreign_origin_may_not_open_a_websocket(client) -> None:
    with pytest.raises(WSServerHandshakeError) as caught:
        await client.ws_connect("/ws", headers={"Origin": "http://evil.example"})
    assert caught.value.status == 403


async def test_our_own_origin_may(client) -> None:
    origin = f"http://{client.host}:{client.port}"
    async with client.ws_connect("/ws", headers={"Origin": origin}) as ws:
        assert (await call(ws, "ping"))["payload"]["pong"] is True


async def test_a_configured_origin_may(aiohttp_client, tree: Path) -> None:
    state = AppState(
        Config(
            config_root=tree,
            poll_interval=0.0,
            allowed_origins=("https://dashboard.example.org",),
        )
    )
    await state.start()
    client = await aiohttp_client(create_app(state, TrustMode.PUBLIC))
    try:
        async with client.ws_connect(
            "/ws", headers={"Origin": "https://dashboard.example.org"}
        ) as ws:
            assert (await call(ws, "ping"))["payload"]["pong"] is True
    finally:
        await state.stop()


async def test_a_foreign_origin_may_not_log_in_either(secure_client) -> None:
    response = await secure_client.post(
        "/auth/login", json={"password": PASSWORD}, headers={"Origin": "http://evil.example"}
    )
    assert response.status == 403


# --------------------------------------------------------------------------
# Decision 1 — the ingress site
# --------------------------------------------------------------------------


def test_only_loopback_and_the_supervisor_are_trusted_peers() -> None:
    assert security.is_trusted_peer("127.0.0.1")
    assert security.is_trusted_peer("::1")
    assert security.is_trusted_peer("::ffff:127.0.0.1")
    assert security.is_trusted_peer(security.SUPERVISOR_GATEWAY)
    assert not security.is_trusted_peer("172.30.32.7")
    assert not security.is_trusted_peer("192.168.1.20")
    # An unknown peer (a Unix socket, a test transport) falls back to
    # the binding as the control.
    assert security.is_trusted_peer(None)


async def test_the_ingress_site_needs_no_password(ingress_client) -> None:
    async with ingress_client.ws_connect("/ws") as ws:
        info = (await call(ws, "server/info"))["payload"]
    assert info["deployment"]["trust"] == "ingress"
    assert info["identity"]["kind"] == "ingress"


async def test_ingress_identity_headers_are_display_only(ingress_client) -> None:
    headers = {"X-Remote-User-Id": "abc123", "X-Remote-User-Name": "Stefan"}
    async with ingress_client.ws_connect("/ws", headers=headers) as ws:
        identity = (await call(ws, "server/info"))["payload"]["identity"]

    # The default deployment has no Supervisor token, so admin resolves to
    # False (fail closed, ADR 0014) — the headers still only decorate.
    assert identity == {
        "kind": "ingress",
        "user_id": "abc123",
        "user_name": "Stefan",
        "is_admin": False,
    }


async def test_the_ingress_site_accepts_the_home_assistant_origin(ingress_client) -> None:
    # An ingress page is an iframe on the Home Assistant origin, which
    # never matches this server's Host. The peer check is the control.
    async with ingress_client.ws_connect(
        "/ws", headers={"Origin": "http://homeassistant.local:8123"}
    ) as ws:
        assert (await call(ws, "ping"))["payload"]["pong"] is True


async def test_the_ingress_site_refuses_a_peer_that_is_not_the_supervisor(
    ingress_client, monkeypatch
) -> None:
    monkeypatch.setattr(security, "_peer_address", lambda request: "192.168.1.20")

    assert (await ingress_client.get("/health")).status == 403
    assert (await ingress_client.get("/")).status == 403
    with pytest.raises(WSServerHandshakeError) as caught:
        await ingress_client.ws_connect("/ws")
    assert caught.value.status == 403


async def test_the_ingress_site_has_no_login_to_offer(ingress_client) -> None:
    assert (await ingress_client.post("/auth/login", json={"password": PASSWORD})).status == 405


# --------------------------------------------------------------------------
# ADR 0014 — failed-login throttling on the public site
# --------------------------------------------------------------------------


def test_the_throttle_locks_after_the_threshold_and_clears_on_time() -> None:
    now = [0.0]
    throttle = security.LoginThrottle(clock=lambda: now[0])
    source = "203.0.113.7"

    # The honest fat-fingering window: below the threshold nothing locks.
    for _ in range(security.LOGIN_FAILURE_THRESHOLD - 1):
        throttle.record_failure(source)
        assert throttle.retry_after(source) == 0.0

    throttle.record_failure(source)  # the threshold-th failure arms it
    wait = throttle.retry_after(source)
    assert wait > 0.0

    now[0] += wait + 0.01
    assert throttle.retry_after(source) == 0.0


def test_the_throttle_backs_off_exponentially() -> None:
    now = [0.0]
    throttle = security.LoginThrottle(clock=lambda: now[0])
    source = "198.51.100.4"
    for _ in range(security.LOGIN_FAILURE_THRESHOLD):
        throttle.record_failure(source)
    first = throttle.retry_after(source)
    throttle.record_failure(source)
    assert throttle.retry_after(source) > first


def test_a_correct_password_clears_the_count() -> None:
    throttle = security.LoginThrottle()
    source = "192.0.2.9"
    for _ in range(security.LOGIN_FAILURE_THRESHOLD):
        throttle.record_failure(source)
    assert throttle.retry_after(source) > 0.0
    throttle.record_success(source)
    assert throttle.retry_after(source) == 0.0


def test_the_global_backstop_catches_a_distributed_guess() -> None:
    now = [0.0]
    throttle = security.LoginThrottle(clock=lambda: now[0])
    # Every failure from a different source, so no single source is locked
    # — the backstop is the only thing that can see the pattern.
    for index in range(security.LOGIN_GLOBAL_THRESHOLD):
        throttle.record_failure(f"src-{index}")
    assert throttle.retry_after("a-brand-new-source") > 0.0


def test_a_quiet_source_is_eventually_forgotten() -> None:
    now = [0.0]
    throttle = security.LoginThrottle(clock=lambda: now[0])
    throttle.record_failure("192.0.2.55")
    now[0] += security.LOGIN_FAILURE_TTL + 1
    assert throttle.retry_after("192.0.2.55") == 0.0
    assert throttle._by_source == {}


async def test_repeated_wrong_logins_are_locked_out(secure_client) -> None:
    for _ in range(security.LOGIN_FAILURE_THRESHOLD):
        response = await secure_client.post("/auth/login", json={"password": "nope"})
        assert response.status == 401

    locked = await secure_client.post("/auth/login", json={"password": "nope"})
    assert locked.status == 429
    assert int(locked.headers["Retry-After"]) >= 1

    # A locked source is refused even with the right password, until the
    # window passes — the lockout is on the source, not on the guess.
    correct = await secure_client.post("/auth/login", json={"password": PASSWORD})
    assert correct.status == 429


async def test_a_success_before_the_threshold_keeps_the_door_open(secure_client) -> None:
    for _ in range(security.LOGIN_FAILURE_THRESHOLD - 1):
        assert (await secure_client.post("/auth/login", json={"password": "no"})).status == 401
    # The correct password clears the count, so the next wrong ones start
    # over rather than tipping straight into a lockout.
    assert (await secure_client.post("/auth/login", json={"password": PASSWORD})).status == 200
    assert (await secure_client.post("/auth/login", json={"password": "no"})).status == 401


async def test_repeated_wrong_bearer_tokens_are_locked_out(secure_client) -> None:
    for _ in range(security.LOGIN_FAILURE_THRESHOLD):
        with pytest.raises(WSServerHandshakeError) as caught:
            await secure_client.ws_connect("/ws", headers={"Authorization": "Bearer nope"})
        assert caught.value.status == 401

    with pytest.raises(WSServerHandshakeError) as caught:
        await secure_client.ws_connect("/ws", headers={"Authorization": "Bearer nope"})
    assert caught.value.status == 429
