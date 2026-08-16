# SPDX-FileCopyrightText: 2026 The MCUHome Contributors
# SPDX-License-Identifier: Apache-2.0
"""Builds: starting them, streaming them, and what happens after one (ADR 0013).

The dashboard's whole build path is this module plus the seam in
:mod:`mcuhome_dashboard.builder`. It drives
:func:`mcuhome.workbench.api.run_build`, which is the builder's one
awaitable over its three build methods, and **does not know which of them
runs**: ``local`` starts a build container on this machine, ``remote``
drives a build server, ``local-dev`` compiles in a west workspace, and
the request object is the same for all three (firmware ADR 0020 decision
6, E64). ADR 0013 decision 1 makes that a decision rather than an
accident: the method is deployment configuration, and a dashboard that
hard-coded one would be re-deciding, worse, something the package already
decided.

**"The dashboard never compiles" (ADR 0003) is unchanged by this.** It is
a statement about the *process*, and it stays true in the strong form:
``mcuhome-compiler`` is not installed here (``requirements-dev.txt`` says
so and leaves it out on purpose), so the two methods that compile refuse
in this very process with :class:`…api.MethodUnavailable` naming the
distribution. That refusal is rendered like any other build error — it is
information, not a crash — and the only way to change it is for a
deployment to install a toolchain deliberately.

Three things the old job protocol had, rebuilt rather than rediscovered:

**Log offsets.** The event bus drops the oldest events for a subscriber
that fell behind (:mod:`mcuhome_dashboard.events`), and build output is
exactly the traffic that makes one fall behind. So every batch of output
says the line offset it starts at, the offsets are monotonic per build
and never reused, and ``build/log`` serves any suffix of the retained
log. A client that sees ``events_dropped``, or a gap between two batches,
asks for everything from the last offset it holds and is whole again —
which is the snapshot-then-events contract of ADR 0004 decision 3 applied
to a stream instead of a list.

**The artifact route.** ``GET /api/builds/{build}/artifacts/{path}``
serves the durable, locally signed copy, because ``<a download>``, the
flash ladder of ADR 0010 and ``curl`` all take a URL and none of them can
take a frame (ADR 0004 decision 4). It serves **what the record
declares** and nothing else — the same list :func:`_measure_outputs`
publishes — so the build context that a method leaves in the same
directory, carrying the resolved model and its commissioning
credentials, is not addressable. Its refusal came back with it: an
undeclared name, a missing file and a path that resolves outside the
build's own directory are all a **404 and never a 403**, because naming
which guess escaped is free reconnaissance.

**Signing.** Every build method delivers an *unsigned* image and one
host-side step signs it (firmware E55/E56). Here that step is
:mod:`mcuhome_dashboard.signing` over the key at ADR 0008's
``/data/signing.key``, and the private half never enters a
:class:`…api.BuildRequest` — there is no field it would fit in. What goes
in is the PEM public half, which the same call that produces it creates
on first need.

**One build at a time, for the whole process** (ADR 0013 decision 3). Not
one per device: the default method compiles in a container on this
machine, two concurrent Zephyr builds on the hardware a Home Assistant
box actually is will thrash rather than finish sooner, and a rule that is
true of the machine is worth more than one that is true of the object the
user clicked. A second start is refused with ``conflict`` naming the
build that holds the slot.

**The slot belongs to the work, not to the task waiting for it.** A
cancel stops this process waiting; it cannot stop a container, because
``local`` and ``local-dev`` block in a worker thread Python cannot
interrupt. So the call into the builder is *shielded*: the record ends
``cancelled`` at once, for the user, while the slot stays taken until the
work really returns and :meth:`BuildRegistry._reap` gives it back. A
build started in that window is refused with the same ``conflict`` — the
alternative is two Zephyr builds on the machine the rule above is about.
"""

from __future__ import annotations

import asyncio
import contextlib
import itertools
import logging
import os
import shutil
import threading
import time
import uuid
from collections import deque
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from mcuhome_dashboard import builder, events, signing
from mcuhome_dashboard.config import Config
from mcuhome_dashboard.devices import DeviceStore
from mcuhome_dashboard.events import EventBus

__all__ = [
    "STATES",
    "BuildBusy",
    "BuildLog",
    "BuildRecord",
    "BuildRegistry",
    "UnknownBuild",
]

