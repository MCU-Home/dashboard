# SPDX-FileCopyrightText: 2026 The MCUHome Contributors
# SPDX-License-Identifier: Apache-2.0
"""Adapter over the ``mcuhome`` builder package (ADR 0011 decision 1).

The dashboard imports the builder and calls it — no subprocess, no CLI
output parsing, no exit codes. This module is the single place that
knows the builder's Python surface, so that a change on the builder's
side lands here and nowhere else.

**Everything below goes through** :mod:`mcuhome.workbench.api`, which
Block 0 made the builder's supported programmatic surface: tree
discovery, stages 1-3, the typed errors and their ``to_dict``, and —
since firmware E64 — the three build methods behind one awaitable
:func:`run_build`. Nothing here re-implements builder behaviour, because
that would put the dashboard in the business of knowing what a valid
configuration is, or how a container is started — contracts the firmware
repository owns (AGENTS.md).

**The build seam is here and nowhere else.** ADR 0013 makes the
dashboard a caller of ``run_build``, and it calls it through the names
re-exported below, so the whole of the dashboard's knowledge of how a
build is started is this one file. Which *method* runs — a container on
this machine, a build server, the caller's own workspace — is
configuration (:class:`~mcuhome_dashboard.config.Config.build_method`)
and not something this module or its callers branch on: that is the
point of ADR 0013 decision 1, and it is only true because the request
object is the same for all three.

Three functions still reach past ``mcuhome.workbench.api`` into the
package, each because the operation it needs has no public form yet and
none is worth a second implementation on this side:

:func:`commissioning_codes`
    uses ``mcuhome.model.pairing.Pairing`` to derive the QR payload and
    the manual code from the model's pairing tuple. It is the same
    computation ``mcuhome validate`` prints, and deriving it here a
    second time is exactly what must not happen.

:func:`raw_summary`
    uses ``mcuhome.workbench.loader.load_yaml_file`` to read a
    configuration that does *not* resolve, which no ``api`` entry point
    offers — every one of them runs the whole of stages 1-3.

:func:`write_ota_image`
    uses ``mcuhome.model.manifest.ota_parameters`` and
    ``mcuhome.workbench.otafile`` to wrap a signed image in a Matter OTA
    file. The vendor/product identity and the SoftwareVersion derivation
    are the firmware repository's (its ``ota.py`` is asserted to be the
    only writer of them), so this is a call and not a copy.

All three are candidates for ``mcuhome.workbench.api``; until then they
are the imports in this file that a builder release may break.
"""

from __future__ import annotations

import logging
from pathlib import Path
from typing import Any

from mcuhome.model.manifest import ota_parameters
from mcuhome.model.pairing import Pairing
from mcuhome.workbench import api, otafile
from mcuhome.workbench.api import (
    DEFAULT_METHOD,
    DEVICE_ENTRY,
    DEVICES_DIR,
    LOCAL,
    LOCAL_DEV,
    METHODS,
    PROJECT_VERSION,
    REMOTE,
    BuildDirectoryBusy,
    BuildOutcome,
    BuildRequest,
    DeviceModel,
    MCUHomeError,
    Project,
    build_lock,
    error_dicts,
    is_project_root,
    is_upgrading,
    resolve_method,
    resolve_project,
    run_build,
)
from mcuhome.workbench.loader import load_yaml_file

