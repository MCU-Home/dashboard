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

**The build verbs are back** (ADR 0013). They were absent for two
releases and the absence was the honest surface then, because ADR 0012
decision 3 dismantled the job-protocol client without a replacement.
What replaced it is not a protocol client at all: it is
:func:`mcuhome.workbench.api.run_build`, the builder's own awaitable over
its three build methods, and the dashboard is a caller of the package the
same way the ``mcuhome`` command line is. Which method runs — a container
here, a build server, a west workspace — is deployment configuration and
is nothing these handlers branch on.

**Streams get an offset, lists get a snapshot.** ``config/subscribe``
hands out the state its events are deltas against; ``build/subscribe``
does the same for builds, and ``build/log`` is the equivalent for the one
thing a snapshot cannot carry — output that keeps arriving. The bus drops
the oldest events for a subscriber that fell behind, so every batch of
output names the line offset it starts at and ``build/log`` serves any
suffix from any offset. That is the mechanism the old ``build_job_output``
had, rebuilt in lines instead of bytes.
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
from mcuhome_dashboard.builds import BuildBusy, UnknownBuild
from mcuhome_dashboard.events import TOPIC_BUILDS, TOPIC_DEVICES
from mcuhome_dashboard.protocol import (
    ERROR_BAD_REQUEST,
    ERROR_CONFLICT,
    ERROR_NOT_FOUND,
    ERROR_UNAUTHORIZED,
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
#: refusal instead of a subscription that never fires. ``builds`` came
#: back with ADR 0013, over the builder package rather than over the job
#: protocol that first had it.
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


def _require_admin(context: CommandContext, command: Command) -> None:
    """Refuse a verb that ADR 0014 reserves for administrators.

    Checked *first* in each gated handler, before the device is even
    looked up, so a non-admin learns nothing about the tree beyond "this
    needs an admin". On the public site every identity is an admin by
    construction (ADR 0009's password means the operator), so this bites
    only on the ingress site — exactly where Home Assistant has non-admin
    users. The refusal is a typed ``unauthorized`` error the frontend
    already renders, not a crash.
    """
    identity = context.identity
    if identity is not None and identity.is_admin:
        return
    raise ProtocolError(
        "This needs a Home Assistant administrator. Creating or editing devices, "
        "starting builds and revealing commissioning codes are reserved for admins.",
        code=ERROR_UNAUTHORIZED,
        frame_id=command.id,
    )


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

    **The ``build`` block says what this deployment is set up to do**, and
    nothing about whether it would work. It carries the configured method
    (or ``null`` for "no preference", plus the builder's default so a
    client can render what that resolves to), every method the installed
    builder has, and whether a build-server address is configured — a
    boolean, because the URL may carry a host an operator does not
    publish and the token must never leave this process at all. It
    deliberately does **not** report reachability: this process opens no
    connection until a build asks it to, and a field describing the
    health of a link nobody has tried is an advertisement rather than an
    answer.
    """
    state = context.state
    config = state.config
    return {
        "dashboard": {
            "name": "mcuhome-dashboard",
            "version": versions.DASHBOARD_VERSION,
            "uptime_seconds": round(time.monotonic() - state.started_at, 3),
        },
        "builder": {
            # The distribution this dashboard imports, from the one place
            # that names it: an operator reading this field is on their
            # way to a `pip install`, and `supported` beside it is a
            # requirement string over the same name. `mcuhome` is the
            # command line's distribution (firmware ADR 0020 decision 2)
            # and installing it here would pull in the compiler ADR 0003
            # keeps out.
            "package": versions.MCUHOME_PACKAGE,
            "version": builder.MCUHOME_VERSION,
            "supported": versions.MCUHOME_VERSION_SPEC,
        },
        "model_version": {
            "sends": versions.MODEL_VERSION,
            "min": versions.MODEL_VERSION_MIN,
            "max": versions.MODEL_VERSION_MAX,
        },
        "build": {
            "method": config.build_method,
            "default_method": builder.DEFAULT_BUILD_METHOD,
            "methods": list(builder.BUILD_METHODS),
            "server_configured": config.build_server_configured,
            "jobs": config.build_jobs,
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
    async with context.state.builder_gate:
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

    This writes an existing device's entry file only — creating one is
    ``device/new``. Editing a device's *other* YAML files needs a
    ``file`` field once something offers a way to open them.
    """
    _require_admin(context, command)
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


async def device_boards(context: CommandContext, command: Command) -> dict[str, Any]:
    """``device/boards`` — the hardware a device can be described with.

    Payload: none. Result: the registry's boards, drivers, clusters and
    device types, each with the *planned* entries beside them.

    **The answer comes from the builder's registry and from nothing
    else.** Which boards MCUHome can build for is knowledge about
    bring-up — partitions, entropy, radio, which bus the board breaks out
    — that the firmware repository owns; a list maintained here would be
    a second opinion that goes stale the first time a board lands. This
    is the same data ``mcuhome device boards`` prints.

    Not admin-gated: it is a catalogue of what the software supports,
    with nothing about *this* deployment in it. It reads no project, so
    it also answers before one is open — which is what a form needs to
    populate itself while the tree is still being resolved.
    """
    del command
    return await asyncio.to_thread(builder.boards)


def _new_device_blocking(root: Path, name: str, board: str, friendly: str | None, outline) -> Path:
    """Create the device, on a worker thread. Raises what the builder raises."""
    tree = builder.open_config_tree(root)
    created = builder.new_device(
        name,
        board=board,
        env={},
        cwd=tree.root,
        project_dir=tree.root,
        friendly_name=friendly,
        outline=outline,
    )
    return created.entry


async def device_new(context: CommandContext, command: Command) -> dict[str, Any]:
    """``device/new`` — create a device's first configuration file.

    Payload: ``{"name", "board", "friendly_name"?, "outline"?}``.
    Result: the same shape ``device/get`` answers with —
    ``{"device", "content", "summary"}`` — so the client opens the editor
    on what it just created without a second round trip.

    *outline* is what a form collected: ``buses``, ``peripherals`` and
    ``endpoints``, each a list of objects over the names ``device/boards``
    offered. Left out, the file carries the commented example instead,
    which is what the command line writes.

    **Every refusal here is the builder's**, including the ones a form
    should have prevented: a name that cannot become a hostname, a board
    nobody has brought up, a driver that is planned rather than
    supported, a source naming a peripheral that is not in the outline.
    They arrive as ordinary diagnostics with the fix hint the command
    line prints, because the alternative — checking the same things
    again here — is the second opinion this dashboard does not keep
    (AGENTS.md). It refuses **before writing anything**, so a rejected
    form leaves no half-made device behind.

    **A device that already exists is a conflict, never an overwrite.**
    That is the builder's rule and this reports it as one, so a client
    can offer to open the existing device rather than only saying no.

    **Admin-only** (ADR 0014): it creates files in the configuration
    tree.
    """
    _require_admin(context, command)
    name = command.require_str("name")
    board = command.require_str("board")
    friendly = command.optional_str("friendly_name")
    root = _tree_required(context)

    try:
        outline = builder.outline_from(command.optional_dict("outline"))
    except builder.OutlineError as exc:
        raise ProtocolError(str(exc), frame_id=command.id) from exc

    try:
        async with context.state.builder_gate:
            await asyncio.to_thread(_new_device_blocking, root, name, board, friendly, outline)
    except MCUHomeError as exc:
        errors = builder.errors_from_exception(exc, root=root)
        raise ProtocolError(
            errors[0]["message"] if errors else str(exc),
            # "already a device" is the one refusal a client acts on
            # differently — it has somewhere to send the user.
            code=ERROR_CONFLICT if "already a device" in str(exc) else ERROR_BAD_REQUEST,
            frame_id=command.id,
            errors=errors,
        ) from exc

    await context.devices.refresh()
    created = context.devices.get(name)
    path = context.devices.entry_path(name)
    if created is None or path is None:  # pragma: no cover - only if the tree vanished
        raise ProtocolError(
            f'"{name}" was created but is not in the configuration tree.',
            code=ERROR_UNAVAILABLE,
            frame_id=command.id,
        )
    content = await asyncio.to_thread(path.read_text, encoding="utf-8")
    return {"device": created.to_dict(), "content": content, "summary": created.summary}


def _pairing_blocking(root: Path, name: str, force: bool) -> dict[str, Any]:
    """Draw the credentials, on a worker thread."""
    tree = builder.open_config_tree(root)
    entry = tree.device_entry(name)
    result = builder.init_pairing(entry, secrets_file=tree.secrets_file, force=force)
    return {"replaced": result.replaced, "secrets_file": str(result.secrets_file)}


async def device_matter_pairing(context: CommandContext, command: Command) -> dict[str, Any]:
    """``device/matter-pairing`` — draw this device's commissioning identity.

    Payload: ``{"name", "force"?}``. Result:
    ``{"name", "replaced", "secrets_file"}``.

    A device's discriminator, passcode and salt are drawn **once, ever**,
    and are then ordinary configuration: that is what makes a build
    reproducible and a device's identity stable, and it is why this is a
    command somebody gives rather than a step a build takes on the way
    past. The values go to the device's own secrets file and ``main.yaml``
    gets ``!secret`` references, so the file a project commits never
    carries them.

    ``force`` replaces credentials that are already there, and the
    builder refuses without it. **Replacing is not editing**: every
    controller that already knows this device would have to commission it
    again, which is why saying so takes a second, explicit word.

    **It answers with none of the codes.** They travel through
    ``device/commissioning`` and through nothing else — one command
    carries passcodes and it is the one built for the purpose (ADR 0007).
    A second door here would be one more place to get that wrong, for a
    round trip on an open socket.

    **Admin-only** (ADR 0014).
    """
    _require_admin(context, command)
    name = command.require_str("name")
    force = command.payload.get("force", False)
    if not isinstance(force, bool):
        raise ProtocolError(
            '"device/matter-pairing" wants "force" as true or false.', frame_id=command.id
        )
    root = _tree_required(context)
    await context.devices.refresh()
    if context.devices.get(name) is None:
        raise ProtocolError(
            f'There is no device called "{name}" in this configuration tree.',
            code=ERROR_NOT_FOUND,
            frame_id=command.id,
        )

    try:
        async with context.state.builder_gate:
            result = await asyncio.to_thread(_pairing_blocking, root, name, force)
    except MCUHomeError as exc:
        errors = builder.errors_from_exception(exc, root=root)
        raise ProtocolError(
            errors[0]["message"] if errors else str(exc),
            code=ERROR_CONFLICT,
            frame_id=command.id,
            errors=errors,
        ) from exc

    # The write changed main.yaml, so the hash every open editor holds is
    # stale. Re-scanning here is what turns that into a `device_changed`
    # event rather than into a conflict on somebody's next save.
    await context.devices.refresh()
    return {"name": name, **result}


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

    **Admin-only** (ADR 0014): the QR payload is the passcode, so on the
    ingress site only an administrator may ask for it.
    """
    _require_admin(context, command)
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
    async with context.state.builder_gate:
        result = await asyncio.to_thread(_commissioning_blocking, root, entry)
    return {"name": name, **result}


def _build_required(context: CommandContext, command: Command):
    """The build a command names, or ``not_found``."""
    build_id = command.require_str("build_id")
    try:
        return context.state.builds.get(build_id)
    except UnknownBuild:
        raise ProtocolError(
            f'This dashboard has no build called "{build_id}". Finished builds are '
            "kept for this process's lifetime only.",
            code=ERROR_NOT_FOUND,
            frame_id=command.id,
        ) from None


async def build_start(context: CommandContext, command: Command) -> dict[str, Any]:
    """``build/start`` — compile one device's firmware.

    Payload: ``{"name": "<device>", "method"?: "<method>"}``. Result:
    ``{"build": {...}}`` — the record as it is the moment it was
    accepted, which is ``queued``. Everything after that arrives on the
    ``builds`` topic, so a client subscribes *before* it starts one.

    ``method`` overrides this deployment's configured build method for
    one build and is otherwise the deployment's own (ADR 0013 decision
    1). It is not validated here: the builder owns the list of real
    method names and its refusal names all of them, so a typo is answered
    by the package that knows rather than by a copy of the list that can
    go stale in this file.

    **One build at a time, for the whole process** (ADR 0013 decision 3).
    A second start is refused with ``conflict`` carrying the build that
    holds the slot, so a UI can say *which* one and offer to cancel it
    rather than only saying no.

    A build that runs and **fails is a successful command**: the refusal
    is the record's ``state`` and its ``errors``, in the same diagnostics
    shape ``device/validate`` uses. An error frame here means the build
    could not be *started*.

    **Admin-only** (ADR 0014): starting a build runs a toolchain on this
    host and produces a passcode-bearing artifact, so on ingress only an
    administrator may.
    """
    _require_admin(context, command)
    name = command.require_str("name")
    method = command.optional_str("method")
    _tree_required(context)
    await context.devices.refresh()
    if context.devices.get(name) is None:
        raise ProtocolError(
            f'There is no device called "{name}" in this configuration tree.',
            code=ERROR_NOT_FOUND,
            frame_id=command.id,
        )
    try:
        record = await context.state.builds.begin(name, method=method)
    except BuildBusy as busy:
        holder = busy.running
        # A holder that already reads `cancelled` is the honest case the
        # slot rule creates: cancelling stops this process waiting, not
        # the container it started, and the slot is the work's until that
        # ends. Saying "is already running" there would describe a record
        # the same answer carries, in the state `cancelled`.
        refusal = (
            f'The build of "{holder.device}" was cancelled, but the work it started '
            "has not ended yet — a container cannot be interrupted. This dashboard "
            "builds one device at a time; the next build can start when it has."
            if holder.finished_state
            else f'A build of "{holder.device}" is already running. This dashboard '
            "builds one device at a time; wait for it or cancel it."
        )
        raise ProtocolError(
            refusal,
            code=ERROR_CONFLICT,
            frame_id=command.id,
            build=holder.to_dict(),
        ) from None
    except MCUHomeError as exc:
        # An unusable method name. The builder's hint lists the real ones.
        raise ProtocolError(
            str(exc),
            frame_id=command.id,
            errors=builder.errors_from_exception(exc),
        ) from None
    return {"build": record.to_dict()}


async def build_status(context: CommandContext, command: Command) -> dict[str, Any]:
    """``build/status`` — one build, or every build this process knows.

    Payload: ``{"build_id"?}``. With one, the result is
    ``{"build": {...}}``; without, ``{"builds": [...], "running": id |
    null}``, oldest first.

    Records live in memory and die with the process. A finished build's
    *artifacts* are on disk and survive, but the record that says which
    build wrote them does not — so after a restart the files are there
    and unattributed, and this command says so by not listing them rather
    than by inventing a record for bytes it did not write.
    """
    builds = context.state.builds
    if command.optional_str("build_id") is not None:
        return {"build": _build_required(context, command).to_dict()}
    running = builds.running
    return {
        "builds": [record.to_dict() for record in builds.snapshot()],
        "running": running.id if running else None,
    }


async def build_log(context: CommandContext, command: Command) -> dict[str, Any]:
    """``build/log`` — build output from an offset, for filling a hole.

    Payload: ``{"build_id", "from_offset"?}``. Result:
    ``{"build_id", "offset", "lines", "next_offset", "truncated"}``.

    **This is the resumable half of the stream** (ADR 0013 decision 4).
    Output arrives as ``build_output`` events carrying the line offset
    each batch starts at; the bus drops the oldest events for a
    subscriber that falls behind, and a build log is the traffic that
    makes one fall behind. A client that sees ``events_dropped``, or a
    batch that does not start where the last one ended, asks here for
    everything from the last offset it holds.

    ``offset`` is where the answer actually starts, which is not always
    ``from_offset``: the retained log is bounded, and a request for
    something already discarded comes back from the oldest line still
    held with ``truncated: true``. Saying so is the point — a log that
    quietly began in the middle would be indistinguishable from a build
    that printed less than it did.
    """
    record = _build_required(context, command)
    requested = command.optional_int("from_offset") or 0
    offset, lines, truncated = record.log.since(requested)
    return {
        "build_id": record.id,
        "offset": offset,
        "lines": lines,
        "next_offset": record.log.next_offset,
        "first_offset": record.log.first_offset,
        "truncated": truncated,
        "state": record.state,
    }


async def build_cancel(context: CommandContext, command: Command) -> dict[str, Any]:
    """``build/cancel`` — stop the dashboard's part of a running build.

    Payload: ``{"build_id"}``. Result: ``{"build": {...}}``.

    What "stop" means is said rather than implied: the work runs to its
    own end. ``local`` and ``local-dev`` are blocked in a worker thread
    that Python cannot interrupt, so the container or the compiler cannot
    be told anything at all. The record ends ``cancelled`` immediately,
    nothing is collected and nothing is signed — a cancelled build never
    leaves a flashable image behind — and its build directory is removed
    once nothing is writing to it.

    **The one build slot stays taken until the work really ends**, so a
    ``build/start`` in that window is refused with ``conflict`` naming a
    record that already reads ``cancelled``. Freeing the slot when the
    *waiting* ended would let a second Zephyr build start on a machine
    the first one is still compiling on, which is the situation ADR 0013
    decision 3 exists to prevent.

    Cancelling a build that already finished is not an error; it answers
    with the record unchanged, because the caller's intent — "this build
    should not be running" — is already true.

    **Admin-only** (ADR 0014): the counterpart of ``build/start`` is
    reserved for administrators for the same reason it is.
    """
    _require_admin(context, command)
    record = await context.state.builds.cancel(_build_required(context, command).id)
    return {"build": record.to_dict()}


async def build_subscribe(context: CommandContext, command: Command) -> dict[str, Any]:
    """``build/subscribe`` — every build, and every change to them.

    Payload: none. Result: the ``build/status`` snapshot plus the topic
    now subscribed. Afterwards ``build_started``, ``build_changed`` and
    ``build_output`` arrive on this socket until it closes.

    The build counterpart of ``config/subscribe``, and for the same
    reason: a client that subscribed without a snapshot would hold
    deltas against a state it never received.
    """
    context.connection.subscribe(TOPIC_BUILDS)
    snapshot = await build_status(context, command)
    return {"topic": TOPIC_BUILDS, **snapshot}


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
    "device/new": device_new,
    "device/boards": device_boards,
    "device/matter-pairing": device_matter_pairing,
    "device/save": device_save,
    "device/validate": device_validate,
    "device/commissioning": device_commissioning,
    "build/start": build_start,
    "build/status": build_status,
    "build/log": build_log,
    "build/cancel": build_cancel,
    "build/subscribe": build_subscribe,
    "config/subscribe": config_subscribe,
    "subscribe_events": subscribe_events,
    "unsubscribe_events": unsubscribe_events,
}


def handler_for(name: str) -> Handler | None:
    return COMMANDS.get(name)