logger = logging.getLogger(__name__)

#: Queued means "accepted, nothing started yet" and lasts microseconds;
#: it exists so a client never sees a build it started in no state at all.
STATE_QUEUED = "queued"
STATE_RUNNING = "running"
STATE_SUCCEEDED = "succeeded"
STATE_FAILED = "failed"
STATE_CANCELLED = "cancelled"

#: Every state a build can be in, for a client that renders them.
STATES = (STATE_QUEUED, STATE_RUNNING, STATE_SUCCEEDED, STATE_FAILED, STATE_CANCELLED)

#: Lines of build output kept per build. A Matter build prints tens of
#: thousands; keeping all of them in one process's memory for every build
#: it ever ran is how a dashboard becomes the reason the machine swaps.
#: What is dropped is dropped from the *front*, and ``build/log`` says so
#: (``truncated``) rather than silently renumbering.
MAX_LOG_LINES = 20_000

#: Lines per ``build_output`` event. Batching is what keeps the bus's
#: 256-deep queues from overflowing on every build (see
#: :func:`mcuhome_dashboard.events.build_output`); the cap keeps one
#: frame from becoming a megabyte.
MAX_EVENT_LINES = 250

#: How often pending output is flushed onto the bus. Short enough that a
#: log looks live, long enough that a chatty compiler produces events in
#: the tens per second rather than the thousands.
FLUSH_INTERVAL = 0.2

#: Finished builds kept for inspection. They hold a log and a record, not
#: artifacts — the files stay on disk under the build directory.
MAX_FINISHED = 20


class BuildBusy(RuntimeError):
    """A build was asked for while another holds the one slot."""

    def __init__(self, running: BuildRecord) -> None:
        super().__init__(f"The build of {running.device} holds the one build slot.")
        self.running = running


class UnknownBuild(LookupError):
    """No build by that id in this process."""


class BuildLog:
    """A build's output, with the offsets that make a hole recoverable.

    Offsets are **line numbers from the start of this build**, monotonic
    and never reused. :attr:`first_offset` moves forward as old lines are
    dropped, which is the one thing a reader has to be told about — a
    request for something older than that comes back marked
    ``truncated`` instead of silently starting somewhere else.
    """

    def __init__(self, limit: int = MAX_LOG_LINES) -> None:
        self._lines: deque[str] = deque()
        self._limit = limit
        self._first = 0
        self._next = 0

    @property
    def first_offset(self) -> int:
        """Offset of the oldest line still held."""
        return self._first

    @property
    def next_offset(self) -> int:
        """Offset the next line appended will get; also the line count."""
        return self._next

    def append(self, lines: list[str]) -> int:
        """Add *lines*. Returns the offset the first of them got."""
        start = self._next
        self._lines.extend(lines)
        self._next += len(lines)
        while len(self._lines) > self._limit:
            self._lines.popleft()
            self._first += 1
        return start

    def since(self, offset: int) -> tuple[int, list[str], bool]:
        """Everything from *offset*: ``(offset served, lines, truncated)``.

        ``truncated`` is True when *offset* named a line that has already
        been dropped — the answer then starts at :attr:`first_offset` and
        says so, which is what lets a client show "…earlier output was
        discarded" instead of a log that quietly begins in the middle.
        """
        wanted = max(offset, 0)
        truncated = wanted < self._first
        start = max(wanted, self._first)
        if start >= self._next:
            return self._next, [], truncated
        return start, list(itertools.islice(self._lines, start - self._first, None)), truncated


