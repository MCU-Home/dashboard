# SPDX-FileCopyrightText: 2026 The MCUHome Contributors
# SPDX-License-Identifier: Apache-2.0
"""The frame vocabulary, without a socket.

ADR 0004 accepts that aiohttp brings no validation batteries and that
frames are validated by hand. That makes :func:`protocol.decode` the
whole trust boundary of the API, so it is tested as one.
"""

from __future__ import annotations

import json

import pytest

from mcuhome_dashboard import protocol
from mcuhome_dashboard.protocol import ProtocolError


def test_a_command_round_trips() -> None:
    command = protocol.decode('{"id": "7", "type": "server/info", "payload": {"a": 1}}')
    assert command.id == "7"
    assert command.type == "server/info"
    assert command.payload == {"a": 1}


def test_the_payload_is_optional() -> None:
    assert protocol.decode('{"id": 3, "type": "ping"}').payload == {}
    assert protocol.decode('{"id": 3, "type": "ping", "payload": null}').payload == {}


def test_an_id_is_optional_so_fire_and_forget_stays_possible() -> None:
    assert protocol.decode('{"type": "ping"}').id is None


@pytest.mark.parametrize(
    "raw",
    [
        "not json at all",
        "[]",
        '"a string"',
        "{}",
        '{"id": "1"}',
        '{"id": "1", "type": ""}',
        '{"id": "1", "type": 42}',
        '{"id": {"a": 1}, "type": "ping"}',
        '{"id": "1", "type": "ping", "payload": []}',
    ],
)
def test_malformed_frames_are_refused(raw: str) -> None:
    with pytest.raises(ProtocolError):
        protocol.decode(raw)


def test_a_refusal_carries_the_id_so_the_client_can_match_it() -> None:
    with pytest.raises(ProtocolError) as caught:
        protocol.decode('{"id": "9", "type": 5}')
    assert caught.value.frame_id == "9"
    assert caught.value.code == protocol.ERROR_BAD_REQUEST


def test_require_str_refuses_a_missing_field() -> None:
    command = protocol.decode('{"id": "1", "type": "device/get", "payload": {}}')
    with pytest.raises(ProtocolError):
        command.require_str("name")


def test_optional_str_list_accepts_a_bare_string() -> None:
    command = protocol.decode('{"id": "1", "type": "x", "payload": {"topics": "devices"}}')
    assert command.optional_str_list("topics") == ["devices"]
    assert command.optional_str_list("missing") == []


def test_optional_str_list_refuses_a_list_of_other_things() -> None:
    command = protocol.decode('{"id": "1", "type": "x", "payload": {"topics": [1, 2]}}')
    with pytest.raises(ProtocolError):
        command.optional_str_list("topics")


def test_the_three_outbound_frame_shapes() -> None:
    assert protocol.result_frame("1", {"a": 1}) == {
        "id": "1",
        "type": "result",
        "payload": {"a": 1},
    }
    assert protocol.error_frame("1", "not_found", "gone", name="x") == {
        "id": "1",
        "type": "error",
        "error": {"code": "not_found", "message": "gone", "name": "x"},
    }
    # An event answers nothing, so it carries no id at all.
    event = protocol.event_frame("device_changed", {"device": {}})
    assert event == {"type": "event", "event": "device_changed", "payload": {"device": {}}}
    assert "id" not in event


def test_encoding_stays_json() -> None:
    assert json.loads(protocol.encode(protocol.result_frame(1, {"ä": "ö"}))) == {
        "id": 1,
        "type": "result",
        "payload": {"ä": "ö"},
    }
