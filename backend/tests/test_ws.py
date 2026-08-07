# SPDX-FileCopyrightText: 2026 The MCUHome Contributors
# SPDX-License-Identifier: Apache-2.0
"""The ``/ws`` endpoint end to end, over aiohttp's test client."""

from __future__ import annotations

from pathlib import Path

from mcuhome_dashboard import versions
from mcuhome_dashboard.builder import MCUHOME_VERSION
from tests.conftest import VALID_CONFIG, call, write_device


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
