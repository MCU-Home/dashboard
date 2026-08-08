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
import os
import time
from collections.abc import Awaitable, Callable
from pathlib import Path
from typing import TYPE_CHECKING, Any

from mcuhome.errors import MCUHomeError

from mcuhome_dashboard import builder, versions
from mcuhome_dashboard.buildclient import BuildServerError, NotConfiguredError
from mcuhome_dashboard.events import TOPIC_BUILDS, TOPIC_DEVICES
from mcuhome_dashboard.protocol import (
    ERROR_CONFLICT,
    ERROR_NOT_FOUND,
    ERROR_UNAVAILABLE,
    Command,
    ProtocolError,
)
from mcuhome_dashboard.security import Identity, trust_mode_of
from mcuhome_dashboard.signing import manifest_is_signed
from mcuhome_dashboard.web import base_path

if TYPE_CHECKING:  # pragma: no cover - typing only
    from aiohttp import web

    from mcuhome_dashboard.app import AppState
    from mcuhome_dashboard.ws import Connection

__all__ = ["COMMANDS", "CommandContext", "KNOWN_TOPICS", "handler_for"]

logger = logging.getLogger(__name__)

#: Topics a client may subscribe to. Named explicitly so a typo is a
#: refusal instead of a subscription that never fires.
KNOWN_TOPICS = frozenset({TOPIC_DEVICES, TOPIC_BUILDS})

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
        # ADR 0003 decision 2: the dashboard never compiles, so "is there
        # a build server" is a property of the deployment that a client
        # needs before it offers a build button.
        "build_server": state.builds.describe(),
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


# --------------------------------------------------------------------------
# Builds (ADR 0003: always remote; ADR 0006: the protocol; ADR 0007: the wire)
# --------------------------------------------------------------------------


def _build_error(exc: BuildServerError, command: Command) -> ProtocolError:
    """Carry a build server's refusal through unchanged.

    The build server already phrased it for a person — a version
    mismatch names both numbers, a missing builder feature names the
    feature. Re-wording it here would only make it vaguer.
    """
    return ProtocolError(exc.message, code=exc.code, frame_id=command.id, **exc.detail)


def _resolve_for_build(root, entry) -> dict[str, Any]:
    """Stages 1-3, off the event loop, returning the wire payload.

    ADR 0007 decision 1: the resolved model is what crosses, and it is
    resolved *here*. The build server gets no schema, no ``secrets.yaml``
    and no file names — only the one device.
    """
    tree = builder.open_config_tree(root)
    model = builder.load_model(entry, tree=tree)
    return model.to_dict()


