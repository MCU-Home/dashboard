# SPDX-FileCopyrightText: 2026 The MCUHome Contributors
# SPDX-License-Identifier: Apache-2.0
"""The configuration-tree scanner and its events.

The interesting property is the negative one: a file whose modification
time moved but whose bytes did not must produce **no** event. A git
checkout, an editor's save-with-no-change and a backup restore all do
exactly that, and each of them would otherwise redraw every open browser
tab.
"""

from __future__ import annotations

import os
from pathlib import Path

import pytest

from mcuhome_dashboard import builder
from mcuhome_dashboard.devices import DeviceStore
from mcuhome_dashboard.events import TOPIC_DEVICES, EventBus
from tests.conftest import VALID_CONFIG, make_tree, write_device


@pytest.fixture
def bus_and_store(tree: Path):
    bus = EventBus()
    store = DeviceStore(tree, bus, poll_interval=0.0)
    subscription = bus.subscribe(TOPIC_DEVICES)
    return bus, store, subscription


def drain(subscription) -> list[tuple[str, dict]]:
    events = []
    while (event := subscription.poll()) is not None:
        events.append((event.name, event.payload))
    return events


async def test_the_first_scan_finds_every_device(bus_and_store) -> None:
    _bus, store, subscription = bus_and_store
    await store.start()

    assert [entry.name for entry in store.snapshot()] == ["bench-node", "broken-node"]
    assert store.available is True
    # The first scan is the snapshot a client receives, not a burst of
    # "added" events for a tree that was already there.
    assert drain(subscription) == []


async def test_a_touch_changes_nothing(bus_and_store, tree: Path) -> None:
    _bus, store, subscription = bus_and_store
    await store.start()
    before = store.get("bench-node")

    entry = tree / "devices" / "bench-node" / "main.yaml"
    os.utime(entry, (0, 0))
    assert await store.refresh() is False

    assert drain(subscription) == []
    assert store.get("bench-node") == before


async def test_a_rewrite_with_identical_bytes_changes_nothing(bus_and_store, tree: Path) -> None:
    _bus, store, subscription = bus_and_store
    await store.start()

    entry = tree / "devices" / "bench-node" / "main.yaml"
    entry.write_text(entry.read_text(), encoding="utf-8")
    os.utime(entry, (1, 1))
    await store.refresh()

    assert drain(subscription) == []


async def test_an_edit_produces_one_changed_event(bus_and_store, tree: Path) -> None:
    _bus, store, subscription = bus_and_store
    await store.start()
    before = store.get("bench-node").content_hash

    entry = tree / "devices" / "bench-node" / "main.yaml"
    entry.write_text(VALID_CONFIG.replace("sampling: 10s", "sampling: 30s"), encoding="utf-8")
    assert await store.refresh() is True

    events = drain(subscription)
    assert [name for name, _ in events] == ["device_changed"]
    assert events[0][1]["device"]["name"] == "bench-node"
    assert store.get("bench-node").content_hash != before


async def test_a_second_file_in_the_folder_counts_towards_the_hash(
    bus_and_store, tree: Path
) -> None:
    _bus, store, subscription = bus_and_store
    await store.start()

    (tree / "devices" / "bench-node" / "extra.yaml").write_text("a: 1\n", encoding="utf-8")
    assert await store.refresh() is True
    assert [name for name, _ in drain(subscription)] == ["device_changed"]


async def test_a_new_device_is_added_and_a_removed_one_is_removed(
    bus_and_store, tree: Path
) -> None:
    _bus, store, subscription = bus_and_store
    await store.start()

    write_device(tree, "third-node", VALID_CONFIG.replace("bench-node", "third-node"))
    await store.refresh()
    assert [name for name, _ in drain(subscription)] == ["device_added"]

    for path in sorted((tree / "devices" / "third-node").rglob("*"), reverse=True):
        path.unlink()
    (tree / "devices" / "third-node").rmdir()
    await store.refresh()
    events = drain(subscription)
    assert [name for name, _ in events] == ["device_removed"]
    assert events[0][1] == {"name": "third-node"}


async def test_a_folder_without_a_main_yaml_is_not_a_device(bus_and_store, tree: Path) -> None:
    _bus, store, _subscription = bus_and_store
    (tree / "devices" / "half-finished").mkdir()
    await store.start()
    assert "half-finished" not in {entry.name for entry in store.snapshot()}


async def test_a_missing_tree_is_reported_not_refused(tmp_path: Path) -> None:
    bus = EventBus()
    store = DeviceStore(tmp_path / "nowhere", bus, poll_interval=0.0)
    await store.start()

    assert store.available is False
    assert store.snapshot() == []
    assert store.tree_state() == {
        "root": str(tmp_path / "nowhere"),
        "available": False,
        "device_count": 0,
        "problem": {"code": builder.PROBLEM_NO_PROJECT},
    }


async def test_a_project_needing_an_upgrade_lists_nothing_and_says_why(
    tmp_path: Path,
) -> None:
    """The dashboard does not manage projects, so it has to say so early.

    Only the marker's *presence* used to be checked. A project written
    by older tools therefore listed its devices as if all were well, and
    then refused every command touching one of them — the tree looked
    usable and was not. The version belongs to the same question the
    rest of the scan answers.
    """
    root = make_tree(tmp_path / "config", {"bench-node": VALID_CONFIG})
    # What a project created before the marker had contents looks like.
    (root / ".mcuhome-project-root").write_text("# older tools wrote this\n", encoding="utf-8")

    bus = EventBus()
    store = DeviceStore(root, bus, poll_interval=0.0)
    await store.start()

    assert store.available is False
    assert store.snapshot() == [], "a device nobody may touch is not a device to offer"
    assert store.problem == {
        "code": builder.PROBLEM_UPGRADE_REQUIRED,
        "project_version": 0,
        "expected_version": builder.PROJECT_VERSION,
    }