__all__ = [
    "BUILD_METHODS",
    "DEFAULT_BUILD_METHOD",
    "DEVICES_DIR",
    "DEVICE_ENTRY",
    "LOCAL",
    "LOCAL_DEV",
    "MCUHOME_VERSION",
    "PROBLEM_NO_PROJECT",
    "PROBLEM_UNREADABLE",
    "PROBLEM_UPGRADE_REQUIRED",
    "PROBLEM_UPGRADING",
    "PROBLEM_VERSION_UNSUPPORTED",
    "PROJECT_VERSION",
    "PUBLIC_FACTS",
    "REMOTE",
    "STEP_ARTIFACTS",
    "STEP_COMPILE",
    "STEP_CONTEXT",
    "STEP_SIGN",
    "STEP_VALIDATE",
    "BuildDirectoryBusy",
    "BuildOutcome",
    "BuildRequest",
    "DeviceModel",
    "MCUHomeError",
    "Project",
    "build_lock",
    "commissioning_codes",
    "device_summary",
    "errors_from_exception",
    "is_project_root",
    "is_upgrading",
    "load_model",
    "open_config_tree",
    "project_problem",
    "public_facts",
    "raw_summary",
    "resolve_method",
    "resolve_project",
    "run_build",
    "steps_for_method",
    "validate_facts",
    "write_ota_image",
]

logger = logging.getLogger(__name__)

#: Every build method the installed builder has (ADR 0020 decision 6).
#: The dashboard does not subset it — ADR 0013 decision 1: the package
#: abstracts *where* a build runs, and a dashboard that re-decided that
#: would be re-deciding it worse and in a second place.
BUILD_METHODS = METHODS

#: What a deployment that expressed no preference gets: the builder's own
#: default, not a copy of it. Firmware E54 makes that ``local``; if it
#: moves, this moves with it, which is the whole reason it is not a
#: literal here.
DEFAULT_BUILD_METHOD = DEFAULT_METHOD

#: The installed builder's version, re-exported so that the rest of the
#: dashboard never imports ``mcuhome`` itself (ADR 0011 decision 2 checks
#: it against the range in :mod:`mcuhome_dashboard.versions`).
MCUHOME_VERSION = api.VERSION

#: Values a raw YAML summary is allowed to carry to the browser as-is.
#: Anything else — a ``!secret`` reference object, a ruamel node, a
#: nested structure — is reduced to ``None``, so that no accident turns
#: an unresolved secret into a JSON string.
_PLAIN_TYPES = (str, int, float, bool)


# --------------------------------------------------------------------------
# Build progress: the steps a build passes through, and what they establish
# --------------------------------------------------------------------------

#: The steps of a build, in the order they happen.
#:
#: Two of them are the **builder's** vocabulary: ``BuildRequest.on_step``
#: is its honest-progress seam, and ``run_build`` announces ``context``
#: and ``compile`` from inside itself. The other three are this
#: dashboard's own, for the work it does around that one call. Keys are
#: append-only on both sides — a consumer renders the ones it knows.
STEP_VALIDATE = "validate"
STEP_CONTEXT = "context"
STEP_COMPILE = "compile"
STEP_ARTIFACTS = "artifacts"
STEP_SIGN = "sign"

_WITH_CONTEXT = (STEP_VALIDATE, STEP_CONTEXT, STEP_COMPILE, STEP_ARTIFACTS, STEP_SIGN)
#: ``local-dev`` compiles in a west workspace and builds no build
#: context, so it announces no ``context`` step and none is claimed for
#: it. The other two methods hand a context to a build environment,
#: which is the step whose facts say which SDK the firmware comes from.
_WITHOUT_CONTEXT = (STEP_VALIDATE, STEP_COMPILE, STEP_ARTIFACTS, STEP_SIGN)

_METHOD_STEPS = {
    LOCAL: _WITH_CONTEXT,
    REMOTE: _WITH_CONTEXT,
    LOCAL_DEV: _WITHOUT_CONTEXT,
}

#: The facts this dashboard forwards to browsers, per step.
#:
#: An **allowlist**, and that is the whole design. The fact vocabulary
#: belongs to the builder and is append-only by construction, so a filter
#: of known-bad keys would publish whatever a later release adds to every
#: open browser tab without anyone deciding it may travel. One key is
#: excluded today for exactly that reason: ``compile`` carries ``server``
#: on the remote method — the build server's address — and this
#: deployment publishes only *whether* one is configured
#: (``server/info``), never where it is.
#:
#: What is left out is otherwise noise rather than danger: ``sdk_sha256``
#: and the image ``digest`` are pins nobody reads off a screen, and the
#: build report is where they belong.
PUBLIC_FACTS: dict[str, frozenset[str]] = {
    STEP_VALIDATE: frozenset(
        {"board", "transport", "thread_role", "matter", "endpoints", "channels"}
    ),
    STEP_CONTEXT: frozenset({"sdk", "zephyr", "patches", "files", "id"}),
    STEP_COMPILE: frozenset({"image", "jobs"}),
}


