# SPDX-FileCopyrightText: 2026 The MCUHome Contributors
# SPDX-License-Identifier: Apache-2.0
"""The ``/ws`` endpoint end to end, over aiohttp's test client."""

from __future__ import annotations

import asyncio
import threading
from pathlib import Path

from mcuhome.ui import commands as commands_module
from mcuhome.ui import versions
from mcuhome.ui.app import AppState
from mcuhome.ui.builder import MCUHOME_VERSION
from mcuhome.ui.ws import MAX_INFLIGHT_COMMANDS
from tests.python.conftest import VALID_CONFIG, call, write_device


async def test_server_info_answers_with_the_id_it_was_asked_with(client) -> None:
    async with client.ws_connect("/ws") as ws:
        frame = await call(ws, "server/info", frame_id="abc-42")

    assert frame["id"] == "abc-42"
    assert frame["type"] == "result"
    payload = frame["payload"]
    assert payload["dashboard"]["version"] == versions.DASHBOARD_VERSION
    assert payload["builder"]["version"] == MCUHOME_VERSION
    assert payload["builder"]["supported"] == versions.MCUHOME_VERSION_SPEC
    assert payload["model_version"] == {
        "sends": versions.MODEL_VERSION,
        "min": versions.MODEL_VERSION_MIN,
        "max": versions.MODEL_VERSION_MAX,
    }
    assert payload["deployment"]["trust"] == "public"
    assert payload["deployment"]["base_path"] == ""


async def test_responses_carry_their_own_id_when_commands_overlap(client) -> None:
    async with client.ws_connect("/ws") as ws:
        await ws.send_json(
            {"id": "slow", "type": "device/validate", "payload": {"name": "bench-node"}}
        )
        await ws.send_json({"id": "fast", "type": "ping", "payload": {}})

        seen = {}
        while len(seen) < 2:
            frame = await ws.receive_json(timeout=10)
            seen[frame["id"]] = frame

    assert seen["fast"]["payload"]["pong"] is True
    assert seen["slow"]["payload"]["ok"] is True


async def test_an_unknown_command_is_an_error_frame_not_a_dropped_connection(client) -> None:
    async with client.ws_connect("/ws") as ws:
        frame = await call(ws, "device/teleport")
        assert frame["type"] == "error"
        assert frame["error"]["code"] == "unknown_command"
        assert "server/info" in frame["error"]["known"]

        # The connection survives it.
        assert (await call(ws, "ping", frame_id="2"))["payload"]["pong"] is True


async def test_a_malformed_frame_is_refused_by_code(client) -> None:
    async with client.ws_connect("/ws") as ws:
        await ws.send_str("{this is not json")
        frame = await ws.receive_json(timeout=5)

    assert frame["type"] == "error"
    assert frame["error"]["code"] == "bad_request"


async def test_binary_frames_are_refused(client) -> None:
    async with client.ws_connect("/ws") as ws:
        await ws.send_bytes(b"\x00\x01")
        frame = await ws.receive_json(timeout=5)

    assert frame["error"]["code"] == "bad_request"


async def test_device_list_finds_the_fixture_tree(client) -> None:
    async with client.ws_connect("/ws") as ws:
        payload = (await call(ws, "device/list"))["payload"]

    assert [device["name"] for device in payload["devices"]] == ["bench-node", "broken-node"]
    assert payload["tree"]["available"] is True
    assert payload["tree"]["device_count"] == 2

    bench = payload["devices"][0]
    assert bench["entry"] == "devices/bench-node/main.yaml"
    assert len(bench["content_hash"]) == 64
    assert bench["summary"]["board"] == "nrf7002dk/nrf5340/cpuapp"


async def test_device_get_returns_the_file_as_it_is_on_disk(client, tree: Path) -> None:
    async with client.ws_connect("/ws") as ws:
        payload = (await call(ws, "device/get", {"name": "bench-node"}))["payload"]

    assert payload["content"] == (tree / "devices" / "bench-node" / "main.yaml").read_text()
    assert payload["summary"]["name"] == "bench-node"
    assert payload["summary"]["endpoint_count"] == 1


async def test_device_get_refuses_an_unknown_name(client) -> None:
    async with client.ws_connect("/ws") as ws:
        frame = await call(ws, "device/get", {"name": "nope"})

    assert frame["type"] == "error"
    assert frame["error"]["code"] == "not_found"
    assert "nope" in frame["error"]["message"]


async def test_device_get_without_a_name_is_a_bad_request(client) -> None:
    async with client.ws_connect("/ws") as ws:
        frame = await call(ws, "device/get", {})

    assert frame["error"]["code"] == "bad_request"


