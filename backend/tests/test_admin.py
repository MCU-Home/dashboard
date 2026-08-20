# SPDX-FileCopyrightText: 2026 The MCUHome Contributors
# SPDX-License-Identifier: Apache-2.0
"""ADR 0014: dashboard access in Home Assistant is admin-only.

Two layers, tested apart. :mod:`mcuhome.ui.admin` turns a trusted
username into an admin decision by asking the Supervisor; the command
layer and the artifact route turn that decision into a refusal. The
public site is untouched — its identities are administrators by
construction — so every gate test here is on the ingress site, which is
the only place Home Assistant has non-admin users.
"""

from __future__ import annotations

from pathlib import Path

import aiohttp
import pytest

from mcuhome.ui.admin import (
    NoAdminOracle,
    SupervisorAdminOracle,
    build_admin_oracle,
    user_is_admin,
)
from mcuhome.ui.app import AppState, create_app
from mcuhome.ui.config import Config
from mcuhome.ui.security import TrustMode
from tests.conftest import (
    ADMIN_HEADERS,
    ADMIN_USER,
    NON_ADMIN_HEADERS,
    StubAdminOracle,
    call,
)

# --------------------------------------------------------------------------
# The admin module: what counts as an admin, and how it is fetched
# --------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("record", "expected"),
    [
        ({"username": "o", "is_owner": True, "group_ids": []}, True),
        ({"username": "a", "is_owner": False, "group_ids": ["system-admin"]}, True),
        ({"username": "a", "is_owner": False, "group_ids": ["system-admin", "x"]}, True),
        ({"username": "u", "is_owner": False, "group_ids": ["system-users"]}, False),
        ({"username": "u", "group_ids": []}, False),
        # An unreadable record is not a licence.
        ({"username": "u"}, False),
        ({"username": "u", "group_ids": "system-admin"}, False),
    ],
)
def test_user_is_admin_reads_owner_or_the_admin_group(record: dict, expected: bool) -> None:
    assert user_is_admin(record) is expected


def test_no_token_gets_the_fail_closed_oracle() -> None:
    assert isinstance(build_admin_oracle(supervisor_url="x", supervisor_token=None), NoAdminOracle)
    assert isinstance(build_admin_oracle(supervisor_url="x", supervisor_token=""), NoAdminOracle)
    assert isinstance(
        build_admin_oracle(supervisor_url="http://supervisor", supervisor_token="t"),
        SupervisorAdminOracle,
    )


class _FakeResponse:
    def __init__(self, payload: object) -> None:
        self._payload = payload

    def raise_for_status(self) -> None:
        return None

    async def json(self) -> object:
        return self._payload

    async def __aenter__(self) -> _FakeResponse:
        return self

    async def __aexit__(self, *_exc: object) -> bool:
        return False


class _FakeSession:
    """The one method :class:`SupervisorAdminOracle` calls: ``get``."""

    def __init__(self, payload: object = None, error: Exception | None = None) -> None:
        self._payload = payload
        self._error = error
        self.calls = 0

    def get(self, url: str, **_kwargs: object) -> _FakeResponse:
        self.calls += 1
        if self._error is not None:
            raise self._error
        return _FakeResponse(self._payload)

    async def close(self) -> None:
        return None


#: A `/auth/list` body in the Supervisor's own envelope: an owner, a group
#: admin, a regular user, and a credential-less user that must be ignored.
ROSTER = {
    "result": "ok",
    "data": {
        "users": [
            {"username": "owner", "is_owner": True, "group_ids": []},
            {"username": "admin", "is_owner": False, "group_ids": ["system-admin"]},
            {"username": "regular", "is_owner": False, "group_ids": ["system-users"]},
            {"username": None, "is_owner": True, "group_ids": ["system-admin"]},
        ]
    },
}


