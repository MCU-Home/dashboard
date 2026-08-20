# SPDX-FileCopyrightText: 2026 The MCUHome Contributors
# SPDX-License-Identifier: Apache-2.0
"""``device/validate``: the builder's errors, unchanged, as data.

ADR 0004 decision 5 is the point of this file. A ``ConfigError`` already
carries file, line, column, dotted key and a fix hint; if any of those
is lost between the builder and the wire, the editor cannot put a marker
on a line and the whole in-process coupling of ADR 0011 has bought
nothing. So the assertions here are about *fields arriving*, not about
message wording — the wording is the builder repository's test suite's
business.
"""

from __future__ import annotations

from pathlib import Path

from mcuhome.ui.app import AppState, create_app
from mcuhome.ui.config import Config
from mcuhome.ui.security import TrustMode
from tests.conftest import (
    BROKEN_BOARD_COLUMN,
    BROKEN_BOARD_LINE,
    VALID_CONFIG,
    call,
    write_device,
)


async def test_a_valid_device_validates(client) -> None:
    async with client.ws_connect("/ws") as ws:
        payload = (await call(ws, "device/validate", {"name": "bench-node"}))["payload"]

    assert payload["ok"] is True
    assert payload["errors"] == []
    assert payload["device"]["board"] == "nrf7002dk/nrf5340/cpuapp"
    assert payload["device"]["transport"] == "thread"
    assert payload["device"]["endpoints"][0]["device_types"] == ["temperature_sensor"]


async def test_a_broken_device_returns_diagnostics_with_a_line_and_a_column(client) -> None:
    async with client.ws_connect("/ws") as ws:
        frame = await call(ws, "device/validate", {"name": "broken-node"})

    # A configuration that does not validate is a *successful* command.
    assert frame["type"] == "result"
    payload = frame["payload"]
    assert payload["ok"] is False
    assert payload["device"] is None

    (error,) = payload["errors"]
    assert error["file"] == "devices/broken-node/main.yaml"
    assert error["line"] == BROKEN_BOARD_LINE
    assert error["column"] == BROKEN_BOARD_COLUMN
    assert error["key"] == "device.board"
    assert error["hint"]
    assert "nrf99dk-does-not-exist" in error["message"]


async def test_the_error_file_is_tree_relative_not_an_absolute_server_path(client) -> None:
    async with client.ws_connect("/ws") as ws:
        payload = (await call(ws, "device/validate", {"name": "broken-node"}))["payload"]

    assert not Path(payload["errors"][0]["file"]).is_absolute()


async def test_every_problem_is_reported_not_only_the_first(client, tree: Path) -> None:
    # The builder deliberately collects a whole stage's problems rather
    # than stopping at the first, and an editor wants all the markers at
    # once. Both faults here are cross-reference checks, so they land in
    # the same collected group.
    write_device(
        tree,
        "very-broken",
        VALID_CONFIG.replace("name: bench-node", "name: very-broken")
        .replace("board: nrf7002dk/nrf5340/cpuapp", "board: nrf99dk-does-not-exist")
        .replace("driver: bosch,bmp180", "driver: acme,nonexistent"),
    )
    async with client.ws_connect("/ws") as ws:
        payload = (await call(ws, "device/validate", {"name": "very-broken"}))["payload"]

    assert payload["ok"] is False
    assert len(payload["errors"]) == 2
    assert [error["key"] for error in payload["errors"]] == [
        "device.board",
        "hardware.peripherals.baro.driver",
    ]
    # Reported in file order, so the markers land top to bottom.
    assert [error["line"] for error in payload["errors"]] == sorted(
        error["line"] for error in payload["errors"]
    )


async def test_unparsable_yaml_still_arrives_with_a_position(client, tree: Path) -> None:
    write_device(tree, "syntax-error", "device:\n  name: x\n   board: y\n")
    async with client.ws_connect("/ws") as ws:
        payload = (await call(ws, "device/validate", {"name": "syntax-error"}))["payload"]

    assert payload["ok"] is False
    (error,) = payload["errors"]
    assert error["file"] == "devices/syntax-error/main.yaml"
    assert error["line"] is not None


async def test_validate_refuses_an_unknown_device(client) -> None:
    async with client.ws_connect("/ws") as ws:
        frame = await call(ws, "device/validate", {"name": "nope"})

    assert frame["error"]["code"] == "not_found"


async def test_without_a_tree_validate_says_so_instead_of_failing_obscurely(
    aiohttp_client, tmp_path: Path
) -> None:
    state = AppState(Config(config_root=tmp_path / "nothing-here", poll_interval=0.0))
    await state.start()
    try:
        client = await aiohttp_client(create_app(state, TrustMode.PUBLIC))
        async with client.ws_connect("/ws") as ws:
            listed = (await call(ws, "device/list"))["payload"]
            frame = await call(ws, "device/validate", {"name": "anything"}, frame_id="2")
    finally:
        await state.stop()

    assert listed["devices"] == []
    assert listed["tree"]["available"] is False
    assert frame["error"]["code"] == "unavailable"