async def test_config_subscribe_answers_with_a_snapshot_then_streams_changes(
    client, tree: Path
) -> None:
    async with client.ws_connect("/ws") as ws:
        snapshot = (await call(ws, "config/subscribe"))["payload"]
        assert snapshot["topic"] == "devices"
        assert len(snapshot["devices"]) == 2

        write_device(tree, "third-node", VALID_CONFIG.replace("bench-node", "third-node"))
        await call(ws, "device/list", frame_id="rescan")

        events = []
        while not events:
            frame = await ws.receive_json(timeout=5)
            if frame.get("type") == "event":
                events.append(frame)

    assert events[0]["event"] == "device_added"
    assert events[0]["payload"]["device"]["name"] == "third-node"


async def test_events_only_reach_a_socket_that_asked_for_them(client, tree: Path) -> None:
    async with client.ws_connect("/ws") as ws:
        await call(ws, "device/list")
        write_device(tree, "third-node", VALID_CONFIG.replace("bench-node", "third-node"))
        await call(ws, "device/list", frame_id="rescan")
        # The next frame is the answer to a ping, not a device event:
        # nothing subscribed this connection to anything.
        assert (await call(ws, "ping", frame_id="ping"))["payload"]["pong"] is True


async def test_subscribe_events_refuses_an_unknown_topic(client) -> None:
    async with client.ws_connect("/ws") as ws:
        frame = await call(ws, "subscribe_events", {"topics": ["devices", "weather"]})

    assert frame["error"]["code"] == "bad_request"
    assert "weather" in frame["error"]["message"]


async def test_subscriptions_can_be_dropped_again(client) -> None:
    async with client.ws_connect("/ws") as ws:
        assert (await call(ws, "subscribe_events", {"topics": ["devices"]}))["payload"] == {
            "topics": ["devices"]
        }
        frame = await call(ws, "unsubscribe_events", {"topics": ["devices"]}, frame_id="2")

    assert frame["payload"] == {"topics": []}


# --------------------------------------------------------------------------
# Concurrency bounds (the CPU-stall finding)
# --------------------------------------------------------------------------


async def test_a_connection_caps_its_in_flight_commands(client, monkeypatch) -> None:
    """One socket cannot pile up unbounded concurrent work.

    A flood of slow commands is held at :data:`MAX_INFLIGHT_COMMANDS` — the
    reader waits for a slot before it reads the next frame, so the extra
    frames sit in the socket buffer and never become a running handler.
    """
    counters = {"running": 0, "peak": 0}
    release = asyncio.Event()

    async def blocker(context, command):
        counters["running"] += 1
        counters["peak"] = max(counters["peak"], counters["running"])
        try:
            await release.wait()
        finally:
            counters["running"] -= 1
        return {"ok": True}

    monkeypatch.setitem(commands_module.COMMANDS, "test/block", blocker)
    total = MAX_INFLIGHT_COMMANDS + 4

    async with client.ws_connect("/ws") as ws:
        for index in range(total):
            await ws.send_json({"id": str(index), "type": "test/block", "payload": {}})

        for _ in range(200):
            await asyncio.sleep(0.01)
            if counters["running"] >= MAX_INFLIGHT_COMMANDS:
                break
        # A moment more, so an over-admission would have shown up.
        await asyncio.sleep(0.05)
        assert counters["running"] == MAX_INFLIGHT_COMMANDS
        assert counters["peak"] == MAX_INFLIGHT_COMMANDS

        release.set()
        answered = 0
        while answered < total:
            frame = await ws.receive_json(timeout=5)
            if frame.get("type") == "result":
                answered += 1

    assert answered == total


async def test_cpu_bound_builder_work_is_globally_bounded(
    client, state: AppState, monkeypatch
) -> None:
    """``device/validate`` cannot exhaust the shared thread pool.

    The builder gate caps how many validations run at once across every
    connection, so the pool the rest of the app's file I/O shares stays
    free. Sized to 2 here to make the bound observable.
    """
    state.builder_gate = asyncio.Semaphore(2)
    lock = threading.Lock()
    counters = {"running": 0, "peak": 0}
    release = threading.Event()

    def blocking(root, entry):
        with lock:
            counters["running"] += 1
            counters["peak"] = max(counters["peak"], counters["running"])
        release.wait(5)
        with lock:
            counters["running"] -= 1
        return {"ok": True, "errors": [], "device": None}

    monkeypatch.setattr(commands_module, "_validate_blocking", blocking)

    async with client.ws_connect("/ws") as ws:
        for index in range(4):
            await ws.send_json(
                {"id": str(index), "type": "device/validate", "payload": {"name": "bench-node"}}
            )

        for _ in range(300):
            await asyncio.sleep(0.01)
            with lock:
                if counters["running"] >= 2:
                    break
        await asyncio.sleep(0.05)
        with lock:
            assert counters["running"] == 2
            assert counters["peak"] == 2

        release.set()
        answered = 0
        while answered < 4:
            frame = await ws.receive_json(timeout=5)
            if frame.get("type") == "result":
                answered += 1

    assert answered == 4
