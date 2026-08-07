# SPDX-FileCopyrightText: 2026 The MCUHome Contributors
# SPDX-License-Identifier: Apache-2.0
"""The command vocabulary of ``/ws``.

ADR 0004 records that no OpenAPI document falls out of a WebSocket API
and that the vocabulary is therefore documented by hand. This module is
that document: one function per command, each with the payload it takes
and the result it returns in its docstring, and a table at the bottom
that is the only place a name is bound to a handler.

Two rules hold across all of them.

**Snapshot-then-events.** A subscription's *result* is the current
state; every later change arrives as an event on the same socket. A
client never polls and never re-fetches a list — the race that pattern
causes is what ESPHome documented as the reason they rebuilt their
dashboard's data flow, and starting there is free.

**Builder work happens in a thread.** ``device/validate`` parses YAML
and walks a model; on the event loop that would stall every other
socket, including one streaming a build log. ADR 0004's "nothing
CPU-bound left" is a statement about *compiling*, not about parsing.
"""

from __future__ import annotations

import asyncio
import logging
import time
from collections.abc import Awaitable, Callable
from typing import TYPE_CHECKING, Any

from mcuhome.errors import MCUHomeError

from mcuhome_dashboard import builder, versions
from mcuhome_dashboard.events import TOPIC_DEVICES
from mcuhome_dashboard.protocol import (
    ERROR_NOT_FOUND,
    ERROR_UNAVAILABLE,
    Command,
    ProtocolError,
)
from mcuhome_dashboard.security import Identity, trust_mode_of
from mcuhome_dashboard.web import base_path

if TYPE_CHECKING:  # pragma: no cover - typing only
    from aiohttp import web

    from mcuhome_dashboard.app import AppState
    from mcuhome_dashboard.ws import Connection

__all__ = ["COMMANDS", "CommandContext", "KNOWN_TOPICS", "handler_for"]

logger = logging.getLogger(__name__)

#: Topics a client may subscribe to. Named explicitly so a typo is a
#: refusal instead of a subscription that never fires.
KNOWN_TOPICS = frozenset({TOPIC_DEVICES})

Handler = Callable[["CommandContext", Command], Awaitable[dict[str, Any]]]


class CommandContext:
    """Everything a handler is allowed to reach."""

    def __init__(
        self,
        state: AppState,
        connection: Connection,
        request: web.Request,
        identity: Identity | None,
    ) -> None:
        self.state = state
        self.connection = connection
        self.request = request
        self.identity = identity

    @property
    def devices(self):
        return self.state.devices


def _tree_required(context: CommandContext):
    root = context.state.devices.root
    if root is None or not context.state.devices.available:
        raise ProtocolError(
            "No MCUHome configuration tree is available."
            if root is None
            else f'"{root}" is not an MCUHome configuration tree.',
            code=ERROR_UNAVAILABLE,
        )
    return root


# --------------------------------------------------------------------------
# Commands
# --------------------------------------------------------------------------


async def server_info(context: CommandContext, command: Command) -> dict[str, Any]:
    """``server/info`` — versions and what this deployment is.

    Payload: none. Result: the dashboard version, the imported builder
    version with the range this dashboard supports (ADR 0011 decision
    2), the device-model version it speaks (ADR 0007 decision 4), the
    trust mode of the site answering, and the ingress prefix the request
    arrived with.
    """
    state = context.state
    return {
        "dashboard": {
            "name": "mcuhome-dashboard",
            "version": versions.DASHBOARD_VERSION,
            "uptime_seconds": round(time.monotonic() - state.started_at, 3),
        },
        "builder": {
            "package": "mcuhome",
            "version": builder.MCUHOME_VERSION,
            "supported": versions.MCUHOME_VERSION_SPEC,
        },
        "model_version": {
            "sends": versions.MODEL_VERSION,
            "min": versions.MODEL_VERSION_MIN,
            "max": versions.MODEL_VERSION_MAX,
        },
        "deployment": {
            "trust": trust_mode_of(context.request).value,
            "base_path": base_path(context.request),
        },
        "identity": context.identity.to_dict() if context.identity else None,
        "tree": state.devices.tree_state(),
    }


async def ping(context: CommandContext, command: Command) -> dict[str, Any]:
    """``ping`` — liveness for a client that wants to time its own link."""
    return {"pong": True, "time": time.time()}


async def device_list(context: CommandContext, command: Command) -> dict[str, Any]:
    """``device/list`` — every device in the configuration tree.

    Payload: none. Result: ``{"devices": [...], "tree": {...}}``.

    Re-scans before answering. The poll would get there within a second
    anyway; doing it here means a client that just wrote a file and
    asked does not see the state from before its own write.
    """
    await context.devices.refresh()
    return {
        "devices": [entry.to_dict() for entry in context.devices.snapshot()],
        "tree": context.devices.tree_state(),
    }


