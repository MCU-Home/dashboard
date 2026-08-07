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
    """Build a configuration tree at *root*."""
    (root / "devices").mkdir(parents=True, exist_ok=True)
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
def config(tree: Path) -> Config:
    """A loopback, password-free configuration with the poll switched off.

    ``poll_interval=0`` keeps the background task out of the tests:
    every command refreshes the store before answering, so the tests
    drive the scanner deterministically instead of waiting for a timer.
    """
    return Config(config_root=tree, poll_interval=0.0)


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
    """A client of the ingress site."""
    return await aiohttp_client(create_app(state, TrustMode.INGRESS))


async def call(ws, command_type: str, payload: dict | None = None, frame_id: str = "1") -> dict:
    """Send one command and return the frame that answers it."""
    await ws.send_json({"id": frame_id, "type": command_type, "payload": payload or {}})
    while True:
        frame = await ws.receive_json(timeout=5)
        if frame.get("id") == frame_id:
            return frame
