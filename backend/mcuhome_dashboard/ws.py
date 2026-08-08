# SPDX-FileCopyrightText: 2026 The MCUHome Contributors
# SPDX-License-Identifier: Apache-2.0
"""The ``/ws`` endpoint: one connection, the whole UI (ADR 0004).

Shape of a connection:

* one **reader**, the handler coroutine itself, decoding client frames;
* one **writer** task, the only thing that ever touches ``send_str``;
* one **event pump** task, moving bus events into the writer's queue;
* one task per in-flight command.

The single writer is not ceremony. Commands run concurrently — a
``device/validate`` that takes half a second must not delay a ``ping``
behind it — and concurrent tasks writing to one WebSocket interleave
frames. Funnelling every outbound frame through one queue makes that
impossible by construction and gives the connection one place to apply
backpressure.

ADR 0004's consequence — "reconnect-and-resubscribe is the single
failure mode worth designing carefully" — shows up here as two things:
subscriptions are per connection and die with it (so a reconnect
re-subscribes and gets a fresh snapshot), and a client that falls too
far behind is told so with an ``events_dropped`` event rather than
quietly shown stale data.
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
from typing import Any

from aiohttp import WSMsgType, web

from mcuhome_dashboard import commands as command_module
from mcuhome_dashboard import protocol
from mcuhome_dashboard.events import Event, EventBus, Subscription
from mcuhome_dashboard.protocol import Command, ProtocolError
from mcuhome_dashboard.security import STATE_KEY, check_origin, identity_of

__all__ = ["Connection", "websocket_handler"]

logger = logging.getLogger(__name__)

#: Outbound frames a connection may buffer before the producers block.
OUTBOX_LIMIT = 512

#: Emitted when the bus had to drop events for this connection. The
#: client's cue to re-subscribe rather than trust what it holds.
EVENT_DROPPED = "events_dropped"


class Connection:
    """One WebSocket client: its subscription and its outbox."""

    def __init__(self, ws: web.WebSocketResponse, bus: EventBus) -> None:
        self._ws = ws
        self._subscription: Subscription = bus.subscribe()
        self._outbox: asyncio.Queue[dict[str, Any]] = asyncio.Queue(maxsize=OUTBOX_LIMIT)
        self._tasks: set[asyncio.Task[None]] = set()
        self._closing = False

    @property
    def topics(self) -> frozenset[str]:
        return self._subscription.topics

    def subscribe(self, *topics: str) -> None:
        self._subscription.add(*topics)

    def unsubscribe(self, *topics: str) -> None:
        self._subscription.remove(*topics)

    async def send(self, frame: dict[str, Any]) -> None:
        if not self._closing:
            await self._outbox.put(frame)

    # -- the three long-lived coroutines -----------------------------

    async def write_loop(self) -> None:
        while True:
            frame = await self._outbox.get()
            try:
                await self._ws.send_str(protocol.encode(frame))
            except (ConnectionResetError, RuntimeError):
                return

    async def event_loop(self) -> None:
        dropped = 0
        while True:
            event: Event = await self._subscription.get()
            if self._subscription.dropped != dropped:
                lost = self._subscription.dropped - dropped
                dropped = self._subscription.dropped
                await self.send(
                    protocol.event_frame(EVENT_DROPPED, {"count": lost, "total": dropped})
                )
            await self.send(protocol.event_frame(event.name, event.payload))

    def spawn(self, coro) -> None:
        """Run one command concurrently, and never lose its exception."""
        task = asyncio.create_task(coro)
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)

    async def close(self) -> None:
        self._closing = True
        for task in list(self._tasks):
            task.cancel()
        for task in list(self._tasks):
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await task
        self._subscription.close()


async def _run_command(
    connection: Connection,
    context: command_module.CommandContext,
    command: Command,
) -> None:
    handler = command_module.handler_for(command.type)
    if handler is None:
        await connection.send(
            protocol.error_frame(
                command.id,
                protocol.ERROR_UNKNOWN_COMMAND,
                f'This server has no command called "{command.type}".',
                known=sorted(command_module.COMMANDS),
            )
        )
        return
    try:
        payload = await handler(context, command)
    except ProtocolError as exc:
        await connection.send(protocol.error_frame(command.id, exc.code, exc.message, **exc.detail))
    except asyncio.CancelledError:
        raise
    except Exception:
        # A bug on this side. The client gets a code it can render; the
        # traceback goes to the log, never over the wire.
        logger.exception("command %r failed", command.type)
        await connection.send(
            protocol.error_frame(
                command.id,
                protocol.ERROR_INTERNAL,
                f'The command "{command.type}" failed unexpectedly. '
                "The dashboard log has the details.",
            )
        )
    else:
        await connection.send(protocol.result_frame(command.id, payload))


async def websocket_handler(request: web.Request) -> web.StreamResponse:
    """Serve ``/ws``.

    Authentication already happened in the middleware; the origin check
    happens here because it is specific to the upgrade (ADR 0009
    decision 3) and must refuse *before* the socket exists.
    """
    state = request.app[STATE_KEY]
    if not check_origin(request, allowed=state.config.allowed_origins):
        logger.warning("refused a WebSocket upgrade from origin %r", request.headers.get("Origin"))
        raise web.HTTPForbidden(text="This origin may not open a WebSocket here.")

    ws = web.WebSocketResponse(heartbeat=30.0)
    await ws.prepare(request)

    connection = Connection(ws, state.bus)
    context = command_module.CommandContext(state, connection, request, identity_of(request))
    writer = asyncio.create_task(connection.write_loop(), name="mcuhome-ws-writer")
    pump = asyncio.create_task(connection.event_loop(), name="mcuhome-ws-events")

    try:
        async for message in ws:
            if message.type is not WSMsgType.TEXT:
                if message.type is WSMsgType.BINARY:
                    await connection.send(
                        protocol.error_frame(
                            None,
                            protocol.ERROR_BAD_REQUEST,
                            "This endpoint speaks JSON text frames only.",
                        )
                    )
                continue
            try:
                command = protocol.decode(message.data)
            except ProtocolError as exc:
                await connection.send(protocol.error_frame(exc.frame_id, exc.code, exc.message))
                continue
            connection.spawn(_run_command(connection, context, command))
    finally:
        await connection.close()
        for task in (pump, writer):
            task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await task
        with contextlib.suppress(Exception):
            await ws.close()
    return ws