async def device_get(context: CommandContext, command: Command) -> dict[str, Any]:
    """``device/get`` — one device's raw YAML and a cheap summary.

    Payload: ``{"name": "<device>"}``. Result: the list entry, the exact
    file contents (this is what the editor opens), and an *unresolved*
    summary — no ``!secret`` is looked up to produce it.
    """
    name = command.require_str("name")
    await context.devices.refresh()
    entry = context.devices.get(name)
    path = context.devices.entry_path(name)
    if entry is None or path is None:
        raise ProtocolError(
            f'There is no device called "{name}" in this configuration tree.',
            code=ERROR_NOT_FOUND,
            frame_id=command.id,
        )
    try:
        content = await asyncio.to_thread(path.read_text, encoding="utf-8")
    except OSError as exc:
        raise ProtocolError(
            f'The configuration file for "{name}" could not be read: {exc.strerror}.',
            code=ERROR_UNAVAILABLE,
            frame_id=command.id,
        ) from exc
    return {"device": entry.to_dict(), "content": content, "summary": entry.summary}


def _validate_blocking(root, entry) -> dict[str, Any]:
    """Stages 1-3 of the builder, off the event loop."""
    try:
        tree = builder.open_config_tree(root)
        model = builder.load_model(entry, tree=tree)
    except MCUHomeError as exc:
        return {
            "ok": False,
            "errors": builder.errors_from_exception(exc, root=root),
            "device": None,
        }
    return {"ok": True, "errors": [], "device": builder.device_summary(model)}


async def device_validate(context: CommandContext, command: Command) -> dict[str, Any]:
    """``device/validate`` — run the builder's checks on one device.

    Payload: ``{"name": "<device>"}``. Result:
    ``{"ok": bool, "errors": [...], "device": summary | null}``.

    A configuration that does not validate is a **successful** command:
    the diagnostics are the answer, not a failure. Each carries file,
    line, column, dotted key and a fix hint, which is what puts a marker
    on the editor's gutter instead of a line in a log pane (ADR 0004
    decision 5).
    """
    name = command.require_str("name")
    root = _tree_required(context)
    await context.devices.refresh()
    entry = context.devices.entry_path(name)
    if entry is None:
        raise ProtocolError(
            f'There is no device called "{name}" in this configuration tree.',
            code=ERROR_NOT_FOUND,
            frame_id=command.id,
        )
    result = await asyncio.to_thread(_validate_blocking, root, entry)
    return {"name": name, **result}


async def config_subscribe(context: CommandContext, command: Command) -> dict[str, Any]:
    """``config/subscribe`` — the device list, and every change to it.

    Payload: none. Result: the same snapshot ``device/list`` returns,
    plus the topic now subscribed. Afterwards ``device_added``,
    ``device_changed``, ``device_removed`` and ``tree_state`` events
    arrive on this socket until it closes.
    """
    context.connection.subscribe(TOPIC_DEVICES)
    snapshot = await device_list(context, command)
    return {"topic": TOPIC_DEVICES, **snapshot}


def _topics(command: Command) -> list[str]:
    requested = command.optional_str_list("topics")
    unknown = [topic for topic in requested if topic not in KNOWN_TOPICS]
    if unknown:
        raise ProtocolError(
            f"Unknown event topic(s): {', '.join(sorted(unknown))}. "
            f"Known topics: {', '.join(sorted(KNOWN_TOPICS))}.",
            frame_id=command.id,
        )
    return requested


async def subscribe_events(context: CommandContext, command: Command) -> dict[str, Any]:
    """``subscribe_events`` — the generic form, without a snapshot.

    Payload: ``{"topics": ["devices"]}``. Result: the topics this socket
    is subscribed to afterwards. Prefer ``config/subscribe`` for lists —
    it hands out the snapshot the events are deltas against.
    """
    context.connection.subscribe(*_topics(command))
    return {"topics": sorted(context.connection.topics)}


async def unsubscribe_events(context: CommandContext, command: Command) -> dict[str, Any]:
    """``unsubscribe_events`` — stop receiving the given topics."""
    context.connection.unsubscribe(*_topics(command))
    return {"topics": sorted(context.connection.topics)}


#: The command table. Adding a name here is the whole registration.
COMMANDS: dict[str, Handler] = {
    "server/info": server_info,
    "ping": ping,
    "device/list": device_list,
    "device/get": device_get,
    "device/validate": device_validate,
    "config/subscribe": config_subscribe,
    "subscribe_events": subscribe_events,
    "unsubscribe_events": unsubscribe_events,
}


def handler_for(name: str) -> Handler | None:
    return COMMANDS.get(name)