async def build_submit(context: CommandContext, command: Command) -> dict[str, Any]:
    """``build/submit`` — compile one device on the build server.

    Payload: ``{"name", "options"?}`` where ``options`` may carry
    ``native``, ``image``, ``snippets`` and ``jobs``. Result:
    ``{"name", "job_id", "job", "created_signing_key"}``.

    What happens, in order, and why each step is on the side it is on:

    1. The configuration is loaded, validated and resolved **here**
       (ADR 0011 decision 1), so a broken configuration is a refusal in
       a second with a line number, not a failed compile in ten minutes.
    2. ``/capabilities`` is checked (ADR 0006 decision 4) before
       anything is sent.
    3. The resolved model and the signing **public** key go over the
       wire; the private key does not (ADR 0007 decision 3).
    4. The job's log is followed from byte 0, so the browser sees output
       from the first line.

    A configuration that does not resolve is a **successful** command
    whose result says ``ok: false`` and carries the diagnostics — the
    same contract ``device/validate`` follows.
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
    if not context.state.builds.configured:
        raise ProtocolError(
            NotConfiguredError().message, code=ERROR_UNAVAILABLE, frame_id=command.id
        )

    try:
        model = await asyncio.to_thread(_resolve_for_build, root, entry)
    except MCUHomeError as exc:
        return {
            "name": name,
            "ok": False,
            "errors": builder.errors_from_exception(exc, root=root),
            "job_id": None,
        }

    context.connection.subscribe(TOPIC_BUILDS)
    try:
        result = await context.state.builds.submit(
            model=model, options=command.optional_dict("options")
        )
    except BuildServerError as exc:
        raise _build_error(exc, command) from exc
    return {"name": name, "ok": True, "errors": [], **result}


async def build_cancel(context: CommandContext, command: Command) -> dict[str, Any]:
    """``build/cancel`` — stop one build. Payload: ``{"job_id"}``."""
    job_id = command.require_str("job_id")
    try:
        return await context.state.builds.cancel(job_id)
    except BuildServerError as exc:
        raise _build_error(exc, command) from exc


async def build_status(context: CommandContext, command: Command) -> dict[str, Any]:
    """``build/status`` — the build server's queue, and what this side is.

    Payload: ``{"limit"?}``. Result: ``{"server": {...}, "queue": {...}}``
    — the client's own view of the build side (configured, connected,
    where the key is, and the trust statement of ADR 0007 decision 2)
    plus the build server's ``queue_status``.

    Subscribes this socket to build events, because a client asking what
    the queue is doing wants to keep knowing.
    """
    context.connection.subscribe(TOPIC_BUILDS)
    describe = context.state.builds.describe()
    if not context.state.builds.configured:
        return {"server": describe, "queue": None, "message": NotConfiguredError().message}
    limit = command.payload.get("limit")
    try:
        capabilities = await context.state.builds.capabilities()
        queue = await context.state.builds.status(
            limit=limit if isinstance(limit, int) and 0 < limit <= 500 else 50
        )
    except BuildServerError as exc:
        # Not an error frame: "the build server is down" is an answer to
        # "what is the build server doing", and a UI that showed a red
        # command failure instead of a red server badge would be lying
        # about which thing is broken.
        return {"server": {**describe, "error": exc.message, "code": exc.code}, "queue": None}
    return {"server": {**describe, "capabilities": capabilities}, "queue": queue}


async def build_log(context: CommandContext, command: Command) -> dict[str, Any]:
    """``build/log`` — history-then-live output for one job.

    Payload: ``{"job_id", "offset"?}``. Result: the build server's
    ``follow_job`` answer, passed through unchanged. Afterwards
    ``build_job_output`` events for that job arrive on this socket.

    A client that sees the offsets of those events skip calls this again
    with its own last offset. That is the whole gap-repair mechanism
    (ADR 0006 decision 6), and it is why the offsets are on the wire.
    """
    job_id = command.require_str("job_id")
    offset = command.payload.get("offset", 0)
    if isinstance(offset, bool) or not isinstance(offset, int) or offset < 0:
        raise ProtocolError(
            '"offset" is a byte position and must be a whole number.', frame_id=command.id
        )
    context.connection.subscribe(TOPIC_BUILDS)
    try:
        return await context.state.builds.follow(job_id, offset=offset)
    except BuildServerError as exc:
        raise _build_error(exc, command) from exc


async def build_artifacts(context: CommandContext, command: Command) -> dict[str, Any]:
    """``build/artifacts`` — what a finished build left on this dashboard.

    Payload: ``{"job_id", "fetch"?}``. Result: the local artifact set,
    each file with its size, its hash and the URL that serves it.

    Fetching normally happens by itself: the client watches for the
    job to succeed and immediately downloads, verifies and signs
    (ADR 0007 decision 3), because an unsigned image is not a product.
    ``fetch: true`` re-runs that for a job whose download failed.
    """
    job_id = command.require_str("job_id")
    if not context.state.builds.configured:
        raise ProtocolError(
            NotConfiguredError().message, code=ERROR_UNAVAILABLE, frame_id=command.id
        )
    if command.payload.get("fetch"):
        try:
            local = await context.state.builds.fetch_artifacts(job_id)
        except BuildServerError as exc:
            raise _build_error(exc, command) from exc
        files = local.files
        signed = local.signed
    else:
        directory = context.state.builds.local_directory(job_id)
        if not directory.is_dir():
            raise ProtocolError(
                f'No artifacts of build "{job_id}" are on this dashboard yet.',
                code=ERROR_NOT_FOUND,
                frame_id=command.id,
            )
        files = tuple(
            {
                "path": str(path.relative_to(directory)),
                "size": path.stat().st_size,
            }
            for path in sorted(directory.rglob("*"))
            if path.is_file()
        )
        signed = await asyncio.to_thread(manifest_is_signed, directory)

    prefix = base_path(context.request)
    return {
        "job_id": job_id,
        "signed": signed,
        "files": [
            {**item, "url": f"{prefix}/api/builds/{job_id}/artifacts/{item['path']}"}
            for item in files
        ],
    }


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
    "build/submit": build_submit,
    "build/cancel": build_cancel,
    "build/status": build_status,
    "build/log": build_log,
    "build/artifacts": build_artifacts,
    "config/subscribe": config_subscribe,
    "subscribe_events": subscribe_events,
    "unsubscribe_events": unsubscribe_events,
}


def handler_for(name: str) -> Handler | None:
    return COMMANDS.get(name)
