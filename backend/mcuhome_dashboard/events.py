# SPDX-FileCopyrightText: 2026 The MCUHome Contributors
# SPDX-License-Identifier: Apache-2.0
"""In-process event bus (ADR 0004 decision 3).

Everything that changes without a client asking — a file edited outside
the dashboard, and later the progress of a build session — publishes
here, and the WebSocket connections that subscribed to the topic receive
it. There is one process, so this is a set of :class:`asyncio.Queue`
objects and nothing more; making it a message broker would be inventing
a distributed system for a single-container app.

The bus itself is protocol-independent and survived the teardown of the
job protocol untouched (ADR 0012 decision 3). The ``builds`` topic went
with that protocol and is back with ADR 0013, over
:func:`mcuhome.workbench.api.run_build` instead of a job queue — and the
one property of the old pair that had to be **rebuilt rather than
rediscovered** came back with it: a log event carries the offset it
starts at, because *this* bus drops the oldest events for a subscriber
that fell behind, and build output is exactly the traffic that makes one
fall behind. A progress stream without a resumable position shows a log
with a hole in it and no way to notice. The offsets are line numbers now
rather than byte positions (:mod:`mcuhome_dashboard.builds` streams
lines, not a byte range), which is the same promise in the unit the
producer actually has: monotonic, per build, and never reused.

**Bounded queues, on purpose.** A subscriber that stops reading (a
browser tab that froze, a suspended laptop) must not grow the server's
memory without limit. When a subscriber's queue is full the *oldest*
event is dropped and the drop is counted, so the client learns that its
view is stale and can re-subscribe for a fresh snapshot — which is
exactly the snapshot-then-events contract, applied to its own failure
mode.
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
from collections.abc import Iterable
from dataclasses import dataclass, field
from types import TracebackType
from typing import Any

__all__ = [
    "TOPIC_BUILDS",
    "TOPIC_DEVICES",
    "Event",
    "EventBus",
    "Subscription",
    "build_changed",
    "build_output",
    "build_started",
    "device_added",
    "device_changed",
    "device_removed",
    "tree_state",
]

logger = logging.getLogger(__name__)

#: Changes to the configuration tree: devices appearing, disappearing or
#: being edited, and the tree itself becoming (un)available.
TOPIC_DEVICES = "devices"

#: Builds starting, progressing and ending, and their output (ADR 0013).
TOPIC_BUILDS = "builds"

#: How many events a subscriber may fall behind before the oldest are
#: dropped. Generous for a UI, small enough to be a bound.
QUEUE_LIMIT = 256


@dataclass(frozen=True)
class Event:
    """One thing that happened, addressed to a topic.

    ``name`` is what reaches the client in the ``event`` field of the
    frame; ``topic`` is what a client subscribes to. Several event names
    share one topic — a client interested in the device list wants all
    of them, and should not have to enumerate them.
    """

    topic: str
    name: str
    payload: dict[str, Any] = field(default_factory=dict)


def device_added(device: dict[str, Any]) -> Event:
    return Event(TOPIC_DEVICES, "device_added", {"device": device})


def device_changed(device: dict[str, Any]) -> Event:
    return Event(TOPIC_DEVICES, "device_changed", {"device": device})


def device_removed(name: str) -> Event:
    return Event(TOPIC_DEVICES, "device_removed", {"name": name})


def tree_state(
    root: str | None, *, available: bool, problem: dict[str, Any] | None = None
) -> Event:
    """The tree's usability, and — when it is not usable — why.

    ``problem`` carries codes and numbers, never a rendered sentence:
    both sites publish onto this one bus and the sentence differs
    between them (:func:`mcuhome_dashboard.builder.project_problem`).
    """
    return Event(
        TOPIC_DEVICES, "tree_state", {"root": root, "available": available, "problem": problem}
    )


def build_started(build: dict[str, Any]) -> Event:
    return Event(TOPIC_BUILDS, "build_started", {"build": build})


def build_changed(build: dict[str, Any]) -> Event:
    return Event(TOPIC_BUILDS, "build_changed", {"build": build})


def build_output(build_id: str, *, offset: int, lines: list[str]) -> Event:
    """A batch of build log lines, and the offset the batch starts at.

    ``offset`` is the line number of ``lines[0]`` in this build's log,
    counted from zero and never reused. It is what makes the hole this
    bus is allowed to punch (see the module docstring) *noticeable and
    fillable*: a client that receives ``events_dropped``, or whose next
    batch does not start where the last one ended, asks ``build/log``
    for everything from the offset it has and is whole again.

    Lines are batched rather than sent one per event because a Zephyr
    build prints thousands of them and :data:`QUEUE_LIMIT` is 256. One
    event per line would guarantee the drop it is the client's job to
    recover from; batching makes it rare and the offset makes it
    survivable either way.
    """
    return Event(
        TOPIC_BUILDS, "build_output", {"build_id": build_id, "offset": offset, "lines": lines}
    )


class Subscription:
    """One consumer's view of the bus.

    Async-iterable, so a WebSocket writer is a ``async for event in
    subscription`` loop and nothing else.
    """

    def __init__(self, bus: EventBus, topics: Iterable[str] = ()) -> None:
        self._bus = bus
        self._queue: asyncio.Queue[Event] = asyncio.Queue(maxsize=QUEUE_LIMIT)
        self._topics: set[str] = set(topics)
        self._dropped = 0
        self._closed = False

    @property
    def topics(self) -> frozenset[str]:
        return frozenset(self._topics)

    @property
    def dropped(self) -> int:
        """Events discarded because this subscriber fell too far behind."""
        return self._dropped

    def add(self, *topics: str) -> None:
        self._topics.update(topics)

    def remove(self, *topics: str) -> None:
        self._topics.difference_update(topics)

    def wants(self, topic: str) -> bool:
        return topic in self._topics

    def offer(self, event: Event) -> None:
        """Enqueue *event*, dropping the oldest when the queue is full."""
        if self._closed:
            return
        try:
            self._queue.put_nowait(event)
            return
        except asyncio.QueueFull:
            pass
        try:
            self._queue.get_nowait()
            self._queue.task_done()
        except asyncio.QueueEmpty:  # pragma: no cover - only under a race
            pass
        self._dropped += 1
        try:
            self._queue.put_nowait(event)
        except asyncio.QueueFull:  # pragma: no cover - only under a race
            logger.warning("event bus: subscriber queue still full after eviction")

    async def get(self) -> Event:
        return await self._queue.get()

    def poll(self) -> Event | None:
        """The next event, or ``None`` when there is none waiting."""
        try:
            return self._queue.get_nowait()
        except asyncio.QueueEmpty:
            return None

    def close(self) -> None:
        self._closed = True
        self._bus.unsubscribe(self)

    def __aiter__(self) -> Subscription:
        return self

    async def __anext__(self) -> Event:
        if self._closed:
            raise StopAsyncIteration
        return await self.get()

    def __enter__(self) -> Subscription:
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        self.close()


class EventBus:
    """Fan-out of :class:`Event` objects to :class:`Subscription` objects."""

    def __init__(self) -> None:
        self._subscribers: list[Subscription] = []

    def subscribe(self, *topics: str) -> Subscription:
        subscription = Subscription(self, topics)
        self._subscribers.append(subscription)
        return subscription

    def unsubscribe(self, subscription: Subscription) -> None:
        # Closing twice is normal on a shutdown path, not an error.
        with contextlib.suppress(ValueError):
            self._subscribers.remove(subscription)

    @property
    def subscriber_count(self) -> int:
        return len(self._subscribers)

    def publish(self, event: Event) -> None:
        """Deliver *event* to every subscriber of its topic.

        Never blocks and never raises: publishing is done from the
        middle of other work (a filesystem poll, a protocol handler),
        and a slow consumer is that consumer's problem.
        """
        for subscription in list(self._subscribers):
            if subscription.wants(event.topic):
                subscription.offer(event)