async def test_the_oracle_resolves_owners_and_group_admins() -> None:
    session = _FakeSession(payload=ROSTER)
    oracle = SupervisorAdminOracle("http://supervisor", "token", session=session)

    assert await oracle.is_admin(user_id=None, username="owner") is True
    assert await oracle.is_admin(user_id=None, username="admin") is True
    assert await oracle.is_admin(user_id=None, username="regular") is False
    # The credential-less admin never entered the roster, so its absence
    # is a non-admin rather than a match on `None`.
    assert await oracle.is_admin(user_id=None, username=None) is None
    # One fetch answered all of them: the roster is cached.
    assert session.calls == 1


async def test_a_supervisor_that_will_not_answer_fails_closed() -> None:
    session = _FakeSession(error=aiohttp.ClientError("boom"))
    oracle = SupervisorAdminOracle("http://supervisor", "token", session=session)

    # None, not False and not a raise: the caller treats "cannot tell" as
    # non-admin, and a transient failure is not cached.
    assert await oracle.is_admin(user_id=None, username="admin") is None
    assert await oracle.is_admin(user_id=None, username="admin") is None
    assert session.calls == 2


async def test_a_shapeless_response_is_no_admins_rather_than_a_crash() -> None:
    oracle = SupervisorAdminOracle(
        "http://supervisor", "token", session=_FakeSession(payload={"data": {}})
    )
    assert await oracle.is_admin(user_id=None, username="admin") is None


# --------------------------------------------------------------------------
# The gate: every admin-only verb over the ingress site
# --------------------------------------------------------------------------

GATED_VERBS = [
    ("device/save", {"name": "no-such-node", "content": "x"}),
    ("device/commissioning", {"name": "no-such-node"}),
    ("build/start", {"name": "no-such-node"}),
    ("build/cancel", {"build_id": "no-such-build"}),
]


@pytest.mark.parametrize(("verb", "payload"), GATED_VERBS)
async def test_a_non_admin_ingress_user_is_refused_every_gated_verb(
    roster_ingress_client, verb: str, payload: dict
) -> None:
    async with roster_ingress_client.ws_connect("/ws", headers=NON_ADMIN_HEADERS) as ws:
        frame = await call(ws, verb, payload)

    assert frame["type"] == "error"
    assert frame["error"]["code"] == "unauthorized"


@pytest.mark.parametrize(("verb", "payload"), GATED_VERBS)
async def test_an_admin_ingress_user_passes_the_gate(
    roster_ingress_client, verb: str, payload: dict
) -> None:
    """The admin clears the gate: the refusal that remains is *not* the gate.

    Every payload names something that does not exist, so a verb that got
    past the admin check answers ``not_found`` — which proves the gate
    opened without starting a build or writing a file.
    """
    async with roster_ingress_client.ws_connect("/ws", headers=ADMIN_HEADERS) as ws:
        frame = await call(ws, verb, payload)

    assert frame["type"] == "error"
    assert frame["error"]["code"] == "not_found"


async def test_an_admin_can_read_commissioning_codes(roster_ingress_client) -> None:
    """The capability the gate protects, exercised on a real device."""
    async with roster_ingress_client.ws_connect("/ws", headers=ADMIN_HEADERS) as ws:
        payload = (await call(ws, "device/commissioning", {"name": "bench-node"}))["payload"]

    assert payload["ok"] is True
    assert payload["commissioning"] is not None


async def test_an_admin_can_save_a_device(roster_ingress_client, tree: Path) -> None:
    from tests.conftest import VALID_CONFIG

    edited = VALID_CONFIG.replace("sampling: 10s", "sampling: 45s")
    async with roster_ingress_client.ws_connect("/ws", headers=ADMIN_HEADERS) as ws:
        frame = await call(ws, "device/save", {"name": "bench-node", "content": edited})

    assert frame["type"] == "result"
    assert (tree / "devices" / "bench-node" / "main.yaml").read_text("utf-8") == edited


