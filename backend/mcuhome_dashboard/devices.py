# SPDX-FileCopyrightText: 2026 The MCUHome Contributors
# SPDX-License-Identifier: Apache-2.0
"""The device list, kept current against the configuration tree.

The tree is a directory of YAML files (ADR 0008) that the dashboard is
not the only writer of: Studio Code Server, a git checkout and a text
editor on a mounted share all edit it behind the dashboard's back. So
the list is a *watched* resource, published as snapshot-then-events
(ADR 0004 decision 3), never re-fetched on a timer by the client.

**Polling, not inotify.** The tree lives on whatever the deployment
mounts — a bind mount, an SD card, an SMB share — and inotify is exactly
the mechanism that does not survive that variety. A poll of a few dozen
`stat` calls every couple of seconds is cheap, portable and has no
failure mode worth handling.

**Two levels, so the poll stays cheap and the events stay honest.** The
poll compares modification times and sizes; only a device whose stamps
moved is read and hashed. Only a device whose *content hash* actually
changed produces a ``device_changed`` event. That is what keeps a
``touch``, a checkout that rewrites identical files, or an editor's
save-with-no-edit from redrawing every open browser tab.
"""

from __future__ import annotations

import asyncio
import contextlib
import hashlib
import logging
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from mcuhome_dashboard import builder, events
from mcuhome_dashboard.events import EventBus

__all__ = ["DeviceEntry", "DeviceStore"]

logger = logging.getLogger(__name__)

#: File suffixes that make up a device's content hash.
CONFIG_SUFFIXES = (".yaml", ".yml")

#: Per-file stamp used by the cheap half of the poll.
_Stamp = tuple[str, int, int]


@dataclass(frozen=True)
class DeviceEntry:
    """One device folder, as the dashboard sees it without validating it."""

    name: str
    #: Path of ``main.yaml``, relative to the configuration tree root.
    entry: str
    #: sha256 over every YAML file in the device folder, path-ordered.
    content_hash: str
    modified: float
    size: int
    #: Cheap, unresolved summary — see :func:`builder.raw_summary`.
    summary: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "entry": self.entry,
            "content_hash": self.content_hash,
            "modified": self.modified,
            "size": self.size,
            "summary": dict(self.summary),
        }


@dataclass(frozen=True)
class _Scan:
    """One device folder's file stamps, taken without reading content."""

    entry: Path
    stamps: tuple[_Stamp, ...]
    modified: float
    size: int


