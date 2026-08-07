# SPDX-FileCopyrightText: 2026 The MCUHome Contributors
# SPDX-License-Identifier: Apache-2.0
"""Adapter over the ``mcuhome`` builder package (ADR 0011 decision 1).

The dashboard imports the builder and calls it — no subprocess, no CLI
output parsing, no exit codes. This module is the single place that
knows the builder's Python surface, so that when Block 0 gives the
builder a real machine-readable API the change lands here and nowhere
else.

Everything marked ``TODO(block-0)`` is a workaround for something ADR
0011 decision 4 says the *builder* should provide. They are workarounds
on purpose: none of them may grow into a second implementation of
builder behaviour on this side, because that would put the dashboard in
the business of knowing what a valid configuration is — a contract the
firmware repository owns (AGENTS.md).

Block 0 items this module is waiting for:

``ConfigError.to_dict()``
    Implemented here as :func:`error_to_dict`. The error objects already
    carry file, line, column, dotted key and hint; only the
    serialization is missing.

A supported entry point for "load, validate and resolve one device"
    Implemented here as :func:`load_model`, which calls
    ``mcuhome.cli.load_device_model`` — a function that lives in a CLI
    module and is therefore not a promise to anyone. Block 0's
    ``--json`` mode needs the same function; exposing it as public API
    (``mcuhome.api`` or an ``__init__`` re-export) serves both.

Not consumed yet, listed so Block 0 has the full set in one place:
``build-manifest.json`` (ADR 0006/0007/0010), ``mcuhome new <device>``
(the new-device wizard), registry and JSON-Schema export (board pickers,
editor autocomplete), detached signing (ADR 0007/0008), per-board
onboarding instructions (ADR 0010 rung 1). No stubs for those exist
here — a stub with no caller is dead code, and the list above is the
handover.
"""

from __future__ import annotations

import logging
from pathlib import Path
from typing import Any

from mcuhome import __version__ as MCUHOME_VERSION
from mcuhome.cli import load_device_model as _load_device_model
from mcuhome.errors import ConfigError, ConfigErrorGroup, MCUHomeError
from mcuhome.loader import load_yaml_file
from mcuhome.model import DeviceModel
from mcuhome.tree import DEVICE_ENTRY, DEVICES_DIR, ConfigTree, is_config_root, open_tree

__all__ = [
    "DEVICES_DIR",
    "DEVICE_ENTRY",
    "MCUHOME_VERSION",
    "ConfigTree",
    "device_summary",
    "error_to_dict",
    "errors_from_exception",
    "is_config_root",
    "load_model",
    "open_config_tree",
    "raw_summary",
]

logger = logging.getLogger(__name__)

#: Values a raw YAML summary is allowed to carry to the browser as-is.
#: Anything else — a ``!secret`` reference object, a ruamel node, a
#: nested structure — is reduced to ``None``, so that no accident turns
#: an unresolved secret into a JSON string.
_PLAIN_TYPES = (str, int, float, bool)


def open_config_tree(root: Path) -> ConfigTree:
    """Open *root* as a configuration tree, or raise ``ConfigError``."""
    return open_tree(root, cwd=root)


def _relative(path: Path | None, root: Path | None) -> str | None:
    """Render *path* relative to the tree root when it is inside it.

    The editor addresses files by their tree-relative path, and an
    absolute server-side path is both useless to it and more than the
    browser needs to know.
    """
    if path is None:
        return None
    if root is not None:
        try:
            return str(Path(path).resolve().relative_to(root.resolve()))
        except (ValueError, OSError):
            pass
    return str(path)


def error_to_dict(error: ConfigError, *, root: Path | None = None) -> dict[str, Any]:
    """Serialize one builder error for the editor's gutter.

    TODO(block-0): this is ``ConfigError.to_dict()``, which belongs on
    the error class in the firmware repository (ADR 0011 decision 4).
    The field names chosen here are the ones the editor wants and are
    the proposal for that method's output.
    """
    location = error.location
    return {
        "message": error.message,
        "file": _relative(location.file, root),
        "line": location.line,
        "column": location.column,
        "key": location.key,
        "hint": error.hint,
        "kind": type(error).__name__,
    }


