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
    }


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
    assert events[0][1] == {"root": str(root), "available": True}


async def test_the_poll_task_starts_and_stops_cleanly(tree: Path) -> None:
    store = DeviceStore(tree, EventBus(), poll_interval=0.01)
    await store.start()
    await store.stop()
    # Stopping twice is not an error; shutdown paths run more than once.
    await store.stop()