class DeviceStore:
    """Scans the configuration tree and publishes what changed.

    Safe to construct without a tree: ``config_root`` may be ``None`` (no
    tree configured yet) or point at a directory that does not exist yet.
    Both are reported as "not available" rather than refused, so that a
    fresh installation starts and can be pointed at a tree from the UI.
    """

    def __init__(
        self,
        config_root: Path | None,
        bus: EventBus,
        *,
        poll_interval: float = 2.0,
    ) -> None:
        self._root = config_root
        self._bus = bus
        self._poll_interval = poll_interval
        self._entries: dict[str, DeviceEntry] = {}
        self._scans: dict[str, _Scan] = {}
        self._available = False
        self._task: asyncio.Task[None] | None = None
        self._lock = asyncio.Lock()

    # -- state ------------------------------------------------------

    @property
    def root(self) -> Path | None:
        return self._root

    @property
    def available(self) -> bool:
        """True when the root exists and looks like a configuration tree."""
        return self._available

    def snapshot(self) -> list[DeviceEntry]:
        return [self._entries[name] for name in sorted(self._entries)]

    def get(self, name: str) -> DeviceEntry | None:
        return self._entries.get(name)

    def entry_path(self, name: str) -> Path | None:
        """Absolute path of a device's ``main.yaml``, if the device exists."""
        entry = self._entries.get(name)
        if entry is None or self._root is None:
            return None
        return self._root / entry.entry

    def tree_state(self) -> dict[str, Any]:
        return {
            "root": str(self._root) if self._root else None,
            "available": self._available,
            "device_count": len(self._entries),
        }

    # -- lifecycle --------------------------------------------------

    async def start(self) -> None:
        """Take the first scan, then poll until :meth:`stop`."""
        await self.refresh(announce=False)
        if self._poll_interval > 0:
            self._task = asyncio.create_task(self._poll(), name="mcuhome-dashboard-tree-poll")

    async def stop(self) -> None:
        if self._task is None:
            return
        self._task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await self._task
        self._task = None

    async def _poll(self) -> None:
        while True:
            await asyncio.sleep(self._poll_interval)
            try:
                await self.refresh()
            except asyncio.CancelledError:
                raise
            except Exception:  # pragma: no cover - defensive
                logger.exception("configuration tree poll failed; continuing")

    # -- scanning ---------------------------------------------------

    async def refresh(self, *, announce: bool = True) -> bool:
        """Re-scan the tree. Returns True when anything changed.

        The filesystem work happens in a thread: a tree on a sleeping
        SD card must not stall the event loop that is streaming a build
        log at the same time (ADR 0004).
        """
        async with self._lock:
            was_available = self._available
            available, scans = await asyncio.to_thread(self._scan_stamps)
            entries, changed_names, new_names = await asyncio.to_thread(self._materialize, scans)

            removed = sorted(set(self._entries) - set(entries))
            self._entries = entries
            self._scans = scans
            self._available = available

            if not announce:
                return bool(changed_names or new_names or removed)

            if available != was_available:
                self._bus.publish(
                    events.tree_state(str(self._root) if self._root else None, available=available)
                )
            for name in removed:
                self._bus.publish(events.device_removed(name))
            for name in sorted(new_names):
                self._bus.publish(events.device_added(entries[name].to_dict()))
            for name in sorted(changed_names):
                self._bus.publish(events.device_changed(entries[name].to_dict()))
            return bool(changed_names or new_names or removed)

    def _scan_stamps(self) -> tuple[bool, dict[str, _Scan]]:
        """The cheap half: which device folders exist, and their stamps."""
        root = self._root
        if root is None or not root.is_dir() or not builder.is_project_root(root):
            return False, {}

        devices_dir = root / builder.DEVICES_DIR
        if not devices_dir.is_dir():
            # A project root with no devices/ is a valid, empty project.
            return True, {}

        scans: dict[str, _Scan] = {}
        try:
            candidates = sorted(devices_dir.iterdir())
        except OSError as exc:
            logger.warning("cannot read %s: %s", devices_dir, exc)
            return False, {}

        for folder in candidates:
            entry = folder / builder.DEVICE_ENTRY
            if not folder.is_dir() or not entry.is_file():
                continue
            scan = self._stamp_folder(folder, entry)
            if scan is not None:
                scans[folder.name] = scan
        return True, scans

    def _stamp_folder(self, folder: Path, entry: Path) -> _Scan | None:
        stamps: list[_Stamp] = []
        try:
            for path in sorted(folder.rglob("*")):
                if not path.is_file() or path.suffix.lower() not in CONFIG_SUFFIXES:
                    continue
                stat = path.stat()
                stamps.append((str(path.relative_to(folder)), stat.st_mtime_ns, stat.st_size))
            entry_stat = entry.stat()
        except OSError as exc:
            logger.warning("cannot read device folder %s: %s", folder, exc)
            return None
        return _Scan(
            entry=entry,
            stamps=tuple(stamps),
            modified=entry_stat.st_mtime,
            size=entry_stat.st_size,
        )

    def _materialize(
        self, scans: dict[str, _Scan]
    ) -> tuple[dict[str, DeviceEntry], set[str], set[str]]:
        """The expensive half, run only where the stamps moved."""
        entries: dict[str, DeviceEntry] = {}
        changed: set[str] = set()
        added: set[str] = set()

        for name, scan in scans.items():
            previous = self._entries.get(name)
            previous_scan = self._scans.get(name)
            if (
                previous is not None
                and previous_scan is not None
                and previous_scan.stamps == scan.stamps
            ):
                entries[name] = previous
                continue

            content_hash = self._hash_folder(scan)
            if previous is not None and previous.content_hash == content_hash:
                # Stamps moved, bytes did not: a touch, or a checkout
                # that rewrote identical files. Not an event.
                entries[name] = previous
                continue

            entry = DeviceEntry(
                name=name,
                entry=str(scan.entry.relative_to(self._root)) if self._root else scan.entry.name,
                content_hash=content_hash,
                modified=scan.modified,
                size=scan.size,
                summary=builder.raw_summary(scan.entry),
            )
            entries[name] = entry
            (added if previous is None else changed).add(name)

        return entries, changed, added

    @staticmethod
    def _hash_folder(scan: _Scan) -> str:
        digest = hashlib.sha256()
        folder = scan.entry.parent
        for relative, _mtime, _size in scan.stamps:
            digest.update(relative.encode("utf-8"))
            digest.update(b"\0")
            try:
                digest.update((folder / relative).read_bytes())
            except OSError as exc:  # pragma: no cover - raced deletion
                logger.warning("cannot read %s: %s", folder / relative, exc)
                digest.update(b"<unreadable>")
            digest.update(b"\0")
        return digest.hexdigest()