@dataclass
class BuildRecord:
    """One build, from the moment it was accepted to whatever ended it."""

    id: str
    device: str
    #: Which of :data:`mcuhome_dashboard.builder.BUILD_METHODS` runs.
    method: str
    state: str = STATE_QUEUED
    started: float = field(default_factory=time.time)
    finished: float | None = None
    #: The build directory, absolute. Where the artifacts below are.
    out_dir: Path | None = None
    #: The identity the work is attributed to (firmware ADR 0018), empty
    #: for a method that has no build context.
    context_id: str = ""
    #: The build-container reference, where one was used.
    image: str = ""
    #: The method's own word for the result — ``success``/``failure``.
    status: str = ""
    #: Why it failed, in the shape ``device/validate`` already uses: the
    #: builder's own ``to_dict``, so a build refusal renders on the same
    #: component as a configuration problem, hint and all.
    errors: list[dict[str, Any]] = field(default_factory=list)
    #: Declared, verified artifacts, relative to :attr:`out_dir`.
    artifacts: list[dict[str, Any]] = field(default_factory=list)
    signing: dict[str, Any] | None = None
    ota: dict[str, Any] | None = None
    log: BuildLog = field(default_factory=BuildLog)

    @property
    def finished_state(self) -> bool:
        return self.state in {STATE_SUCCEEDED, STATE_FAILED, STATE_CANCELLED}

    def to_dict(self) -> dict[str, Any]:
        """The record as it crosses the wire.

        No paths outside the build directory and no key material: the
        signing block says *whether* a key was used and created, and
        names the files it produced, never where the key is. ``out_dir``
        is a server path and stays here.
        """
        return {
            "id": self.id,
            "device": self.device,
            "method": self.method,
            "state": self.state,
            "started": self.started,
            "finished": self.finished,
            "context_id": self.context_id,
            "image": self.image,
            "status": self.status,
            "errors": list(self.errors),
            "artifacts": list(self.artifacts),
            "signing": dict(self.signing) if self.signing else None,
            "ota": dict(self.ota) if self.ota else None,
            "log_first_offset": self.log.first_offset,
            "log_next_offset": self.log.next_offset,
        }


class _LogStream:
    """Turns ``on_line`` callbacks into batched events on the bus.

    ``on_line`` is called from whichever thread the build method runs on
    — a worker thread for the two synchronous methods, the event loop for
    ``remote`` — so :meth:`sink` does the one thing that is safe from
    both: append to a bounded queue under a lock. Everything that touches
    the record, the offsets or the bus happens in :meth:`flush`, on the
    loop.

    **The queue is bounded and the stream can be closed**, because the
    producer outlives the reader. A cancelled ``local`` build keeps
    printing from its worker thread for the rest of its compile, with
    nothing draining this side any more; an unbounded list there would
    hold tens of thousands of lines that no client can ever ask for,
    which is precisely the memory :data:`MAX_LOG_LINES` exists to bound.
    So what is not flushed drops from the front like the log itself, and
    after :meth:`close` — the last flush of a finished record — nothing
    is kept at all.
    """

    def __init__(self, record: BuildRecord, bus: EventBus) -> None:
        self._record = record
        self._bus = bus
        self._pending: deque[str] = deque(maxlen=MAX_LOG_LINES)
        self._closed = False
        self._lock = threading.Lock()

    def sink(self, line: str) -> None:
        with self._lock:
            if self._closed:
                return
            self._pending.append(line.rstrip("\n"))

    def close(self) -> None:
        """Stop accepting output. Called after the record's last flush."""
        with self._lock:
            self._closed = True
            self._pending.clear()

    def flush(self) -> None:
        with self._lock:
            batch = list(self._pending)
            self._pending.clear()
        for start in range(0, len(batch), MAX_EVENT_LINES):
            chunk = batch[start : start + MAX_EVENT_LINES]
            offset = self._record.log.append(chunk)
            self._bus.publish(events.build_output(self._record.id, offset=offset, lines=chunk))

    async def pump(self) -> None:
        while True:
            await asyncio.sleep(FLUSH_INTERVAL)
            self.flush()