async def test_a_project_from_newer_tools_is_told_apart_from_an_old_one(
    tmp_path: Path,
) -> None:
    """Both directions are unusable; only one of them can be repaired here."""
    root = make_tree(tmp_path / "config")
    (root / ".mcuhome-project-root").write_text(
        f"version = {builder.PROJECT_VERSION + 1}\n", encoding="utf-8"
    )

    store = DeviceStore(root, EventBus(), poll_interval=0.0)
    await store.start()

    assert store.available is False
    assert store.problem is not None
    assert store.problem["code"] == builder.PROBLEM_VERSION_UNSUPPORTED


async def test_a_project_held_by_an_upgrade_is_left_alone(tmp_path: Path) -> None:
    """The marker is renamed for as long as an upgrade holds the project.

    Running or killed halfway looks the same from here, and both mean
    the same thing to a browser: nothing in there may be touched yet.
    """
    root = make_tree(tmp_path / "config", {"bench-node": VALID_CONFIG})
    (root / ".mcuhome-project-root").rename(root / ".mcuhome-project-root.upgrade")

    store = DeviceStore(root, EventBus(), poll_interval=0.0)
    await store.start()

    assert store.available is False
    assert store.snapshot() == []
    assert store.problem == {"code": builder.PROBLEM_UPGRADING}


async def test_an_upgrade_starting_mid_scan_is_not_read_as_a_missing_project(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """The two states swap under the scan, because an upgrade *renames*.

    ``is_upgrading`` is asked, says no, and the upgrade then renames the
    marker before ``is_project_root`` is asked — which now also says no.
    Answering from that pair alone reports "there is no project here",
    the one answer that sends somebody looking for a project that is
    merely busy. Here the rename is driven from the first call so the
    race is a fact of the test rather than a matter of timing.
    """
    root = make_tree(tmp_path / "config", {"bench-node": VALID_CONFIG})
    marker = root / ".mcuhome-project-root"
    real = builder.is_upgrading
    seen = 0

    def renaming_underneath(path: Path) -> bool:
        nonlocal seen
        seen += 1
        if seen == 1:
            # Answering "no", and making it untrue on the way out.
            marker.rename(root / ".mcuhome-project-root.upgrade")
            return False
        return real(path)

    monkeypatch.setattr(builder, "is_upgrading", renaming_underneath)

    store = DeviceStore(root, EventBus(), poll_interval=0.0)
    await store.start()

    assert store.available is False
    assert store.problem == {"code": builder.PROBLEM_UPGRADING}
    assert seen == 2, "the second question is the whole point"


async def test_a_directory_that_cannot_be_read_is_reported_not_raised(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A filesystem that answers with an error still has to reach the browser.

    On Python 3.13 ``Path.is_file`` re-raises what it cannot read past —
    a permission change, an unmounted share — and this runs in the poll
    loop's worker thread. An error escaping there is the one outcome
    that tells the user nothing at all. (Python 3.14 swallows the same
    errors, so a newer host cannot show this.)
    """
    root = make_tree(tmp_path / "config", {"bench-node": VALID_CONFIG})

    def denied(path: Path) -> bool:
        raise PermissionError(13, "Permission denied")

    monkeypatch.setattr(builder, "is_project_root", denied)

    store = DeviceStore(root, EventBus(), poll_interval=0.0)
    await store.start()

    assert store.available is False
    assert store.problem == {"code": builder.PROBLEM_UNREADABLE}
    assert store.snapshot() == []


async def test_the_reason_is_announced_when_it_changes_under_a_dead_tree(
    tmp_path: Path,
) -> None:
    """A tree can stay unusable while the reason changes underneath it.

    Announcing only on the availability flag would leave a browser
    telling the user to run an upgrade that is already running.
    """
    root = make_tree(tmp_path / "config")
    (root / ".mcuhome-project-root").write_text("# older tools wrote this\n", encoding="utf-8")

    bus = EventBus()
    store = DeviceStore(root, bus, poll_interval=0.0)
    subscription = bus.subscribe(TOPIC_DEVICES)
    await store.start()
    assert store.available is False

    (root / ".mcuhome-project-root").rename(root / ".mcuhome-project-root.upgrade")
    await store.refresh()

    events = drain(subscription)
    assert [name for name, _ in events] == ["tree_state"]
    assert events[0][1]["available"] is False
    assert events[0][1]["problem"] == {"code": builder.PROBLEM_UPGRADING}


async def test_no_tree_configured_at_all_is_reported_too() -> None:
    store = DeviceStore(None, EventBus(), poll_interval=0.0)
    await store.start()
    assert store.tree_state()["root"] is None
    assert store.entry_path("anything") is None


async def test_a_tree_appearing_later_announces_itself(tmp_path: Path) -> None:
    bus = EventBus()
    root = tmp_path / "config"
    store = DeviceStore(root, bus, poll_interval=0.0)
    subscription = bus.subscribe(TOPIC_DEVICES)
    await store.start()
    assert store.available is False

    make_tree(root, {"bench-node": VALID_CONFIG})
    await store.refresh()

    events = drain(subscription)
    assert [name for name, _ in events] == ["tree_state", "device_added"]
    assert events[0][1] == {"root": str(root), "available": True, "problem": None}


async def test_the_poll_task_starts_and_stops_cleanly(tree: Path) -> None:
    store = DeviceStore(tree, EventBus(), poll_interval=0.01)
    await store.start()
    await store.stop()
    # Stopping twice is not an error; shutdown paths run more than once.
    await store.stop()
