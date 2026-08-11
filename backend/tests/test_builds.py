# SPDX-FileCopyrightText: 2026 The MCUHome Contributors
# SPDX-License-Identifier: Apache-2.0
"""Building: the verbs, the offsets, the artifacts and the signature (ADR 0013).

Everything here is in-process. The one seam that is faked is
:func:`mcuhome_dashboard.builder.run_build` — the builder's own entry
point into a container, a subprocess or a socket — because a test that
really compiled would be a quarter of an hour of Zephyr and would test
the firmware repository rather than this one.

Two tests deliberately do *not* fake it. ``test_real_run_build_refuses``
drives the actual ``mcuhome.workbench.api.run_build`` with the ``local``
method and asserts what this venv must produce: ``mcuhome-compiler`` is
not installed here (``requirements-dev.txt`` leaves it out on purpose, so
that "the dashboard never compiles" is checkable in the very environment
the tests run in), so the method refuses with :class:`MethodUnavailable`
— and the point is that the refusal arrives as a *rendered build error*
with the pip line in its hint, never as a crashed command.

The signing seam is cut one level lower: :func:`signing.sign_build` runs
for real — it generates the key at ADR 0008's location under the test's
own ``data_dir``, translates refusals and assembles the result — and only
the ``imgtool`` invocation underneath it is replaced. So the key
handling, which is the part worth being sure about, is exercised.
"""

from __future__ import annotations

import asyncio
from importlib import metadata
from pathlib import Path
from typing import Any

import pytest
from mcuhome.model.artifacts import Artifact

from mcuhome_dashboard import builder, builds, signing, versions
from mcuhome_dashboard.app import AppState, create_app
from mcuhome_dashboard.config import Config
from mcuhome_dashboard.events import EventBus
from mcuhome_dashboard.security import TrustMode
from tests.conftest import call

# --------------------------------------------------------------------------
# The fake build
# --------------------------------------------------------------------------


class FakeBuild:
    """One scripted run of ``run_build``, standing in for a real one."""

    def __init__(
        self,
        *,
        successful: bool = True,
        lines: tuple[str, ...] = ("configuring", "compiling", "linking"),
        artifacts: tuple[tuple[str, str], ...] = (("firmware", "firmware.bin"),),
        report: str = signing.BUILD_REPORT_FILE,
        raises: Exception | None = None,
        block: asyncio.Event | None = None,
    ) -> None:
        self.successful = successful
        self.lines = lines
        self.artifacts = artifacts
        self.report = report
        self.raises = raises
        self.block = block
        self.requests: list[builder.BuildRequest] = []
        self.methods: list[str] = []

    async def __call__(self, request: builder.BuildRequest, *, method: str) -> builder.BuildOutcome:
        self.requests.append(request)
        self.methods.append(method)
        for line in self.lines:
            if request.on_line is not None:
                request.on_line(line)
        if self.block is not None:
            await self.block.wait()
        if self.raises is not None:
            raise self.raises

        # A build method delivers into its own directory; the dashboard
        # copies the declared artifacts up from there.
        delivery = Path(request.out_dir) / ".delivery"
        delivery.mkdir(parents=True, exist_ok=True)
        declared = []
        for role, name in self.artifacts:
            (delivery / name).write_bytes(b"unsigned firmware\n")
            declared.append(Artifact(root=delivery, path=name, role=role, sha256=""))
        (delivery / self.report).write_text("{}", encoding="utf-8")

        return builder.BuildOutcome(
            method=method,
            successful=self.successful,
            status="success" if self.successful else "failure",
            context_id="ctx-0123456789abcdef",
            artifacts=tuple(declared),
            out_dir=delivery,
            report=self.report,
            image="ghcr.io/mcu-home/builder:test",
            detail=None if self.successful else _FailureDetail(),
        )


class _FailureDetail:
    """The shape a failed §5.4 result arrives in, minus everything unused."""

    problems = ("the build report is missing the application image",)
    result = {"reason": "compile-failed", "error": {"message": "west build exited 1"}}


def fake_signature(outputs: tuple[str, ...] = ("firmware.signed.bin",)):
    """Replace the ``imgtool`` call inside a real :func:`signing.sign_build`."""

    def signer(build_dir: Path, *, key: Path, report: str, env: dict[str, str]) -> list[Path]:
        assert key.is_file(), "the signing key must exist before the signer runs"
        written = []
        for name in outputs:
            path = build_dir / name
            path.write_bytes(b"signed firmware\n")
            written.append(path)
        return written

    return signer


@pytest.fixture
def fake_build(monkeypatch: pytest.MonkeyPatch) -> FakeBuild:
    """The default: one successful build with three lines of output."""
    stub = FakeBuild()
    monkeypatch.setattr(builder, "run_build", stub)
    monkeypatch.setattr(signing, "_sign_blocking", fake_signature())
    # Nothing here may wrap an OTA image: that needs the real device model
    # to have an update scheme, and the fixture device is asserted about
    # separately in `test_ota_is_written_for_a_device_that_can_take_one`.
    return stub