class BuildRegistry:
    """Every build this process ran, and the one slot they take turns in.

    Follows :class:`~mcuhome_dashboard.devices.DeviceStore`'s shape —
    constructed with the bus, started and stopped by
    :class:`~mcuhome_dashboard.app.AppState` — because it is the same
    kind of object: long-lived, owned by the process rather than by a
    connection, and publishing to a topic that any number of sockets
    subscribe to.
    """

    def __init__(self, config: Config, bus: EventBus, *, devices: DeviceStore) -> None:
        self._config = config
        self._bus = bus
        self._devices = devices
        self._records: dict[str, BuildRecord] = {}
        self._order: deque[str] = deque()
        self._task: asyncio.Task[None] | None = None
        #: The call into the builder, kept apart from the task awaiting it:
        #: this is what holds the slot, and a cancel does not stop it.
        self._work: asyncio.Task[builder.BuildOutcome] | None = None
        self._reaper: asyncio.Task[None] | None = None
        self._running: BuildRecord | None = None
        self._lock = asyncio.Lock()

    # -- state ------------------------------------------------------

    @property
    def running(self) -> BuildRecord | None:
        """The record holding the one slot, if any.

        After a cancel this is a record that already reads ``cancelled``:
        the work it started outlives the waiting, and the slot is the
        work's until :meth:`_reap` gives it back.
        """
        return self._running

    def snapshot(self) -> list[BuildRecord]:
        """Every known build, oldest first."""
        return [self._records[key] for key in self._order]

    def get(self, build_id: str) -> BuildRecord:
        try:
            return self._records[build_id]
        except KeyError:
            raise UnknownBuild(build_id) from None

    # -- lifecycle --------------------------------------------------

    async def start(self) -> None:
        """Nothing to start. Present so the caller treats both stores alike."""

    async def stop(self) -> None:
        """Cancel a build in flight and wait for it to unwind.

        The **work** is cancelled here too, which :meth:`cancel`
        deliberately does not do: the process is going away, so there is
        no slot left to protect and no second build that could collide
        with this one. A worker thread Python cannot interrupt still runs
        to its own end — ``asyncio.run`` waits for the default executor
        on the way out — and that is a property of the thread rather than
        something this can decide. Nothing is deleted on this path for
        the same reason: the container may still be writing.
        """
        task = self._task
        if task is not None:
            task.cancel()
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await task
        work = self._work
        if work is not None:
            work.cancel()
        reaper = self._reaper
        if reaper is not None:
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await reaper
        self._task = None

    # -- driving ----------------------------------------------------

    async def begin(self, device: str, *, method: str | None = None) -> BuildRecord:
        """Accept a build of *device*, or refuse it.

        Raises :class:`BuildBusy` when the slot is taken, and whatever
        :func:`mcuhome.workbench.api.resolve_method` raises for a method
        name that is not one of the builder's — both before a record
        exists, so a refusal never leaves one behind.
        """
        chosen = builder.resolve_method(method or self._config.build_method)
        async with self._lock:
            if self._running is not None:
                raise BuildBusy(self._running)
            record = BuildRecord(id=uuid.uuid4().hex[:16], device=device, method=chosen)
            self._records[record.id] = record
            self._order.append(record.id)
            self._running = record
            self._forget_old()
            task = asyncio.create_task(self._run(record), name=f"mcuhome-build-{record.id}")
            task.add_done_callback(lambda _task: self._settle(record))
            self._task = task
        self._bus.publish(events.build_started(record.to_dict()))
        return record

    async def cancel(self, build_id: str) -> BuildRecord:
        """Stop the dashboard's part of a running build.

        What that means is stated rather than smoothed over: the work
        itself runs to its own end. ``local`` and ``local-dev`` are
        blocked in a worker thread that Python cannot interrupt, so the
        container or the compiler cannot be told anything at all, and
        ``remote`` is left alone for the same reason the slot is — a
        build server that is still building is still using the machine
        this dashboard promised it would only use once at a time.

        What the cancel *does* is immediate and is the part that matters:
        the record ends ``cancelled`` now, no artifacts are collected and
        nothing is signed — so a cancelled build never leaves a flashable
        image — and its build directory is removed once nothing is
        writing to it. The slot stays taken until then, so a second start
        is refused with ``conflict`` rather than accepted into a machine
        the first build is still using.
        """
        record = self.get(build_id)
        task = self._task
        if record.finished_state or task is None or self._running is not record:
            return record
        task.cancel()
        return record

    def _forget_old(self) -> None:
        while len(self._order) > MAX_FINISHED:
            oldest = self._order[0]
            if not self._records[oldest].finished_state:
                return
            self._order.popleft()
            del self._records[oldest]

    def _settle(self, record: BuildRecord) -> None:
        """Backstop for a build whose task ended without :meth:`_run` running.

        ``build/start`` answers before the build takes its first step, so
        a client can cancel inside that window — and a task cancelled
        before its coroutine has run never executes the body that would
        end the record and hand the slot back. On every ordinary path
        this finds both already done and does nothing.
        """
        if not record.finished_state:
            self._finish(record, STATE_CANCELLED)
            self._publish(record)
        if self._running is record and self._work is None and self._reaper is None:
            self._running = None
            self._task = None

    def _finish(self, record: BuildRecord, state: str) -> None:
        record.state = state
        record.finished = time.time()

    def _publish(self, record: BuildRecord) -> None:
        self._bus.publish(events.build_changed(record.to_dict()))

    async def _run(self, record: BuildRecord) -> None:
        """One build, from the model to the signed image."""
        stream = _LogStream(record, self._bus)
        pump = asyncio.create_task(stream.pump(), name=f"mcuhome-build-log-{record.id}")
        try:
            await self._build(record, stream)
        except asyncio.CancelledError:
            self._finish(record, STATE_CANCELLED)
            raise
        except builder.MCUHomeError as error:
            # Every refusal the builder raises — a missing compiler
            # distribution, a build server that is not configured, a
            # configuration that stopped resolving between the list and
            # the click — arrives with a message and a hint, in the same
            # shape validation errors use.
            record.errors = builder.errors_from_exception(error, root=self._devices.root)
            self._finish(record, STATE_FAILED)
        except (signing.SigningError, OSError) as error:
            if isinstance(error, signing.KeyCustodyError):
                # The public message says nothing about where the key is
                # (it goes to every subscribed tab); the operator's copy,
                # which does, goes here and nowhere else.
                logger.exception("build %s: %s", record.id, error.detail)
            record.errors = [_plain_error(error)]
            self._finish(record, STATE_FAILED)
        except Exception as error:  # pragma: no cover - a bug on this side
            logger.exception("build %s of %s failed unexpectedly", record.id, record.device)
            record.errors = [_plain_error(error)]
            self._finish(record, STATE_FAILED)
        finally:
            pump.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await pump
            stream.flush()
            # Nothing drains this stream again. An abandoned worker keeps
            # calling ``sink`` for the rest of its compile, and lines with
            # no reader are lines nobody can ask for.
            stream.close()
            if not record.finished_state:  # pragma: no cover - defensive
                self._finish(record, STATE_FAILED)
            self._task = None
            work = self._work
            if work is not None and not work.done():
                # Cancelled: the work goes on, so the slot and the
                # directory it is writing into stay its own until it ends.
                self._reaper = asyncio.create_task(
                    self._reap(record, work), name=f"mcuhome-build-reap-{record.id}"
                )
            else:
                self._work = None
                await self._retire(record)
                self._running = None
            self._publish(record)

    async def _reap(self, record: BuildRecord, work: asyncio.Task[Any]) -> None:
        """Hold the slot until abandoned work really ends, then tidy up.

        This is what makes "one build at a time" true across a cancel.
        Handing the slot back when the *await* ended would let a second
        build start into a machine the first one is still compiling on,
        and — before its directory became this build's own — into the
        very context directory that build is mounted on.
        """
        with contextlib.suppress(asyncio.CancelledError, Exception):
            await asyncio.gather(work, return_exceptions=True)
        self._work = None
        self._reaper = None
        # Cancelled work never ended: at process stop the container may
        # still be writing, and removing a live build directory is the
        # accident this whole path exists to prevent.
        if not work.cancelled():
            await self._retire(record)
        if self._running is record:
            self._running = None

    async def _retire(self, record: BuildRecord) -> None:
        """Apply the retention rule of ADR 0013 decision 5 to a finished build.

        A build that succeeded *is* the device's current image, so its
        directory stays and every older one goes. A build that did not
        succeed declared no artifacts and can serve nothing, so its own
        directory goes instead — which also takes the build context, and
        with it the resolved model, off the disk.

        Never raises: a directory that could not be removed is a disk to
        look at, not a reason for the slot to stay taken forever.
        """
        out_dir = record.out_dir
        if out_dir is None:
            return
        try:
            if record.state == STATE_SUCCEEDED:
                await asyncio.to_thread(_keep_only, out_dir)
            else:
                await asyncio.to_thread(shutil.rmtree, out_dir, ignore_errors=True)
        except OSError:
            logger.warning("build %s: %s could not be tidied up", record.id, out_dir)

    async def _build(self, record: BuildRecord, stream: _LogStream) -> None:
        """The build proper. Raises; the caller turns that into a state."""
        root = self._devices.root
        entry = self._devices.entry_path(record.device)
        if root is None or entry is None:
            # The device was in the list when the command was checked and
            # is not now — deleted between the click and the thread.
            raise builder.MCUHomeError(
                f'There is no device called "{record.device}" in this configuration tree.'
            )

        record.state = STATE_RUNNING
        self._publish(record)

        model = await asyncio.to_thread(_load_model, root, entry)

        # The public half, and the side effect that matters: after this
        # the signing key exists, so the signer below never has to invent
        # one (ADR 0008 decision 3 — a second key orphans every device
        # already bootstrapped against the first).
        key = signing.key_path(self._config.data_dir)
        pem, created_key = await asyncio.to_thread(signing.public_key, key)

        # One directory per build, under the device's own (ADR 0013
        # decision 5). Per *device* was one directory two builds wrote
        # into: the scratch area a method puts inside it — the build
        # context, bind-mounted into a running container — was shared,
        # and a record's artifacts were whatever the last write left.
        out_dir = (self._config.artifact_root / record.device / record.id).resolve()
        record.out_dir = out_dir
        await asyncio.to_thread(out_dir.mkdir, parents=True, exist_ok=True)

        environment = dict(os.environ)
        request = builder.BuildRequest(
            model=model,
            out_dir=out_dir,
            env=environment,
            jobs=self._config.build_jobs,
            signing_pub=pem,
            sdk_sources=self._config.sdk_sources,
            server=self._config.build_server_url,
            token=self._config.build_server_token,
            on_line=stream.sink,
        )
        # Shielded, and this is the one place it matters: a cancel must
        # not hand the slot back while a container is still compiling.
        # Two methods block in a worker thread Python cannot interrupt,
        # so cancelling the await would end the waiting and nothing else.
        # What the cancel does stop is every line below this one.
        work = asyncio.create_task(
            builder.run_build(request, method=record.method),
            name=f"mcuhome-build-work-{record.id}",
        )
        self._work = work
        outcome = await asyncio.shield(work)

        record.context_id = outcome.context_id
        record.image = outcome.image
        record.status = outcome.status
        if not outcome.successful:
            record.errors = [_outcome_error(outcome)]
            self._finish(record, STATE_FAILED)
            return

        # `run_build` holds the directory for the compile and gives it
        # back before returning, so the host-side signing that follows
        # is outside its guard. The command line closes that window by
        # holding the directory across both, and so does this: the lock
        # is per directory and every operation on one takes it, naming
        # itself. Not the compile as well, because a cancel leaves the
        # shielded work running in this process — the outermost holder
        # would hand the directory back while its own build is still in
        # it. This build's directory is its own (one per build id), so
        # what the guard is really for is the operations that come to an
        # existing directory later: flashing it, cleaning it.
        with builder.build_lock(out_dir, device=record.device, operation="sign"):
            record.artifacts = await asyncio.to_thread(_collect_artifacts, outcome, out_dir)

            # No stale signed image can be sitting here: this directory
            # is this build's own, and the signing step below is the
            # only thing that ever writes a `.signed.` name or an `.ota`
            # into it. What used to be a delete-first is now the shape
            # of the directory.
            result = await signing.sign_build(
                out_dir, key=key, report=outcome.report, env=environment
            )
            # Assembled field by field, never from
            # ``SigningResult.to_dict``: that carries ``key``, the path
            # of the private key, and this object goes to every
            # subscribed browser tab. What a client may know is *that*
            # it was signed and *what* was written.
            record.signing = {
                "signed": True,
                "created_key": result.created_key or created_key,
                "outputs": list(result.outputs),
            }

            signed_bin = next(
                (out_dir / name for name in result.outputs if name.endswith(".bin")), None
            )
            if signed_bin is not None:
                record.ota = await asyncio.to_thread(
                    builder.write_ota_image, model, out_dir=out_dir, signed=signed_bin
                )
            record.artifacts = await asyncio.to_thread(_measure_outputs, out_dir, record)
        self._finish(record, STATE_SUCCEEDED)


