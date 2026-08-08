# SPDX-FileCopyrightText: 2026 The MCUHome Contributors
# SPDX-License-Identifier: Apache-2.0
"""Adapter over the ``mcuhome`` builder package (ADR 0011 decision 1).

The dashboard imports the builder and calls it — no subprocess, no CLI
output parsing, no exit codes. This module is the single place that
knows the builder's Python surface, so that a change on the builder's
side lands here and nowhere else.

**Everything below goes through** :mod:`mcuhome.api`, which Block 0 made
the builder's supported programmatic surface: tree discovery, stages 1-3,
the typed errors and their ``to_dict``. Nothing here re-implements
builder behaviour, because that would put the dashboard in the business
of knowing what a valid configuration is — a contract the firmware
repository owns (AGENTS.md).

Two functions still reach past ``mcuhome.api`` into the package, both
because the operation they need has no public form yet and neither is
worth a second implementation on this side:

:func:`commissioning_codes`
    uses ``mcuhome.pairing.Pairing`` to derive the QR payload and the
    manual code from the model's pairing tuple. It is the same
    computation ``mcuhome validate`` prints, and deriving it here a
    second time is exactly what must not happen.

:func:`raw_summary`
    uses ``mcuhome.loader.load_yaml_file`` to read a configuration that
    does *not* resolve, which no ``api`` entry point offers — every one
    of them runs the whole of stages 1-3.

Both are candidates for ``mcuhome.api``; until then they are the two
imports in this file that a builder release may break.
"""

from __future__ import annotations

import logging
from pathlib import Path
from typing import Any

from mcuhome import api
from mcuhome.api import (
    DEVICE_ENTRY,
    DEVICES_DIR,
    ConfigTree,
    DeviceModel,
    MCUHomeError,
    error_dicts,
    is_config_root,
)
from mcuhome.loader import load_yaml_file
from mcuhome.pairing import Pairing

__all__ = [
    "DEVICES_DIR",
    "DEVICE_ENTRY",
    "MCUHOME_VERSION",
    "ConfigTree",
    "commissioning_codes",
    "device_summary",
    "errors_from_exception",
    "is_config_root",
    "load_model",
    "open_config_tree",
    "raw_summary",
]

logger = logging.getLogger(__name__)

#: The installed builder's version, re-exported so that the rest of the
#: dashboard never imports ``mcuhome`` itself (ADR 0011 decision 2 checks
#: it against the range in :mod:`mcuhome_dashboard.versions`).
MCUHOME_VERSION = api.VERSION

#: Values a raw YAML summary is allowed to carry to the browser as-is.
#: Anything else — a ``!secret`` reference object, a ruamel node, a
#: nested structure — is reduced to ``None``, so that no accident turns
#: an unresolved secret into a JSON string.
_PLAIN_TYPES = (str, int, float, bool)


def open_config_tree(root: Path) -> ConfigTree:
    """Open *root* as a configuration tree, or raise ``ConfigError``."""
    return api.open_config_tree(root, cwd=root)


def errors_from_exception(exc: MCUHomeError, *, root: Path | None = None) -> list[dict[str, Any]]:
    """Flatten whatever the builder raised into a list of diagnostics.

    ``validate`` deliberately reports every problem it found rather than
    the first (``ConfigErrorGroup``), and that is the whole point of
    showing them in an editor: one pass, all markers. The serialization
    is the builder's own ``ConfigError.to_dict`` — message, tree-relative
    file, line, column, dotted key, hint and kind — so the editor's
    gutter and ``mcuhome validate --json`` say the same thing about the
    same configuration.
    """
    return error_dicts(exc, root=root)


def load_model(entry: Path, *, tree: ConfigTree) -> DeviceModel:
    """Run the builder's stages 1-3 on one device configuration.

    Blocking and CPU-bound-ish (YAML parsing); call it in an executor,
    never on the event loop (ADR 0004).
    """
    return api.load_model(entry, tree=tree)


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


def commissioning_codes(model: DeviceModel) -> dict[str, Any] | None:
    """The codes a human needs to add this device to a controller.

    ``None`` when the configuration has no Matter pairing tuple — a
    device with Matter switched off has nothing to commission.

    This is the "commissioning view built for the purpose" that
    :func:`device_summary` refers to when it explains why the pairing
    tuple is not in the summary. The distinction is not that these
    strings are less sensitive: **the QR payload contains the passcode**,
    and so does the manual code. It is that they only travel when a user
    asked for them by hand, instead of riding along on every list
    response that any open browser tab happens to hold.

    What is returned is exactly what ``mcuhome validate`` already prints
    (``mcuhome.cli.format_commissioning``) — the same two codes and the
    same discriminator, so the dashboard and the CLI never disagree about
    what a device's commissioning data is. The SPAKE2+ salt and iteration
    count are inputs to the verifier and are deliberately *not* here:
    nothing a human does with a controller needs them.
    """
    credentials = model.network.pairing
    if credentials is None:
        return None
    codes = Pairing(
        discriminator=credentials.discriminator,
        passcode=credentials.passcode,
        salt=credentials.salt,
        iterations=credentials.iterations,
        test_credentials=credentials.test_credentials,
    )
    return {
        "qr_payload": codes.qr_payload,
        "manual_code": codes.manual_code,
        "discriminator": credentials.discriminator,
        "test_credentials": credentials.test_credentials,
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