def steps_for_method(method: str) -> tuple[str, ...]:
    """The steps a build with *method* is expected to pass through.

    A **prediction**, and stated as one: a person watching a build wants
    to know how far along it is, which needs the steps still to come and
    not only the one running. A method this dashboard has never heard of
    gets the longer list — being one step optimistic about an unknown
    method is better than showing nothing — and a step that is announced
    without being on the list is added where it happened rather than
    dropped, so a builder that grows one shows it late instead of never
    (:class:`mcuhome_dashboard.builds._Progress`).
    """
    return _METHOD_STEPS.get(method, _WITH_CONTEXT)


def public_facts(step: str, facts: dict[str, Any]) -> dict[str, Any]:
    """The part of *facts* that may reach a browser, for *step*.

    Two filters, both of them load-bearing. :data:`PUBLIC_FACTS` decides
    *which* keys travel, for the reasons written there. The value check
    decides what a key may hold: these dicts are serialized into a
    WebSocket frame, and a builder release that made one of them an
    object would turn a progress update into a failed publish in the
    middle of a build. A fact is display material — it is never worth
    that.
    """
    allowed = PUBLIC_FACTS.get(step)
    if not allowed:
        return {}
    return {key: value for key, value in facts.items() if key in allowed and _serializable(value)}


def _serializable(value: Any) -> bool:
    """Whether *value* is plain enough to put in a frame unexamined."""
    if isinstance(value, _PLAIN_TYPES) or value is None:
        return True
    return isinstance(value, list | tuple) and all(isinstance(item, _PLAIN_TYPES) for item in value)


def validate_facts(model: DeviceModel) -> dict[str, Any]:
    """What resolving this configuration established, as data.

    The dashboard's own contribution to the fact vocabulary: stages 1-3
    run here, before any build method is called, and what they settled —
    the board, how the device talks, whether it is a Matter device at
    all, how much of it there is — is the same set ``mcuhome build``
    states in one line above its step bar.

    Data and not a sentence, for the reason the whole
    :func:`project_problem` payload is: two sites, one event bus, and the
    browser is where the wording happens.
    """
    thread = model.network.thread
    return {
        "board": model.device.board,
        "transport": model.network.transport,
        "thread_role": thread.device_role if thread is not None else None,
        "matter": model.network.matter_enabled,
        "endpoints": len(model.endpoints),
        "channels": len(model.channels),
    }


def open_config_tree(root: Path) -> Project:
    """Open *root* as a project, or raise ``ConfigError``."""
    return resolve_project(explicit=root, env={}, cwd=root)


#: Why a directory is not a usable project. These travel to the browser
#: as codes, never as sentences — see :func:`project_problem`.
PROBLEM_NO_PROJECT = "no_project"
PROBLEM_UPGRADE_REQUIRED = "project_upgrade_required"
PROBLEM_VERSION_UNSUPPORTED = "project_version_unsupported"
PROBLEM_UPGRADING = "project_upgrading"
PROBLEM_UNREADABLE = "project_file_unreadable"