# --------------------------------------------------------------------------
# Blocking helpers — every one of them runs in a thread
# --------------------------------------------------------------------------


def _load_model(root: Path, entry: Path):
    tree = builder.open_config_tree(root)
    return builder.load_model(entry, tree=tree)


def _plain_error(error: Exception) -> dict[str, Any]:
    """An exception the builder did not raise, in the diagnostics shape.

    A ``hint`` attribute is carried across when the exception has one —
    :class:`…signing.KeyCustodyError` has one precisely because its
    message may not name the file it is about.
    """
    hint = getattr(error, "hint", None)
    return {
        "message": str(error),
        "file": None,
        "line": None,
        "column": None,
        "key": None,
        "hint": hint if isinstance(hint, str) else None,
        "kind": type(error).__name__,
    }


def _outcome_error(outcome: builder.BuildOutcome) -> dict[str, Any]:
    """A build that ran and failed, as one diagnostic.

    Two voices, both quoted, the way the command line quotes them: the
    backend's judgement of which §5.3 condition failed (``problems``) and
    the program's own account of itself (the §5.4 result document). A
    program that refuses before it runs writes the document and not a
    line of build log, so dropping it would leave "status 'failure'" as
    the entire diagnosis of a failure that was explained precisely.
    """
    inner = getattr(outcome.detail, "outcome", outcome.detail)
    problems = "; ".join(getattr(inner, "problems", ()) or ())
    document = getattr(inner, "result", None) or {}
    if not document and getattr(inner, "error", None):
        document = {"error": inner.error}
    error = document.get("error") if isinstance(document, dict) else None
    error = error if isinstance(error, dict) else {}

    reason = document.get("reason") if isinstance(document, dict) else None
    said = [str(value) for value in (reason, error.get("message")) if value]
    message = problems or f"the build reported {outcome.status!r} and no usable result"
    if said:
        message = f"{message}. The program said: {' — '.join(said)}"
    return {
        "message": f"The firmware did not build: {message}",
        "file": None,
        "line": None,
        "column": None,
        "key": None,
        "hint": (
            "the build log carries what west and the compiler said. This build ran "
            f"with the {outcome.method} method; the dashboard's method is deployment "
            "configuration (--build-method)."
        ),
        "kind": "BuildError",
    }


