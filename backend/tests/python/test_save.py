# SPDX-FileCopyrightText: 2026 The MCUHome Contributors
# SPDX-License-Identifier: Apache-2.0
"""``device/save``: the write direction, and the race it has to lose.

The dashboard is not the only writer of the configuration tree (ADR 0008)
— Studio Code Server, a git checkout and a text editor on a mounted share
all edit it behind the dashboard's back. So the interesting assertions
here are not "the bytes arrived" but the two properties that make an
editor safe against that:

* a save that names the version it edited is **refused** when that
  version is no longer what is on disk, instead of overwriting somebody
  else's work;
* the file is replaced in one step, so the poll that runs concurrently
  never hashes a half-written configuration.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from mcuhome.ui.app import AppState, create_app
from mcuhome.ui.commands import MAX_CONFIG_BYTES
from mcuhome.ui.config import Config
from mcuhome.ui.security import TrustMode
from tests.python.conftest import VALID_CONFIG, call

#: A trivially different valid configuration for the same device.
EDITED_CONFIG = VALID_CONFIG.replace("sampling: 10s", "sampling: 30s")


async def test_a_save_writes_the_file_and_answers_with_the_new_hash(client, tree: Path) -> None:
    async with client.ws_connect("/ws") as ws:
        opened = (await call(ws, "device/get", {"name": "bench-node"}))["payload"]
        frame = await call(
            ws,
            "device/save",
            {
                "name": "bench-node",
                "content": EDITED_CONFIG,
                "expected_hash": opened["device"]["content_hash"],
            },
            frame_id="2",
        )

    payload = frame["payload"]
    assert (tree / "devices" / "bench-node" / "main.yaml").read_text("utf-8") == EDITED_CONFIG
    assert payload["content_hash"] != opened["device"]["content_hash"]
    # The hash the client must present on its *next* save.
    assert payload["content_hash"] == payload["device"]["content_hash"]


async def test_what_was_saved_is_what_device_get_returns_next(client) -> None:
    async with client.ws_connect("/ws") as ws:
        await call(ws, "device/save", {"name": "bench-node", "content": EDITED_CONFIG})
        reopened = (await call(ws, "device/get", {"name": "bench-node"}, frame_id="2"))["payload"]

    assert reopened["content"] == EDITED_CONFIG


async def test_a_stale_hash_is_refused_rather_than_overwriting(client, tree: Path) -> None:
    entry = tree / "devices" / "bench-node" / "main.yaml"
    async with client.ws_connect("/ws") as ws:
        opened = (await call(ws, "device/get", {"name": "bench-node"}))["payload"]
        stale = opened["device"]["content_hash"]

        # Somebody else — Studio Code Server, a checkout — writes first.
        entry.write_text(VALID_CONFIG.replace("sampling: 10s", "sampling: 5s"), encoding="utf-8")

        frame = await call(
            ws,
            "device/save",
            {"name": "bench-node", "content": EDITED_CONFIG, "expected_hash": stale},
            frame_id="2",
        )

    assert frame["type"] == "error"
    assert frame["error"]["code"] == "conflict"
    assert "changed on disk" in frame["error"]["message"]
    # The other writer's work is still there.
    assert "sampling: 5s" in entry.read_text("utf-8")


async def test_saving_without_a_hash_overwrites_on_purpose(client, tree: Path) -> None:
    entry = tree / "devices" / "bench-node" / "main.yaml"
    async with client.ws_connect("/ws") as ws:
        await call(ws, "device/get", {"name": "bench-node"})
        entry.write_text(VALID_CONFIG.replace("sampling: 10s", "sampling: 5s"), encoding="utf-8")
        frame = await call(
            ws,
            "device/save",
            {"name": "bench-node", "content": EDITED_CONFIG},
            frame_id="2",
        )

    assert frame["type"] == "result"
    assert entry.read_text("utf-8") == EDITED_CONFIG


async def test_a_save_produces_a_device_changed_event(client) -> None:
    # The subscriber that learns about the write is every *other* tab,
    # which is the whole reason snapshot-then-events exists. The event
    # and the result travel on separate tasks, so their order on the
    # wire is not part of the contract — only their arrival is.
    async with client.ws_connect("/ws") as ws:
        await call(ws, "config/subscribe")
        await ws.send_json(
            {
                "id": "2",
                "type": "device/save",
                "payload": {"name": "bench-node", "content": EDITED_CONFIG},
            }
        )
        names: list[str] = []
        answered = False
        while not (answered and names):
            frame = await ws.receive_json(timeout=5)
            if frame.get("type") == "event" and frame["event"] == "device_changed":
                names.append(frame["payload"]["device"]["name"])
            if frame.get("id") == "2":
                assert frame["type"] == "result"
                answered = True

    assert names == ["bench-node"]


async def test_a_broken_configuration_saves_and_then_reports_its_errors(client) -> None:
    # Saving must not require validity: half-finished YAML is the normal
    # state of an open editor, and the diagnostics are what the user
    # opened the editor to see.
    broken = "device:\n  name: bench-node\n   board: nope\n"
    async with client.ws_connect("/ws") as ws:
        saved = await call(ws, "device/save", {"name": "bench-node", "content": broken})
        validated = (await call(ws, "device/validate", {"name": "bench-node"}, frame_id="2"))[
            "payload"
        ]

    assert saved["type"] == "result"
    assert validated["ok"] is False
    assert validated["errors"][0]["line"] is not None


async def test_a_missing_trailing_newline_is_added(client, tree: Path) -> None:
    async with client.ws_connect("/ws") as ws:
        await call(ws, "device/save", {"name": "bench-node", "content": EDITED_CONFIG.rstrip("\n")})

    assert (tree / "devices" / "bench-node" / "main.yaml").read_text("utf-8") == EDITED_CONFIG


async def test_an_empty_configuration_is_a_legitimate_edit(client, tree: Path) -> None:
    async with client.ws_connect("/ws") as ws:
        frame = await call(ws, "device/save", {"name": "bench-node", "content": ""})

    assert frame["type"] == "result"
    assert (tree / "devices" / "bench-node" / "main.yaml").read_text("utf-8") == ""


async def test_no_temporary_file_survives_a_save(client, tree: Path) -> None:
    async with client.ws_connect("/ws") as ws:
        await call(ws, "device/save", {"name": "bench-node", "content": EDITED_CONFIG})

    folder = tree / "devices" / "bench-node"
    assert sorted(path.name for path in folder.iterdir()) == ["main.yaml"]


@pytest.mark.parametrize(
    ("payload", "code"),
    [
        ({"content": EDITED_CONFIG}, "bad_request"),
        ({"name": "bench-node"}, "bad_request"),
        ({"name": "bench-node", "content": 42}, "bad_request"),
        ({"name": "bench-node", "content": EDITED_CONFIG, "expected_hash": 7}, "bad_request"),
        ({"name": "nope", "content": EDITED_CONFIG}, "not_found"),
    ],
)
async def test_a_malformed_save_is_refused(client, payload: dict, code: str) -> None:
    async with client.ws_connect("/ws") as ws:
        frame = await call(ws, "device/save", payload)

    assert frame["error"]["code"] == code


async def test_an_absurdly_large_configuration_is_refused(client) -> None:
    async with client.ws_connect("/ws") as ws:
        frame = await call(
            ws,
            "device/save",
            {"name": "bench-node", "content": "#" * (MAX_CONFIG_BYTES + 1)},
        )

    assert frame["error"]["code"] == "bad_request"
    assert "larger than" in frame["error"]["message"]


async def test_creating_a_device_is_not_this_command_s_job(client) -> None:
    # TODO(new-device): `mcuhome new <device>` is what creates one. Until
    # the dashboard has a command for it, save is edit-only and says so.
    async with client.ws_connect("/ws") as ws:
        frame = await call(ws, "device/save", {"name": "brand-new", "content": VALID_CONFIG})

    assert frame["error"]["code"] == "not_found"


async def test_without_a_tree_save_says_so_instead_of_writing_somewhere(
    aiohttp_client, tmp_path: Path
) -> None:
    state = AppState(Config(config_root=tmp_path / "nothing-here", poll_interval=0.0))
    await state.start()
    try:
        client = await aiohttp_client(create_app(state, TrustMode.PUBLIC))
        async with client.ws_connect("/ws") as ws:
            frame = await call(ws, "device/save", {"name": "anything", "content": VALID_CONFIG})
    finally:
        await state.stop()

    assert frame["error"]["code"] == "unavailable"
    assert not (tmp_path / "nothing-here").exists()