def project_problem(root: Path | None) -> dict[str, Any] | None:
    """What stands between *root* and being a usable project, or ``None``.

    The dashboard does not manage projects: it is pointed at one that
    already exists, and creating or upgrading it is somebody else's job.
    What it owes the user is to say so *before* they start working,
    rather than listing devices from a project every command will then
    refuse — which is what happened while only the marker's presence was
    checked and not the version it states.

    The answer is **facts, never a sentence**. Both sites publish onto
    one event bus, and the sentence differs between them: in a standalone
    deployment a person can install the command line and run the upgrade,
    while in the Home Assistant App the container is what keeps the
    project current and a user seeing this at all means something went
    wrong upstream of them. The browser knows which site answered
    (``server/info`` reports it) and does the wording.
    """
    # Every filesystem question below is asked inside the guard, not
    # before it. On Python 3.13 ``Path.is_file`` re-raises anything it
    # cannot read past — a directory whose permissions changed, an
    # unmounted network share — and this runs in the poll loop's worker
    # thread, where an escaping error means the browser is told nothing
    # at all rather than told what is wrong. (Python 3.14 swallows the
    # same errors, so the difference is invisible on a newer host and
    # not on the one this ships with.)
    try:
        if root is None or not root.is_dir():
            return {"code": PROBLEM_NO_PROJECT}
        if is_upgrading(root):
            # The marker is renamed for exactly as long as an upgrade
            # holds the project, so this is either one in flight or one
            # that was killed halfway. Both mean: nothing here may be
            # touched yet.
            return {"code": PROBLEM_UPGRADING}
        if not is_project_root(root):
            # Asked a second time, and this is the order that matters: an
            # upgrade *renames* the marker, so the two states swap under
            # a scan that is already looking at one of them. Without this,
            # an upgrade starting between the two questions reads as
            # "there is no project here" — the one answer that sends a
            # user looking for a project that is merely busy.
            if is_upgrading(root):
                return {"code": PROBLEM_UPGRADING}
            return {"code": PROBLEM_NO_PROJECT}
        # The one call that reads the marker without judging it: the
        # version check is what this function is *for*, so it must not
        # happen inside the resolution and arrive as an exception.
        file = resolve_project(explicit=root, env={}, cwd=root, require_version=False).file
    except (MCUHomeError, OSError):
        return {"code": PROBLEM_UNREADABLE}
    if file is None:  # pragma: no cover - the marker is there, so is the file
        return {"code": PROBLEM_UNREADABLE}
    if file.current:
        return None
    code = (
        PROBLEM_UPGRADE_REQUIRED if file.version < PROJECT_VERSION else PROBLEM_VERSION_UNSUPPORTED
    )
    return {
        "code": code,
        "project_version": file.version,
        "expected_version": PROJECT_VERSION,
    }


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


def load_model(entry: Path, *, tree: Project) -> DeviceModel:
    """Run the builder's stages 1-3 on one device configuration.

    Blocking and CPU-bound-ish (YAML parsing); call it in an executor,
    never on the event loop (ADR 0004).
    """
    return api.load_model(entry, project=tree)


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


def write_ota_image(model: DeviceModel, *, out_dir: Path, signed: Path) -> dict[str, Any] | None:
    """Wrap the signed image in a Matter OTA file, when this device can take one.

    ``None`` — silently, and correctly — for a board whose update scheme
    has no staging slot and for a device without a Matter stack: neither
    can be updated over the air, so an ``.ota`` file for it would be a
    file nothing can deliver. The same two facts
    ``mcuhome.model.manifest.ota_parameters`` checks, checked by calling
    it rather than by knowing them here.

    *signed* has to be the **signed** binary. An unsigned one produces a
    valid ``.ota`` that a device downloads, stages, reboots into and then
    rejects at the bootloader, because MCUboot's signature is the only
    trust anchor in that path (ADR 0015 decision 6). That is why this is
    called after the signing step and never beside it.

    Blocking (it hashes the image); call it in an executor.
    """
    parameters = ota_parameters(model)
    if parameters is None or not signed.is_file():
        return None
    image = otafile.write_ota_image(
        payload=signed,
        output=out_dir / otafile.ota_file_name(model.device.name, parameters.version),
        vendor_id=parameters.vendor_id,
        product_id=parameters.product_id,
        version=parameters.version,
    )
    return {
        "path": image.path.name,
        "version": image.version,
        "software_version": image.software_version,
    }