async def finished(state: AppState, build_id: str, *, timeout: float = 5.0) -> builds.BuildRecord:
    """Wait until a build reaches a terminal state, or fail the test."""
    async with asyncio.timeout(timeout):
        while True:
            record = state.builds.get(build_id)
            if record.finished_state:
                return record
            await asyncio.sleep(0.01)


async def in_the_builder(stub: FakeBuild, *, count: int = 1, timeout: float = 5.0) -> None:
    """Wait until *count* builds have reached ``run_build``, or fail the test.

    Not the same as the record reading ``running``: that is set before
    the model is loaded and the key is read, and a cancel arriving in
    *that* window is a cancel with no work behind it.
    """
    async with asyncio.timeout(timeout):
        while len(stub.requests) < count:
            await asyncio.sleep(0.01)


async def slot_free(state: AppState, *, timeout: float = 5.0) -> None:
    """Wait until the one build slot is given back.

    Not the same as :func:`finished`: a cancelled record is finished
    while the work it started is not, and the slot belongs to the work.
    """
    async with asyncio.timeout(timeout):
        while state.builds.running is not None:
            await asyncio.sleep(0.01)


# --------------------------------------------------------------------------
# Starting
# --------------------------------------------------------------------------


async def test_start_refuses_a_device_that_is_not_there(client, fake_build: FakeBuild) -> None:
    async with client.ws_connect("/ws") as ws:
        frame = await call(ws, "build/start", {"name": "no-such-node"})
    assert frame["type"] == "error"
    assert frame["error"]["code"] == "not_found"


async def test_start_accepts_and_the_build_finishes(
    client, state: AppState, fake_build: FakeBuild
) -> None:
    async with client.ws_connect("/ws") as ws:
        frame = await call(ws, "build/start", {"name": "bench-node"})
        build = frame["payload"]["build"]
        assert build["state"] in {"queued", "running"}
        assert build["device"] == "bench-node"
        assert build["method"] == builder.DEFAULT_BUILD_METHOD

        record = await finished(state, build["id"])
        assert record.state == "succeeded"
        assert record.context_id == "ctx-0123456789abcdef"


async def test_the_method_is_configuration_and_the_override_is_per_build(
    state: AppState, fake_build: FakeBuild
) -> None:
    """ADR 0013 decision 1: the dashboard does not subset the methods."""
    record = await state.builds.begin("bench-node", method="remote")
    await finished(state, record.id)
    assert fake_build.methods == ["remote"]
    assert record.method == "remote"


