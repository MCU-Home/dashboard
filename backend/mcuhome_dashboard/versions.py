# SPDX-FileCopyrightText: 2026 The MCUHome Contributors
# SPDX-License-Identifier: Apache-2.0
"""Version constants and the builder compatibility check (ADR 0011).

ADR 0011 decision 2: the dashboard declares the ``mcuhome`` versions it
supports as a **range**, not a pin, and refuses to start against
anything outside it *naming both versions*. The direction of the
dependency is the invariant — the builder never learns that the
dashboard exists — so the check lives here and nowhere else.

ADR 0007 decision 4 / ADR 0011 decision 3: the device-model wire format
is at ``model_version`` 2. The dashboard sends exactly one version and
advertises the range it can read; a build server that cannot meet it is
refused, never worked around.
"""

from __future__ import annotations

import re

from mcuhome_dashboard import __version__ as DASHBOARD_VERSION

__all__ = [
    "DASHBOARD_VERSION",
    "MCUHOME_PACKAGE",
    "MCUHOME_VERSION_MAX_EXCLUSIVE",
    "MCUHOME_VERSION_MIN",
    "MCUHOME_VERSION_SPEC",
    "MODEL_VERSION",
    "MODEL_VERSION_MAX",
    "MODEL_VERSION_MIN",
    "IncompatibleBuilderError",
    "check_mcuhome_version",
    "release_tuple",
]

#: The distribution the dashboard actually imports. Firmware ADR 0020
#: split the builder into three: ``mcuhome-model`` is the vocabulary,
#: ``mcuhome-workbench`` is stages 1-3 plus the build methods and
#: signing — this one, which brings the model with it — and
#: ``mcuhome-compiler`` is stages 4-5, which a dashboard does not carry
#: (ADR 0003, ADR 0017 §2). The plain name ``mcuhome`` is the *command
#: line's* distribution since decision 2 of that ADR, so naming it here
#: would state a dependency on a console script this package neither has
#: nor wants.
MCUHOME_PACKAGE = "mcuhome-workbench"

#: Lowest release of it this dashboard knows how to drive.
MCUHOME_VERSION_MIN = "0.1.0"
#: First release this dashboard does *not* claim to support.
MCUHOME_VERSION_MAX_EXCLUSIVE = "0.2.0"
#: The same range in the notation a dependency declaration uses. Real
#: pinning of this range into ``pyproject.toml`` arrives with release
#: tooling; until the builder is published, the dependency is a path.
MCUHOME_VERSION_SPEC = f"{MCUHOME_PACKAGE}>={MCUHOME_VERSION_MIN},<{MCUHOME_VERSION_MAX_EXCLUSIVE}"

#: The device-model version this dashboard sends (ADR 0007 decision 4).
MODEL_VERSION = 2
#: The range it can read back. Both ends equal today; they exist apart
#: so that widening the reader is not a protocol change.
MODEL_VERSION_MIN = 2
MODEL_VERSION_MAX = 2

_RELEASE_RE = re.compile(r"^\s*v?(\d+(?:\.\d+)*)")


class IncompatibleBuilderError(RuntimeError):
    """The installed ``mcuhome`` package is outside the supported range."""


def release_tuple(version: str) -> tuple[int, ...]:
    """The numeric release part of *version*, as a comparable tuple.

    Pre-release and development suffixes are dropped rather than
    ordered: ``0.1.0.dev0`` compares equal to ``0.1.0``. That is
    deliberately laxer than PEP 440 and is what lets a dashboard
    developed against ``0.1.0.dev0`` run against it. Once the builder
    publishes real releases this becomes an ordinary lower bound.
    """
    match = _RELEASE_RE.match(version)
    if match is None:
        return ()
    return tuple(int(part) for part in match.group(1).split("."))


def check_mcuhome_version(version: str) -> None:
    """Raise unless *version* is inside the supported range.

    The message names both versions, per ADR 0011 decision 2.
    """
    found = release_tuple(version)
    low = release_tuple(MCUHOME_VERSION_MIN)
    high = release_tuple(MCUHOME_VERSION_MAX_EXCLUSIVE)
    if low <= found < high:
        return
    raise IncompatibleBuilderError(
        f"MCUHome Dashboard {DASHBOARD_VERSION} supports the builder package "
        f"{MCUHOME_VERSION_SPEC}, but {MCUHOME_PACKAGE} {version} is installed. "
        "Install a matching builder, or a dashboard release that supports this one."
    )
