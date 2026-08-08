# SPDX-FileCopyrightText: 2026 The MCUHome Contributors
# SPDX-License-Identifier: Apache-2.0
"""The ``/ws`` frame vocabulary (ADR 0004 decision 3).

One endpoint carries two kinds of frame.

**Command / response.** The client assigns an ``id``; every answer
carries it back, so responses may arrive out of order::

    → {"id": "7", "type": "device/list", "payload": {}}
    ← {"id": "7", "type": "result", "payload": {"devices": [...]}}
    ← {"id": "7", "type": "error",  "error": {"code": "not_found", ...}}

**Events.** Pushed by the server, never in answer to anything, so they
carry no ``id``::

    ← {"type": "event", "event": "device_changed", "payload": {...}}

ADR 0004 notes that aiohttp brings no validation batteries and that
frames are validated by hand against this vocabulary. :func:`decode`
is that hand: it is the only place where untrusted text becomes a
:class:`Command`, and everything downstream may assume a well-formed
command with a dictionary payload.

The distinction worth keeping straight: an **error frame** means the
command could not be carried out. A configuration that fails to
validate is not an error frame — it is a successful ``device/validate``
whose result says ``ok: false`` and carries the diagnostics.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Any

__all__ = [
    "ERROR_BAD_REQUEST",
    "ERROR_CONFLICT",
    "ERROR_INTERNAL",
    "ERROR_NOT_FOUND",
    "ERROR_UNAUTHORIZED",
    "ERROR_UNAVAILABLE",
    "ERROR_UNKNOWN_COMMAND",
    "ERROR_UNSUPPORTED",
    "TYPE_ERROR",
    "TYPE_EVENT",
    "TYPE_RESULT",
    "Command",
    "ProtocolError",
    "decode",
    "encode",
    "error_frame",
    "event_frame",
    "result_frame",
]

TYPE_RESULT = "result"
TYPE_ERROR = "error"
TYPE_EVENT = "event"

#: The frame was not understood: malformed JSON, missing fields, wrong
#: types. Always the client's fault.
ERROR_BAD_REQUEST = "bad_request"
#: A well-formed frame naming a command this server does not have.
ERROR_UNKNOWN_COMMAND = "unknown_command"
#: The command is fine, the thing it names does not exist.
ERROR_NOT_FOUND = "not_found"
#: The session may not do this. Only reachable on the public site.
ERROR_UNAUTHORIZED = "unauthorized"
#: A precondition outside the client's control is missing — most often
#: "no configuration tree is configured yet".
ERROR_UNAVAILABLE = "unavailable"
#: The client is writing against a version of the file that is no longer
#: on disk. Distinct from ``bad_request`` because nothing about the frame
#: is wrong and the fix is a human decision — reload or overwrite — not a
#: retry.
ERROR_CONFLICT = "conflict"
#: The request is well-formed and cannot be honoured: a build server
#: whose ``model_version`` range does not include ours, or whose builder
#: is missing something every job needs. Distinct from ``bad_request``
#: because nothing about the frame is wrong, and from ``unavailable``
#: because retrying will not help — one of the two sides has to change
#: version (ADR 0006 decision 4, ADR 0007 decision 4).
ERROR_UNSUPPORTED = "unsupported"
#: A bug on this side. Carries no traceback; the log has it.
ERROR_INTERNAL = "internal_error"


class ProtocolError(Exception):
    """A frame that :func:`decode` refuses, with the code to answer with."""

    def __init__(
        self,
        message: str,
        *,
        code: str = ERROR_BAD_REQUEST,
        frame_id: Any = None,
        **detail: Any,
    ) -> None:
        super().__init__(message)
        self.message = message
        self.code = code
        self.frame_id = frame_id
        #: Extra fields for the error frame. A refusal that has numbers
        #: in it — the two `model_version`s of ADR 0006 decision 4, say —
        #: should carry them as data as well as in its sentence, so a UI
        #: can act on them without parsing English.
        self.detail = detail


@dataclass(frozen=True)
class Command:
    """One decoded client frame."""

    id: Any
    type: str
    payload: dict[str, Any] = field(default_factory=dict)

    def require_str(self, key: str) -> str:
        """Fetch a mandatory string field, or refuse the command."""
        value = self.payload.get(key)
        if not isinstance(value, str) or not value:
            raise ProtocolError(
                f'"{self.type}" needs a non-empty string "{key}" in its payload.',
                frame_id=self.id,
            )
        return value

    def require_text(self, key: str) -> str:
        """Fetch a mandatory string field that is allowed to be empty.

        The counterpart to :meth:`require_str` for document bodies: a
        name may not be empty, a file's contents may — emptying an
        editor and saving is a legitimate edit, and refusing it here
        would report it as a malformed frame.
        """
        value = self.payload.get(key)
        if not isinstance(value, str):
            raise ProtocolError(
                f'"{self.type}" needs a string "{key}" in its payload.',
                frame_id=self.id,
            )
        return value

    def optional_str(self, key: str) -> str | None:
        """Fetch an optional string field, or refuse the command."""
        value = self.payload.get(key)
        if value is None:
            return None
        if not isinstance(value, str):
            raise ProtocolError(
                f'"{self.type}" wants "{key}" as a string.',
                frame_id=self.id,
            )
        return value

    def optional_dict(self, key: str) -> dict[str, Any]:
        """Fetch an optional object field, or refuse the command.

        Missing and empty are the same thing here: a command with an
        options bag that nobody filled in is the ordinary case, not a
        malformed frame.
        """
        value = self.payload.get(key)
        if value is None:
            return {}
        if not isinstance(value, dict):
            raise ProtocolError(
                f'"{self.type}" wants "{key}" as a JSON object.',
                frame_id=self.id,
            )
        return value

    def optional_str_list(self, key: str) -> list[str]:
        """Fetch an optional list-of-strings field, or refuse the command."""
        value = self.payload.get(key, [])
        if isinstance(value, str):
            value = [value]
        if not isinstance(value, list) or any(not isinstance(item, str) for item in value):
            raise ProtocolError(
                f'"{self.type}" wants "{key}" as a list of strings.',
                frame_id=self.id,
            )
        return list(value)


def decode(raw: str) -> Command:
    """Turn one text frame into a :class:`Command`, or refuse it."""
    try:
        data = json.loads(raw)
    except (ValueError, TypeError) as exc:
        raise ProtocolError(f"This frame is not valid JSON: {exc}.") from exc

    if not isinstance(data, dict):
        raise ProtocolError("A frame must be a JSON object with id, type and payload.")

    frame_id = data.get("id")
    if frame_id is not None and not isinstance(frame_id, str | int):
        raise ProtocolError('"id" must be a string or a number.', frame_id=None)

    command_type = data.get("type")
    if not isinstance(command_type, str) or not command_type:
        raise ProtocolError('A frame needs a "type" naming the command.', frame_id=frame_id)

    payload = data.get("payload", {})
    if payload is None:
        payload = {}
    if not isinstance(payload, dict):
        raise ProtocolError('"payload" must be a JSON object.', frame_id=frame_id)

    return Command(id=frame_id, type=command_type, payload=payload)


def result_frame(frame_id: Any, payload: dict[str, Any]) -> dict[str, Any]:
    return {"id": frame_id, "type": TYPE_RESULT, "payload": payload}


def error_frame(
    frame_id: Any,
    code: str,
    message: str,
    **detail: Any,
) -> dict[str, Any]:
    error: dict[str, Any] = {"code": code, "message": message}
    if detail:
        error.update(detail)
    return {"id": frame_id, "type": TYPE_ERROR, "error": error}


def event_frame(name: str, payload: dict[str, Any]) -> dict[str, Any]:
    return {"type": TYPE_EVENT, "event": name, "payload": payload}


def encode(frame: dict[str, Any]) -> str:
    """Serialize a frame. Deterministic, so tests can compare text."""
    return json.dumps(frame, ensure_ascii=False, separators=(",", ":"))