async def test_a_non_admin_cannot_save_a_device(roster_ingress_client, tree: Path) -> None:
    before = (tree / "devices" / "bench-node" / "main.yaml").read_text("utf-8")
    async with roster_ingress_client.ws_connect("/ws", headers=NON_ADMIN_HEADERS) as ws:
        frame = await call(ws, "device/save", {"name": "bench-node", "content": "wiped"})

    assert frame["error"]["code"] == "unauthorized"
    # The refusal is real: the file on disk is untouched.
    assert (tree / "devices" / "bench-node" / "main.yaml").read_text("utf-8") == before


async def test_the_read_only_views_stay_open_to_any_ingress_user(roster_ingress_client) -> None:
    """The line ADR 0014 draws: reading the tree is open, mutating it is not."""
    async with roster_ingress_client.ws_connect("/ws", headers=NON_ADMIN_HEADERS) as ws:
        assert (await call(ws, "server/info"))["payload"]["identity"]["is_admin"] is False
        assert (await call(ws, "device/list", frame_id="2"))["payload"]["tree"]["available"] is True
        got = await call(ws, "device/get", {"name": "bench-node"}, frame_id="3")
        assert got["type"] == "result"
        validated = await call(ws, "device/validate", {"name": "bench-node"}, frame_id="4")
        assert validated["payload"]["ok"] is True
        assert (await call(ws, "config/subscribe", frame_id="5"))["payload"]["topic"] == "devices"


async def test_with_no_token_no_ingress_user_is_an_admin(ingress_client) -> None:
    """Fail closed: the default deployment resolves nobody to admin."""
    async with ingress_client.ws_connect("/ws", headers=ADMIN_HEADERS) as ws:
        frame = await call(ws, "device/save", {"name": "bench-node", "content": "x"})

    assert frame["error"]["code"] == "unauthorized"


async def test_the_admin_flag_reaches_server_info_for_an_admin(roster_ingress_client) -> None:
    async with roster_ingress_client.ws_connect("/ws", headers=ADMIN_HEADERS) as ws:
        identity = (await call(ws, "server/info"))["payload"]["identity"]

    assert identity["is_admin"] is True
    assert identity["user_name"] == ADMIN_USER


# --------------------------------------------------------------------------
# The gate: the artifact download route (REST)
# --------------------------------------------------------------------------


async def test_a_non_admin_cannot_download_artifacts(state: AppState, aiohttp_client) -> None:
    """403 before the build id is even looked at — a passcode-bearing file."""
    state.admin_oracle = StubAdminOracle({ADMIN_USER})
    client = await aiohttp_client(create_app(state, TrustMode.INGRESS))
    response = await client.get(
        "/api/builds/whatever/artifacts/firmware.bin", headers=NON_ADMIN_HEADERS
    )
    assert response.status == 403


async def test_an_admin_reaches_the_artifact_route(state: AppState, aiohttp_client) -> None:
    """Past the gate the answer is the ordinary 404 for an unknown build."""
    state.admin_oracle = StubAdminOracle({ADMIN_USER})
    client = await aiohttp_client(create_app(state, TrustMode.INGRESS))
    response = await client.get(
        "/api/builds/whatever/artifacts/firmware.bin", headers=ADMIN_HEADERS
    )
    assert response.status == 404


async def test_the_public_artifact_route_is_admin_by_construction(
    tree: Path, tmp_path: Path, aiohttp_client
) -> None:
    """A public open (loopback) identity is an operator, so the gate is open."""
    state = AppState(Config(config_root=tree, poll_interval=0.0, data_dir=tmp_path / "data"))
    await state.start()
    try:
        client = await aiohttp_client(create_app(state, TrustMode.PUBLIC))
        # 404 (unknown build), not 403: the gate did not bite.
        response = await client.get("/api/builds/whatever/artifacts/firmware.bin")
        assert response.status == 404
    finally:
        await state.stop()