def _collect_artifacts(outcome: builder.BuildOutcome, out_dir: Path) -> list[dict[str, Any]]:
    """Copy the declared, verified artifacts up into the build directory.

    A build environment delivers into a per-invocation directory that the
    next build wipes; the durable copies a user downloads and flashes
    belong in the build directory itself. **Only** what the method
    declared and verified is copied — nothing undeclared rides along, on
    any method.
    """
    collected: list[dict[str, Any]] = []
    if outcome.out_dir is None:
        return collected
    for artifact in outcome.artifacts:
        source = Path(outcome.out_dir) / artifact.path
        destination = out_dir / artifact.path
        if destination != source:
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, destination)
        collected.append(
            {
                "role": artifact.role,
                "path": artifact.path,
                "size": destination.stat().st_size if destination.is_file() else 0,
                "signed": False,
            }
        )
    return collected


def _keep_only(out_dir: Path) -> None:
    """Leave *out_dir* as the only build directory this device has.

    ADR 0013 decision 5's retention rule. One directory per build is what
    makes a record's artifacts provably its own; keeping every one of
    them would make ``/data`` a dated pile of Zephyr images. So the build
    that just succeeded *is* the device's current image and the rest go —
    including the directories of builds this process no longer has a
    record for, which is the state a restart leaves behind.
    """
    for stale in out_dir.parent.iterdir():
        if stale == out_dir:
            continue
        if stale.is_dir():
            shutil.rmtree(stale, ignore_errors=True)
        else:
            stale.unlink(missing_ok=True)


def _measure_outputs(out_dir: Path, record: BuildRecord) -> list[dict[str, Any]]:
    """The artifact list a client downloads from: unsigned, signed, .ota.

    The signed images and the ``.ota`` are artifacts of *this* dashboard
    rather than of the build, which is why they are added here and not by
    :func:`_collect_artifacts`: the build never saw the private key
    (firmware E56), so it could not have declared them.
    """
    listed = list(record.artifacts)
    known = {entry["path"] for entry in listed}
    extra: list[tuple[str, str]] = [
        ("firmware-signed", name) for name in (record.signing or {}).get("outputs", ())
    ]
    if record.ota is not None:
        extra.append(("ota", record.ota["path"]))
    for role, name in extra:
        if name in known:
            continue
        path = out_dir / name
        if not path.is_file():
            continue
        listed.append({"role": role, "path": name, "size": path.stat().st_size, "signed": True})
        known.add(name)
    return listed
