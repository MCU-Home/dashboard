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
socket. ADR 0004's "nothing CPU-bound left" is a statement about
*compiling*, not about parsing.

**There is no build command here, and no build path anywhere in this
package.** The job-protocol client of dashboard ADR 0006 was dismantled
when ADR 0012 decision 3 made the session protocol of firmware ADR 0019
the way a dashboard reaches a build server. Nothing has replaced it yet,
so this dashboard cannot compile, cannot flash and cannot fetch an
artifact — the honest surface is the absence of the verbs, not a
``build/submit`` that always refuses.
"""

from __future__ import annotations

import asyncio
import logging
import os
import time
from collections.abc import Awaitable, Callable
from pathlib import Path
from typing import TYPE_CHECKING, Any

from mcuhome.model.errors import MCUHomeError

from mcuhome_dashboard import builder, versions
from mcuhome_dashboard.events import TOPIC_DEVICES
from mcuhome_dashboard.protocol import (
    ERROR_CONFLICT,
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
#: refusal instead of a subscription that never fires. One topic, because
#: the ``builds`` topic went with the job protocol (ADR 0012 decision 3);
#: the session protocol's progress events will bring their own.
KNOWN_TOPICS = frozenset({TOPIC_DEVICES})

#: The largest configuration ``device/save`` accepts, in bytes of UTF-8.
#: A device configuration is a page of YAML; a megabyte of it is a
#: mistake or an attack, and either way this side has to hold it in
#: memory to write it.
MAX_CONFIG_BYTES = 1 << 20

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

    **No ``build_server`` block.** It said whether a build server was
    configured, reachable and compatible; since ADR 0012 decision 3 took
    the job protocol out, nothing here can reach one, and a field
    reporting the health of a connection this process cannot open would
    be an advertisement rather than an answer. It returns when the
    session client does, describing a session and not a queue.
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


def _write_atomically(path: Path, content: str) -> None:
    """Replace *path*'s contents in one step, or not at all.

    The device store polls this tree and hashes what it finds, and a
    text editor on a mounted share may be reading the same file. A
    truncate-then-write would show both of them a half-written
    configuration; a write to a sibling followed by :func:`os.replace`
    shows them either the old file or the new one.

    The temporary file is a sibling because ``rename`` is only atomic
    within a filesystem, and its name ends in ``.tmp`` because the
    scanner only looks at ``.yaml``/``.yml`` — so a crash between the two
    steps leaves litter, never a device.
    """
    temporary = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    try:
        temporary.write_text(content, encoding="utf-8")
        os.replace(temporary, path)
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise


async def device_save(context: CommandContext, command: Command) -> dict[str, Any]:
    """``device/save`` — write one device's configuration file.

    Payload: ``{"name", "content", "expected_hash"?}``. Result:
    ``{"name", "device": entry, "content_hash"}`` — the entry as it is
    after the write, so the editor holds the hash its next save must
    present.

    **The mirror of** ``device/get``: same device, same file, the other
    direction. It does not validate. Saving a configuration that does not
    resolve yet is the normal state of editing one, and a save that
    refused broken YAML would make the editor unusable exactly when its
    diagnostics are most useful — ``device/validate`` is the separate
    command that says whether what was saved is good.

    **Conflict detection is the snapshot-then-events pattern applied to
    writing.** ``device/get`` handed the client a ``content_hash``;
    passing it back as ``expected_hash`` says "I edited *that* version".
    If the file changed since — Studio Code Server, a git checkout,
    another browser tab — the write is refused with ``conflict`` and the
    client re-reads with ``device/get`` rather than silently discarding
    somebody's work. Omitting ``expected_hash`` is a deliberate
    force-overwrite and is how a client that has just resolved the
    conflict retries.

    TODO(new-device): this writes an existing device's entry file only.
    Creating one is ``mcuhome new``, which Block 0 implemented as a CLI
    command but not (yet) as a ``mcuhome.api`` entry point — a
    new-device command here needs one or the other. Editing a device's
    *other* YAML files needs a ``file`` field once something offers a
    way to open them.
    """
    name = command.require_str("name")
    content = command.require_text("content")
    expected_hash = command.optional_str("expected_hash")

    if len(content.encode("utf-8")) > MAX_CONFIG_BYTES:
        raise ProtocolError(
            f'The configuration for "{name}" is larger than '
            f"{MAX_CONFIG_BYTES // 1024} KiB, which no device configuration is.",
            frame_id=command.id,
        )

    _tree_required(context)
    await context.devices.refresh()
    entry = context.devices.get(name)
    path = context.devices.entry_path(name)
    if entry is None or path is None:
        raise ProtocolError(
            f'There is no device called "{name}" in this configuration tree.',
            code=ERROR_NOT_FOUND,
            frame_id=command.id,
        )
    if expected_hash is not None and expected_hash != entry.content_hash:
        raise ProtocolError(
            f'"{name}" changed on disk since it was opened. Reload it to see the '
            "current version, or save again without a hash to overwrite it.",
            code=ERROR_CONFLICT,
            frame_id=command.id,
        )

    # A file whose last line has no newline is a file every other tool in
    # the chain has to special-case. The editor does not send one.
    if content and not content.endswith("\n"):
        content += "\n"

    try:
        await asyncio.to_thread(_write_atomically, path, content)
    except OSError as exc:
        raise ProtocolError(
            f'The configuration file for "{name}" could not be written: {exc.strerror}.',
            code=ERROR_UNAVAILABLE,
            frame_id=command.id,
        ) from exc

    # Re-scan before answering, so the hash handed back is the one the
    # next save has to present and the `device_changed` event this write
    # caused has already gone out.
    await context.devices.refresh()
    saved = context.devices.get(name)
    if saved is None:  # pragma: no cover - only if the tree vanished mid-write
        raise ProtocolError(
            f'"{name}" disappeared from the configuration tree while it was being saved.',
            code=ERROR_UNAVAILABLE,
            frame_id=command.id,
        )
    return {"name": name, "device": saved.to_dict(), "content_hash": saved.content_hash}


def _commissioning_blocking(root, entry) -> dict[str, Any]:
    """Resolve the device, then take only its commissioning codes."""
    try:
        tree = builder.open_config_tree(root)
        model = builder.load_model(entry, tree=tree)
    except MCUHomeError as exc:
        return {
            "ok": False,
            "errors": builder.errors_from_exception(exc, root=root),
            "commissioning": None,
        }
    return {"ok": True, "errors": [], "commissioning": builder.commissioning_codes(model)}


async def device_commissioning(context: CommandContext, command: Command) -> dict[str, Any]:
    """``device/commissioning`` — the codes that add this device to a controller.

    Payload: ``{"name"}``. Result:
    ``{"name", "ok", "errors", "commissioning": {...} | null}``, where
    the object carries ``qr_payload``, ``manual_code``, ``discriminator``
    and ``test_credentials``. ``null`` means the device has no Matter
    pairing tuple — nothing to commission, not a failure. A configuration
    that does not resolve answers like ``device/validate`` does: a
    successful command carrying diagnostics.

    **A command of its own, and not a field of the summary, on purpose.**
    The QR payload contains the passcode. That is precisely why it is
    worth showing — a commissioning view that hides the commissioning
    code is useless — and precisely why it may not be attached to
    ``device/list`` or ``device/validate``, which every open tab receives
    without asking. Here it crosses the wire only when a user pressed a
    button, which is what ADR 0007's exposure discipline asks for and
    what ``builder.device_summary`` says it is leaving room for.

    It is the same data ``mcuhome validate`` prints on a terminal today.
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
    result = await asyncio.to_thread(_commissioning_blocking, root, entry)
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
    "device/save": device_save,
    "device/validate": device_validate,
    "device/commissioning": device_commissioning,
    "config/subscribe": config_subscribe,
    "subscribe_events": subscribe_events,
    "unsubscribe_events": unsubscribe_events,
}


def handler_for(name: str) -> Handler | None:
    return COMMANDS.get(name)