async def test_a_configured_method_is_used_when_no_override_is_given(
    tree: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    stub = FakeBuild()
    monkeypatch.setattr(builder, "run_build", stub)
    monkeypatch.setattr(signing, "_sign_blocking", fake_signature())
    config = Config(
        config_root=tree,
        poll_interval=0.0,
        data_dir=tmp_path / "data",
        build_method="local-dev",
    )
    state = AppState(config)
    await state.start()
    try:
        record = await state.builds.begin("bench-node")
        await finished(state, record.id)
    finally:
        await state.stop()
    assert stub.methods == ["local-dev"]


async def test_an_unknown_method_is_refused_in_the_builders_own_words(
    client, fake_build: FakeBuild
) -> None:
    async with client.ws_connect("/ws") as ws:
        frame = await call(ws, "build/start", {"name": "bench-node", "method": "telepathy"})
    assert frame["type"] == "error"
    # The hint enumerates the real methods, so a client never has to.
    hint = frame["error"]["errors"][0]["hint"]
    assert "local" in hint and "remote" in hint


async def test_only_one_build_runs_at_a_time(
    client, state: AppState, fake_build: FakeBuild
) -> None:
    """ADR 0013 decision 3, and the refusal names who holds the slot."""
    gate = asyncio.Event()
    fake_build.block = gate
    async with client.ws_connect("/ws") as ws:
        first = await call(ws, "build/start", {"name": "bench-node"})
        second = await call(ws, "build/start", {"name": "broken-node"}, frame_id="2")

        assert second["type"] == "error"
        assert second["error"]["code"] == "conflict"
        assert second["error"]["build"]["id"] == first["payload"]["build"]["id"]

        gate.set()
        await finished(state, first["payload"]["build"]["id"])


async def test_the_slot_is_free_again_afterwards(state: AppState, fake_build: FakeBuild) -> None:
    first = await state.builds.begin("bench-node")
    await finished(state, first.id)
    assert state.builds.running is None
    second = await state.builds.begin("bench-node")
    await finished(state, second.id)
    assert {first.state, second.state} == {"succeeded"}


# --------------------------------------------------------------------------
# Failing
# --------------------------------------------------------------------------


async def test_a_build_that_fails_is_a_successful_command(
    client, state: AppState, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(builder, "run_build", FakeBuild(successful=False))
    async with client.ws_connect("/ws") as ws:
        frame = await call(ws, "build/start", {"name": "bench-node"})
        assert frame["type"] == "result"
        record = await finished(state, frame["payload"]["build"]["id"])

    assert record.state == "failed"
    # Both voices, the way the command line quotes them: the backend's
    # judgement and the program's own account of itself.
    message = record.errors[0]["message"]
    assert "the build report is missing the application image" in message
    assert "compile-failed" in message and "west build exited 1" in message


async def test_a_typed_refusal_keeps_its_hint(
    state: AppState, monkeypatch: pytest.MonkeyPatch
) -> None:
    refusal = builder.MCUHomeError("no build server is configured")
    monkeypatch.setattr(builder, "run_build", FakeBuild(raises=refusal))
    record = await state.builds.begin("bench-node")
    await finished(state, record.id)

    assert record.state == "failed"
    assert record.errors[0]["message"] == "no build server is configured"
    assert record.errors[0]["kind"] == "MCUHomeError"


async def test_a_configuration_that_stopped_resolving_fails_the_build(
    state: AppState, fake_build: FakeBuild
) -> None:
    """The broken device reaches stages 1-3 and is refused there."""
    record = await state.builds.begin("broken-node")
    await finished(state, record.id)

    assert record.state == "failed"
    assert record.errors, "a configuration error has to arrive as diagnostics"
    assert any(entry["line"] is not None for entry in record.errors)
    assert fake_build.requests == [], "a build must not start for a model that does not resolve"


async def test_real_run_build_refuses_because_no_compiler_is_installed(
    client, state: AppState
) -> None:
    """The un-faked seam. ADR 0017 §2, checkable in this very venv.

    Nothing is monkeypatched here: this drives
    ``mcuhome.workbench.api.run_build`` with the ``local`` method, which
    needs ``mcuhome-compiler``. That distribution is deliberately absent
    from ``requirements-dev.txt``, so the method raises
    ``MethodUnavailable`` — and what this asserts is that the refusal
    becomes a rendered build error carrying the exact ``pip install``,
    rather than an internal error or a traceback on the wire.
    """
    async with client.ws_connect("/ws") as ws:
        frame = await call(ws, "build/start", {"name": "bench-node", "method": "local"})
        assert frame["type"] == "result", "starting must succeed; the build is what fails"
        record = await finished(state, frame["payload"]["build"]["id"])

    assert record.state == "failed"
    assert record.errors[0]["kind"] == "MethodUnavailable"
    assert "mcuhome-compiler" in record.errors[0]["message"]
    assert "pip install mcuhome-compiler" in (record.errors[0]["hint"] or "")


async def test_real_run_build_refuses_remote_without_a_server(client, state: AppState) -> None:
    """The other un-faked seam, and the one the App packaging will hit.

    ``remote`` needs an address and there is no discovery and no default
    (firmware E53), so a dashboard configured for it without a build
    server refuses before a socket is opened. Asserted against the real
    ``run_build`` because the wording is the builder's and the whole
    value of ADR 0013 decision 1 is that this side does not write its
    own version of it.
    """
    async with client.ws_connect("/ws") as ws:
        frame = await call(ws, "build/start", {"name": "bench-node", "method": "remote"})
        assert frame["type"] == "result"
        record = await finished(state, frame["payload"]["build"]["id"])

    assert record.state == "failed"
    assert record.errors[0]["kind"] == "RemoteNotConfigured"
    assert "build server" in record.errors[0]["message"]


# --------------------------------------------------------------------------
# Output, offsets and the hole the bus is allowed to punch
# --------------------------------------------------------------------------


def test_log_offsets_are_monotonic_and_survive_truncation() -> None:
    log = builds.BuildLog(limit=3)
    assert log.append(["a", "b"]) == 0
    assert log.append(["c", "d"]) == 2
    # Two lines dropped from the front; the offsets of what is left do
    # not move, which is the whole point of counting rather than indexing.
    assert log.first_offset == 1
    assert log.next_offset == 4

    offset, lines, truncated = log.since(2)
    assert (offset, lines, truncated) == (2, ["c", "d"], False)

    offset, lines, truncated = log.since(0)
    assert truncated is True
    assert (offset, lines) == (1, ["b", "c", "d"])

    assert log.since(4) == (4, [], False)


def test_output_with_no_reader_is_bounded_and_then_dropped() -> None:
    """The producer outlives the reader, so the buffer may not be a list.

    A cancelled ``local`` build keeps printing from a worker thread
    Python cannot interrupt, with nothing draining this side any more.
    Until the record ends the pending lines are bounded like the log
    itself; after it ends they are not kept at all.
    """
    record = builds.BuildRecord(id="beef", device="bench-node", method="local")
    stream = builds._LogStream(record, EventBus())

    stream.sink("configuring\n")
    stream.flush()
    assert record.log.next_offset == 1

    for index in range(builds.MAX_LOG_LINES + 500):
        stream.sink(f"line {index}")
    assert len(stream._pending) == builds.MAX_LOG_LINES

    stream.flush()
    stream.close()
    for index in range(1000):
        stream.sink(f"orphaned {index}")
    assert len(stream._pending) == 0
    stream.flush()
    assert record.log.next_offset == 1 + builds.MAX_LOG_LINES, "a closed stream publishes nothing"


async def test_build_log_serves_a_suffix_from_any_offset(
    client, state: AppState, fake_build: FakeBuild
) -> None:
    fake_build.lines = tuple(f"line {index}" for index in range(10))
    async with client.ws_connect("/ws") as ws:
        started = await call(ws, "build/start", {"name": "bench-node"})
        build_id = started["payload"]["build"]["id"]
        await finished(state, build_id)

        whole = await call(ws, "build/log", {"build_id": build_id}, frame_id="2")
        assert whole["payload"]["lines"] == list(fake_build.lines)
        assert whole["payload"]["offset"] == 0
        assert whole["payload"]["next_offset"] == 10
        assert whole["payload"]["truncated"] is False

        rest = await call(ws, "build/log", {"build_id": build_id, "from_offset": 7}, frame_id="3")
        assert rest["payload"]["offset"] == 7
        assert rest["payload"]["lines"] == ["line 7", "line 8", "line 9"]


async def test_build_log_refuses_a_nonsense_offset(client, fake_build: FakeBuild) -> None:
    async with client.ws_connect("/ws") as ws:
        started = await call(ws, "build/start", {"name": "bench-node"})
        build_id = started["payload"]["build"]["id"]
        frame = await call(
            ws, "build/log", {"build_id": build_id, "from_offset": True}, frame_id="2"
        )
    assert frame["type"] == "error"
    assert frame["error"]["code"] == "bad_request"


async def test_build_log_refuses_an_unknown_build(client) -> None:
    async with client.ws_connect("/ws") as ws:
        frame = await call(ws, "build/log", {"build_id": "deadbeef"})
    assert frame["type"] == "error"
    assert frame["error"]["code"] == "not_found"


async def test_output_events_carry_the_offset_they_start_at(
    client, state: AppState, fake_build: FakeBuild
) -> None:
    """The mechanism that makes a dropped event recoverable (ADR 0013 decision 4)."""
    fake_build.lines = tuple(f"line {index}" for index in range(600))
    async with client.ws_connect("/ws") as ws:
        await call(ws, "build/subscribe")
        started = await call(ws, "build/start", {"name": "bench-node"}, frame_id="2")
        build_id = started["payload"]["build"]["id"]
        await finished(state, build_id)

        received: list[dict[str, Any]] = []
        seen_end = False
        while not seen_end:
            frame = await ws.receive_json(timeout=5)
            if frame.get("type") != "event":
                continue
            if frame["event"] == "build_output":
                received.append(frame["payload"])
            if frame["event"] == "build_changed" and frame["payload"]["build"]["state"] in {
                "succeeded",
                "failed",
            }:
                seen_end = True

    assert received, "output has to reach a subscriber"
    # Batched, not one event per line: 600 lines must not be 600 events.
    assert len(received) < 20
    # The offsets tile the log exactly — no gap, no overlap.
    expected = 0
    for payload in received:
        assert payload["build_id"] == build_id
        assert payload["offset"] == expected
        expected += len(payload["lines"])
    assert expected == 600


async def test_subscribing_gets_a_snapshot_first(
    client, state: AppState, fake_build: FakeBuild
) -> None:
    record = await state.builds.begin("bench-node")
    await finished(state, record.id)
    async with client.ws_connect("/ws") as ws:
        frame = await call(ws, "build/subscribe")
    assert frame["payload"]["topic"] == "builds"
    assert [entry["id"] for entry in frame["payload"]["builds"]] == [record.id]
    assert frame["payload"]["running"] is None


async def test_builds_is_a_known_topic(client) -> None:
    async with client.ws_connect("/ws") as ws:
        frame = await call(ws, "subscribe_events", {"topics": ["builds"]})
    assert frame["payload"]["topics"] == ["builds"]


# --------------------------------------------------------------------------
# Cancelling
# --------------------------------------------------------------------------


async def test_cancel_ends_the_build_without_signing_anything(
    client, state: AppState, fake_build: FakeBuild
) -> None:
    gate = asyncio.Event()
    fake_build.block = gate
    async with client.ws_connect("/ws") as ws:
        started = await call(ws, "build/start", {"name": "bench-node"})
        build_id = started["payload"]["build"]["id"]
        await in_the_builder(fake_build)

        frame = await call(ws, "build/cancel", {"build_id": build_id}, frame_id="2")
        assert frame["type"] == "result"
        record = await finished(state, build_id)

    assert record.state == "cancelled"
    # A cancelled build never leaves a flashable image behind.
    assert record.signing is None
    assert record.artifacts == []
    # And the slot is NOT free while the work runs on — see below.
    assert state.builds.running is record

    gate.set()
    await slot_free(state)


async def test_the_slot_belongs_to_the_work_and_not_to_the_waiting(
    state: AppState, fake_build: FakeBuild
) -> None:
    """ADR 0013 decisions 3 and 7, at the point where they meet.

    Cancelling stops this process waiting; it cannot stop a container.
    Handing the slot back at that moment is what would let a second
    Zephyr build start on a machine the first one is still compiling on
    — so the record reads ``cancelled`` immediately, for the user, and
    the slot stays taken until the work really returns.
    """
    gate = asyncio.Event()
    fake_build.block = gate
    first = await state.builds.begin("bench-node")
    await in_the_builder(fake_build)
    await state.builds.cancel(first.id)
    await finished(state, first.id)

    assert first.state == "cancelled"
    assert state.builds.running is first, "the work is still going, so the slot is still taken"
    with pytest.raises(builds.BuildBusy) as refusal:
        await state.builds.begin("bench-node")
    assert refusal.value.running is first

    # The work returns; only now is the next build accepted.
    gate.set()
    await slot_free(state)
    second = await state.builds.begin("bench-node")
    await finished(state, second.id)
    assert second.state == "succeeded"


async def test_the_conflict_says_which_it_is(
    client, state: AppState, fake_build: FakeBuild
) -> None:
    """A refusal naming a cancelled record has to read like one."""
    gate = asyncio.Event()
    fake_build.block = gate
    record = await state.builds.begin("bench-node")
    await in_the_builder(fake_build)
    await state.builds.cancel(record.id)
    await finished(state, record.id)

    async with client.ws_connect("/ws") as ws:
        frame = await call(ws, "build/start", {"name": "bench-node"})
    assert frame["error"]["code"] == "conflict"
    assert frame["error"]["build"]["state"] == "cancelled"
    assert "was cancelled" in frame["error"]["message"]

    gate.set()
    await slot_free(state)


async def test_a_cancelled_build_takes_its_directory_with_it(
    state: AppState, fake_build: FakeBuild
) -> None:
    """And not before the work stopped writing into it."""
    gate = asyncio.Event()
    fake_build.block = gate
    record = await state.builds.begin("bench-node")
    await in_the_builder(fake_build)
    await state.builds.cancel(record.id)
    await finished(state, record.id)

    out_dir = Path(record.out_dir or "")
    assert out_dir.is_dir(), "the abandoned worker is still writing here"

    gate.set()
    await slot_free(state)
    assert not out_dir.exists()


async def test_cancelling_before_the_build_takes_its_first_step(
    state: AppState, fake_build: FakeBuild
) -> None:
    """``build/start`` answers before the build runs, so this window is real.

    A task cancelled before its coroutine has run never executes the
    body that ends the record and hands the slot back — which would
    leave a build reading ``queued`` forever, holding the one slot.
    """
    record = await state.builds.begin("bench-node")
    await state.builds.cancel(record.id)

    await finished(state, record.id)
    assert record.state == "cancelled"
    await slot_free(state)
    assert fake_build.requests == [], "nothing was built"

    second = await state.builds.begin("bench-node")
    await finished(state, second.id)
    assert second.state == "succeeded"


async def test_cancelling_a_finished_build_is_not_an_error(
    client, state: AppState, fake_build: FakeBuild
) -> None:
    record = await state.builds.begin("bench-node")
    await finished(state, record.id)
    async with client.ws_connect("/ws") as ws:
        frame = await call(ws, "build/cancel", {"build_id": record.id})
    assert frame["type"] == "result"
    assert frame["payload"]["build"]["state"] == "succeeded"


# --------------------------------------------------------------------------
# After the outcome: artifacts, signature, OTA
# --------------------------------------------------------------------------


async def test_declared_artifacts_are_copied_into_the_build_directory(
    state: AppState, config: Config, fake_build: FakeBuild
) -> None:
    record = await state.builds.begin("bench-node")
    await finished(state, record.id)

    out_dir = Path(record.out_dir or "")
    # One directory per build, under the device's own (ADR 0013 decision 5).
    assert out_dir == config.artifact_root / "bench-node" / record.id
    assert (out_dir / "firmware.bin").read_bytes() == b"unsigned firmware\n"
    roles = {entry["role"]: entry for entry in record.artifacts}
    assert roles["firmware"]["path"] == "firmware.bin"
    assert roles["firmware"]["size"] == len(b"unsigned firmware\n")


async def test_nothing_undeclared_rides_along(
    state: AppState, config: Config, fake_build: FakeBuild, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Only what the method declared and verified is copied up."""

    async def with_a_stowaway(request, *, method):
        outcome = await fake_build(request, method=method)
        (Path(outcome.out_dir) / "secrets.txt").write_text("wifi password", encoding="utf-8")
        return outcome

    monkeypatch.setattr(builder, "run_build", with_a_stowaway)
    record = await state.builds.begin("bench-node")
    await finished(state, record.id)

    delivered = Path(fake_build.requests[0].out_dir) / ".delivery" / "secrets.txt"
    assert delivered.is_file(), "the stowaway has to exist for this test to mean anything"

    out_dir = Path(record.out_dir or "")
    assert not (out_dir / "secrets.txt").exists()
    assert all(entry["path"] != "secrets.txt" for entry in record.artifacts)


async def test_the_build_is_signed_afterwards_and_the_key_is_created_once(
    state: AppState, config: Config, fake_build: FakeBuild
) -> None:
    """Firmware E55/E56: every method delivers unsigned, one step signs."""
    first = await state.builds.begin("bench-node")
    await finished(state, first.id)

    key = config.data_dir / signing.KEY_FILE
    assert key.is_file(), "ADR 0008 decision 2: the key lives in the private volume"
    assert first.signing == {
        "signed": True,
        "created_key": True,
        "outputs": ["firmware.signed.bin"],
    }
    assert (Path(first.out_dir or "") / "firmware.signed.bin").is_file()

    second = await state.builds.begin("bench-node")
    await finished(state, second.id)
    # A *second* generation is what must never happen quietly.
    assert second.signing["created_key"] is False


async def test_the_signer_never_invents_a_key(tmp_path: Path) -> None:
    """ADR 0008 decision 3, at the one call that could break it.

    The key is created — if it has to be — by the call that produces the
    build's *public* input, before the build. Creating one at the signing
    step would sign an image with a key its own MCUboot does not carry,
    and would silently make that new key this dashboard's.
    """
    missing = tmp_path / "signing.key"
    with pytest.raises(signing.KeyCustodyError):
        await signing.sign_build(tmp_path, key=missing)
    assert not missing.exists(), "a second key is what must never happen quietly"


async def test_a_key_that_cannot_be_read_says_so_without_saying_where(
    state: AppState, config: Config, fake_build: FakeBuild, monkeypatch: pytest.MonkeyPatch
) -> None:
    """The build record goes to every subscribed tab (ADR 0013 decision 6).

    The libraries underneath name the key file in their refusals, and
    with ``MCUHOME_SIGNING_KEY`` set that is a path the operator chose.
    What crosses the wire names the key by its role; the path is in the
    log.
    """

    def refuse(path, *, env, create=True):
        raise builder.MCUHomeError(
            f"MCUHome cannot read the firmware signing key {path}: permission denied."
        )

    monkeypatch.setattr(signing, "signing_key", refuse)
    record = await state.builds.begin("bench-node")
    await finished(state, record.id)

    assert record.state == "failed"
    assert record.errors[0]["kind"] == "KeyCustodyError"
    assert record.errors[0]["hint"], "it may not say where the key is, so it says how to find out"
    wire = repr(record.to_dict())
    assert signing.KEY_FILE not in wire
    assert str(config.data_dir) not in wire
    assert "permission denied" not in wire


async def test_the_private_key_never_reaches_the_wire_or_the_build(
    state: AppState, fake_build: FakeBuild
) -> None:
    """The invariant, asserted from both ends."""
    record = await state.builds.begin("bench-node")
    await finished(state, record.id)

    wire = record.to_dict()
    assert "key" not in wire["signing"]
    assert signing.KEY_FILE not in repr(wire)

    request = fake_build.requests[0]
    assert request.signing_pub.startswith("-----BEGIN PUBLIC KEY-----")
    assert "PRIVATE KEY" not in request.signing_pub
    # There is no field a private key fits in, on any method.
    assert not hasattr(request, "signing_key")


async def test_a_stale_signed_image_does_not_survive_a_later_build(
    state: AppState, config: Config, fake_build: FakeBuild, monkeypatch: pytest.MonkeyPatch
) -> None:
    """No flashable lookalike beside fresh unsigned firmware.

    The delete-first this used to need is now the shape of the
    directories: a build directory has exactly one writer, and the
    retention rule of ADR 0013 decision 5 removes the older ones when a
    build succeeds.
    """
    first = await state.builds.begin("bench-node")
    await finished(state, first.id)
    first_dir = Path(first.out_dir or "")
    assert (first_dir / "firmware.signed.bin").is_file()

    monkeypatch.setattr(signing, "_sign_blocking", fake_signature(outputs=("firmware.signed.hex",)))
    second = await state.builds.begin("bench-node")
    await finished(state, second.id)

    assert second.state == "succeeded"
    assert not first_dir.exists(), "the older build directory goes when a newer one succeeds"
    second_dir = Path(second.out_dir or "")
    assert (second_dir / "firmware.signed.hex").is_file()
    assert not (second_dir / "firmware.signed.bin").exists()
    # One device, one current image — not a dated pile of them.
    assert [entry.name for entry in (config.artifact_root / "bench-node").iterdir()] == [second.id]


async def test_a_build_that_did_not_succeed_takes_its_own_directory_only(
    state: AppState, fake_build: FakeBuild, monkeypatch: pytest.MonkeyPatch
) -> None:
    """It declared nothing, so it can serve nothing — and the last image stays."""
    good = await state.builds.begin("bench-node")
    await finished(state, good.id)
    good_dir = Path(good.out_dir or "")

    monkeypatch.setattr(builder, "run_build", FakeBuild(successful=False))
    bad = await state.builds.begin("bench-node")
    await finished(state, bad.id)

    assert bad.state == "failed"
    assert not Path(bad.out_dir or "").exists()
    assert (good_dir / "firmware.signed.bin").is_file()


async def test_signing_failure_is_a_rendered_error(
    state: AppState, fake_build: FakeBuild, monkeypatch: pytest.MonkeyPatch
) -> None:
    def refuse(build_dir: Path, *, key: Path, report: str, env: dict[str, str]) -> list[Path]:
        raise builder.MCUHomeError("imgtool is not available here.")

    monkeypatch.setattr(signing, "_sign_blocking", refuse)
    record = await state.builds.begin("bench-node")
    await finished(state, record.id)

    assert record.state == "failed"
    assert "imgtool" in record.errors[0]["message"]
    assert record.errors[0]["kind"] == "SigningError"


async def test_ota_is_written_for_a_device_that_can_take_one(
    state: AppState, config: Config, fake_build: FakeBuild
) -> None:
    """The fixture board has a staging slot and Matter on, so it gets one."""
    record = await state.builds.begin("bench-node")
    await finished(state, record.id)

    assert record.ota is not None, "an nrf7002dk Matter node can be updated over the air"
    ota_path = Path(record.out_dir or "") / record.ota["path"]
    assert ota_path.is_file()
    assert record.ota["path"].endswith(".ota")
    # The .ota wraps the SIGNED image — MCUboot's signature is the only
    # trust anchor in that path.
    assert ota_path.read_bytes().endswith(b"signed firmware\n")
    assert any(entry["role"] == "ota" for entry in record.artifacts)


# --------------------------------------------------------------------------
# The artifact route
# --------------------------------------------------------------------------


async def test_an_artifact_can_be_downloaded(
    client, state: AppState, fake_build: FakeBuild
) -> None:
    record = await state.builds.begin("bench-node")
    await finished(state, record.id)

    response = await client.get(f"/api/builds/{record.id}/artifacts/firmware.signed.bin")
    assert response.status == 200
    assert await response.read() == b"signed firmware\n"
    assert response.headers["Content-Type"] == "application/octet-stream"
    assert response.headers["Content-Disposition"] == 'attachment; filename="firmware.signed.bin"'


async def test_an_artifact_name_cannot_forge_a_header(
    client, state: AppState, fake_build: FakeBuild
) -> None:
    """The file name reaches a header; it may not end the quoted string."""
    fake_build.artifacts = (("firmware", 'ev"il; x=y.bin'),)
    record = await state.builds.begin("bench-node")
    await finished(state, record.id)

    assert any(entry["path"] == 'ev"il; x=y.bin' for entry in record.artifacts)
    response = await client.get(f'/api/builds/{record.id}/artifacts/ev"il; x=y.bin')

    assert response.status == 200
    assert response.headers["Content-Disposition"] == 'attachment; filename="evilxy.bin"'


async def test_the_route_serves_the_declared_artifacts_and_nothing_else(
    client, state: AppState, fake_build: FakeBuild
) -> None:
    """ADR 0013 decision 5 is a rule about the reader too.

    Both container-shaped methods keep their scratch area *inside* the
    build directory, and it holds the build context: the resolved device
    model, verbatim, with the Matter pairing tuple in it. Commissioning
    codes travel only when a user asked for them (AGENTS.md), and a plain
    ``GET`` is not asking.
    """
    record = await state.builds.begin("bench-node")
    await finished(state, record.id)
    out_dir = Path(record.out_dir or "")

    model = out_dir / ".mcuhome-local" / "context" / "model"
    model.mkdir(parents=True, exist_ok=True)
    (model / "device-model.json").write_text('{"passcode": 20202021}', encoding="utf-8")
    (out_dir / "stowaway.bin").write_bytes(b"undeclared\n")

    context = await client.get(
        f"/api/builds/{record.id}/artifacts/.mcuhome-local/context/model/device-model.json"
    )
    assert context.status == 404
    stowaway = await client.get(f"/api/builds/{record.id}/artifacts/stowaway.bin")
    assert stowaway.status == 404

    # What the record declared is what a link in the UI points at.
    declared = await client.get(f"/api/builds/{record.id}/artifacts/firmware.signed.bin")
    assert declared.status == 200


async def test_a_cancelled_build_does_not_serve_the_last_ones_firmware(
    client, state: AppState, fake_build: FakeBuild
) -> None:
    """The URL space is keyed by build id, so it has to mean that build."""
    first = await state.builds.begin("bench-node")
    await finished(state, first.id)
    served = await client.get(f"/api/builds/{first.id}/artifacts/firmware.signed.bin")
    assert served.status == 200

    gate = asyncio.Event()
    fake_build.block = gate
    second = await state.builds.begin("bench-node")
    await in_the_builder(fake_build, count=2)
    await state.builds.cancel(second.id)
    await finished(state, second.id)

    assert second.artifacts == []
    refused = await client.get(f"/api/builds/{second.id}/artifacts/firmware.signed.bin")
    assert refused.status == 404

    gate.set()
    await slot_free(state)
    # And the build that did produce it still serves it.
    still = await client.get(f"/api/builds/{first.id}/artifacts/firmware.signed.bin")
    assert still.status == 200


@pytest.mark.parametrize(
    "path",
    [
        "../../signing.key",
        "..%2f..%2fsigning.key",
        "nothing-here.bin",
    ],
)
async def test_everything_unservable_is_a_404_and_never_a_403(
    client, state: AppState, fake_build: FakeBuild, path: str
) -> None:
    """Naming which guess escaped is free reconnaissance."""
    record = await state.builds.begin("bench-node")
    await finished(state, record.id)

    response = await client.get(f"/api/builds/{record.id}/artifacts/{path}")
    assert response.status == 404


async def test_an_unknown_build_is_a_404(client) -> None:
    response = await client.get("/api/builds/deadbeef/artifacts/firmware.bin")
    assert response.status == 404


async def test_the_artifact_route_needs_an_identity(tree: Path, tmp_path: Path, aiohttp_client):
    """It serves firmware signed with this installation's key."""
    config = Config(
        config_root=tree,
        poll_interval=0.0,
        data_dir=tmp_path / "data",
        host="0.0.0.0",
        password="hunter2",
    )
    state = AppState(config)
    await state.start()
    try:
        http = await aiohttp_client(create_app(state, TrustMode.PUBLIC))
        response = await http.get("/api/builds/whatever/artifacts/firmware.bin")
        assert response.status == 401
    finally:
        await state.stop()


# --------------------------------------------------------------------------
# server/info
# --------------------------------------------------------------------------


async def test_server_info_describes_the_build_setup_without_secrets(
    tree: Path, tmp_path: Path, aiohttp_client
) -> None:
    config = Config(
        config_root=tree,
        poll_interval=0.0,
        data_dir=tmp_path / "data",
        build_method="remote",
        build_server_url="https://build.example/",
        build_server_token="s3cr3t-token",
    )
    state = AppState(config)
    await state.start()
    try:
        http = await aiohttp_client(create_app(state, TrustMode.PUBLIC))
        async with http.ws_connect("/ws") as ws:
            frame = await call(ws, "server/info")
    finally:
        await state.stop()

    # One object, one distribution name: an operator reading this field
    # is on their way to a `pip install`, and `supported` is a
    # requirement string over the same name.
    installed = frame["payload"]["builder"]
    assert installed["package"] == versions.MCUHOME_PACKAGE
    assert installed["supported"].startswith(f"{installed['package']}>=")
    assert metadata.version(installed["package"]), "and it is the one actually installed"

    block = frame["payload"]["build"]
    assert block["method"] == "remote"
    assert block["default_method"] == builder.DEFAULT_BUILD_METHOD
    assert set(block["methods"]) == set(builder.BUILD_METHODS)
    assert block["server_configured"] is True
    # A boolean, not the address, and never the token.
    assert "s3cr3t-token" not in repr(frame)
    assert "build.example" not in repr(frame)


async def test_the_server_and_token_reach_the_build_request(
    tree: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """ADR 0013 decision 2: the dashboard's own settings, mapped through."""
    stub = FakeBuild()
    monkeypatch.setattr(builder, "run_build", stub)
    monkeypatch.setattr(signing, "_sign_blocking", fake_signature())
    config = Config(
        config_root=tree,
        poll_interval=0.0,
        data_dir=tmp_path / "data",
        build_method="remote",
        build_server_url="wss://build.example/ws",
        build_server_token="s3cr3t-token",
        sdk_sources=(tmp_path / "sdk",),
        build_jobs=3,
    )
    state = AppState(config)
    await state.start()
    try:
        record = await state.builds.begin("bench-node")
        await finished(state, record.id)
    finally:
        await state.stop()

    request = stub.requests[0]
    assert request.server == "wss://build.example/ws"
    assert request.token == "s3cr3t-token"
    assert tuple(request.sdk_sources) == (tmp_path / "sdk",)
    assert request.jobs == 3