def errors_from_exception(exc: MCUHomeError, *, root: Path | None = None) -> list[dict[str, Any]]:
    """Flatten whatever the builder raised into a list of diagnostics.

    ``validate`` deliberately reports every problem it found rather than
    the first (``ConfigErrorGroup``), and that is the whole point of
    showing them in an editor: one pass, all markers.
    """
    if isinstance(exc, ConfigErrorGroup):
        return [error_to_dict(error, root=root) for error in exc.errors]
    if isinstance(exc, ConfigError):
        return [error_to_dict(exc, root=root)]
    # A builder error without a location (a BuildError about a missing
    # tool, say). Still a user-facing message, still not a traceback.
    return [
        {
            "message": str(exc),
            "file": None,
            "line": None,
            "column": None,
            "key": None,
            "hint": None,
            "kind": type(exc).__name__,
        }
    ]


def load_model(entry: Path, *, tree: ConfigTree) -> DeviceModel:
    """Run the builder's stages 1-3 on one device configuration.

    Blocking and CPU-bound-ish (YAML parsing); call it in an executor,
    never on the event loop (ADR 0004).

    TODO(block-0): ``mcuhome.cli.load_device_model`` is a CLI-internal
    helper. Block 0 should promote this exact operation to public API
    alongside the ``--json`` mode that needs it too.
    """
    return _load_device_model(entry, tree=tree)


def device_summary(model: DeviceModel) -> dict[str, Any]:
    """What a device list entry and a validation result show.

    Deliberately **not** ``model.to_dict()``: the resolved model carries
    the device's Matter commissioning credentials (``network.pairing``),
    which belong in the build request (ADR 0007) and in a commissioning
    view built for the purpose — not in every list response that a
    browser tab happens to hold open.
    """
    endpoints = [
        {
            "id": endpoint.id,
            "alias": endpoint.alias,
            "device_types": [dt.name for dt in endpoint.device_types],
            "cluster_count": len(endpoint.clusters),
        }
        for endpoint in model.endpoints
    ]
    return {
        "model_version": model.model_version,
        "name": model.device.name,
        "friendly_name": model.device.friendly_name,
        "board": model.device.board,
        "power_source": model.device.power_source,
        "transport": model.network.transport,
        "matter_enabled": model.network.matter_enabled,
        "thread_role": model.network.thread.device_role if model.network.thread else None,
        "zephyr_line": model.toolchain.zephyr_line,
        "peripherals": [
            {"id": peripheral.id, "compatible": peripheral.compatible, "bus": peripheral.bus}
            for peripheral in model.hardware.peripherals
        ],
        "endpoints": endpoints,
    }


def _plain(value: Any) -> Any:
    return value if isinstance(value, _PLAIN_TYPES) else None


def raw_summary(entry: Path) -> dict[str, Any]:
    """A cheap summary of an *unresolved* configuration file.

    Used by ``device/get`` so that a configuration which does not
    validate still shows something useful in a list. It parses YAML and
    stops there: ``!secret`` references stay :class:`SecretRef` objects
    and are reduced to ``None`` by :func:`_plain`, so nothing here can
    resolve a secret by accident.

    Returns ``{}`` when the file cannot be parsed at all — the caller
    already has the raw text, and ``device/validate`` is where a parse
    failure gets reported properly.
    """
    try:
        data = load_yaml_file(entry)
    except MCUHomeError:
        return {}
    if not isinstance(data, dict):
        return {}

    device = data.get("device")
    device = device if isinstance(device, dict) else {}
    node = data.get("node")
    node = node if isinstance(node, dict) else {}
    endpoints = node.get("endpoints")

    return {
        "sections": sorted(str(key) for key in data),
        "name": _plain(device.get("name")),
        "friendly_name": _plain(device.get("friendly_name")),
        "board": _plain(device.get("board")),
        "endpoint_count": len(endpoints) if isinstance(endpoints, list) else 0,
    }
