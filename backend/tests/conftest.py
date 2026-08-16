# SPDX-FileCopyrightText: 2026 The MCUHome Contributors
# SPDX-License-Identifier: Apache-2.0
"""Shared fixtures.

The fixture tree follows the builder repository's pattern (a valid
baseline configuration that individual tests break one thing at a time),
because the dashboard's job is to carry the builder's answers unchanged
— a fixture that drifts from the builder's would test the dashboard
against a configuration language nobody speaks.

Everything here runs in-process: no subprocess, no real socket beyond
the loopback one aiohttp's test client opens, no container. The suite is
expected to stay under a second.
"""

from __future__ import annotations

from collections.abc import Iterator
from pathlib import Path

import pytest
from mcuhome.workbench import api as workbench_api

from mcuhome_dashboard.app import AppState, create_app
from mcuhome_dashboard.config import Config
from mcuhome_dashboard.security import TrustMode

#: A configuration that passes every check the builder makes.
VALID_CONFIG = """\
device:
  name: bench-node
  board: nrf7002dk/nrf5340/cpuapp

network:
  thread:
    device_role: ftd
  matter:
    enabled: true
    use_test_pairing: true

hardware:
  buses:
    i2c0:
      controller: arduino_i2c
  peripherals:
    baro:
      driver: bosch,bmp180
      bus: i2c0

node:
  endpoints:
    - id: 1
      device_type: temperature_sensor
      clusters:
        temperature_measurement:
          source: baro.temperature
          sampling: 10s
"""

#: The same configuration with one thing wrong, on a line and column the
#: editor has to be able to point at.
BROKEN_CONFIG = VALID_CONFIG.replace("name: bench-node", "name: broken-node").replace(
    "board: nrf7002dk/nrf5340/cpuapp", "board: nrf99dk-does-not-exist"
)
#: Where the bad board sits in :data:`BROKEN_CONFIG`, 1-based.
BROKEN_BOARD_LINE = 3
BROKEN_BOARD_COLUMN = 10


def write_device(root: Path, name: str, config: str) -> Path:
    """Create ``<root>/devices/<name>/main.yaml``."""
    folder = root / "devices" / name
    folder.mkdir(parents=True, exist_ok=True)
    entry = folder / "main.yaml"
    entry.write_text(config, encoding="utf-8")
    return entry


def make_tree(root: Path, devices: dict[str, str] | None = None) -> Path:
    """Build a project directory at *root*.

    The project is created by the workbench's own ``init_project``
    rather than by writing the marker here. A fixture that writes the
    marker by hand states the project format a second time, and the
    second statement is the one that goes stale: this suite spent the
    freeze red because the hand-written marker stayed at version 0
    after the workbench moved to version 1. Calling the real thing
    means the fixture follows the format wherever it goes.
    """
    workbench_api.init_project(root)
    for name, config in (devices or {}).items():
        write_device(root, name, config)
    return root


@pytest.fixture
def tree(tmp_path: Path) -> Path:
    """A tree with one valid and one broken device."""
    return make_tree(
        tmp_path / "config",
        {"bench-node": VALID_CONFIG, "broken-node": BROKEN_CONFIG},
    )


@pytest.fixture
def config(tree: Path, tmp_path: Path) -> Config:
    """A loopback, password-free configuration with the poll switched off.

    ``poll_interval=0`` keeps the background task out of the tests:
    every command refreshes the store before answering, so the tests
    drive the scanner deterministically instead of waiting for a timer.

    ``data_dir`` is under ``tmp_path`` so that nothing in the suite can
    reach the developer's real ``/data`` or state directory — that is
    where ADR 0008 puts the firmware signing key, and a test that wrote
    one there would be writing into somebody's trust anchor.
    """
    return Config(config_root=tree, poll_interval=0.0, data_dir=tmp_path / "data")


@pytest.fixture
async def state(config: Config) -> Iterator[AppState]:
    app_state = AppState(config)
    await app_state.start()
    yield app_state
    await app_state.stop()


@pytest.fixture
async def client(aiohttp_client, state: AppState):
    """A client of the public site."""
    return await aiohttp_client(create_app(state, TrustMode.PUBLIC))


@pytest.fixture
async def ingress_client(aiohttp_client, state: AppState):
    """A client of the ingress site (no Supervisor token: nobody is admin)."""
    return await aiohttp_client(create_app(state, TrustMode.INGRESS))


#: The username the roster fixtures below treat as an administrator, and
#: the one they do not. The Supervisor sets ``X-Remote-User-Name`` from
#: the authenticated session, so a test that sends it is standing in for
#: the Supervisor, not for a client that could forge it (ADR 0014).
ADMIN_USER = "admin-user"
NON_ADMIN_USER = "regular-user"
ADMIN_HEADERS = {"X-Remote-User-Id": "u-admin", "X-Remote-User-Name": ADMIN_USER}
NON_ADMIN_HEADERS = {"X-Remote-User-Id": "u-regular", "X-Remote-User-Name": NON_ADMIN_USER}


class StubAdminOracle:
    """A scripted stand-in for the Supervisor's admin roster (ADR 0014).

    ``is_admin`` answers from a fixed set of admin usernames, and ``None``
    when there is no username to match — the same fail-closed shape the
    real :class:`mcuhome_dashboard.admin.SupervisorAdminOracle` has.
    """

    def __init__(self, admins: set[str] | None = None) -> None:
        self.admins = set(admins or ())
        self.calls: list[tuple[str | None, str | None]] = []

    async def is_admin(self, *, user_id: str | None, username: str | None) -> bool | None:
        self.calls.append((user_id, username))
        if username is None:
            return None
        return username in self.admins

    async def aclose(self) -> None:
        return None


@pytest.fixture
async def roster_ingress_client(aiohttp_client, state: AppState):
    """An ingress site whose Supervisor roster has exactly one admin."""
    state.admin_oracle = StubAdminOracle({ADMIN_USER})
    return await aiohttp_client(create_app(state, TrustMode.INGRESS))


async def call(ws, command_type: str, payload: dict | None = None, frame_id: str = "1") -> dict:
    """Send one command and return the frame that answers it."""
    await ws.send_json({"id": frame_id, "type": command_type, "payload": payload or {}})
    while True:
        frame = await ws.receive_json(timeout=5)
        if frame.get("id") == frame_id:
            return frame
