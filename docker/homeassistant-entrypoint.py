# SPDX-FileCopyrightText: 2026 The MCUHome Contributors
# SPDX-License-Identifier: Apache-2.0
"""Start the dashboard as a Home Assistant App.

The dashboard opens a project; it does not manage one. In every other
deployment that is a person's job — they run the command line. In this
one there is nobody at a terminal, so the *container* does it: it creates
the project on first start and brings an outdated one forward before the
server is up. Doing it here rather than in the server keeps the rule
intact, because what runs the migrations is packaging and not the
dashboard.

Everything this file decides is fixed by the App's ``config.yaml``: the
project is the App's own configuration directory, the private volume
holds the signing key and build output, and the only way in is Home
Assistant's ingress. So the server is started with those and with
nothing configurable in between — the one option the App offers, the log
level, is read from the Supervisor's options file.
"""

from __future__ import annotations

import json
import logging
import os
import sys
from pathlib import Path
from typing import Any

from mcuhome.workbench import api

from mcuhome.ui import builder
from mcuhome.ui.builder import MCUHomeError

#: `addon_config`, mapped read-write by config.yaml. On the host this is
#: /addon_configs/<id>_mcuhome-ui, which is what the documentation names.
CONFIG_ROOT = Path("/config")

#: The App's private volume: the firmware signing key and build output.
DATA_DIR = Path("/data")

#: Where the Supervisor writes the App's options before starting it.
OPTIONS_FILE = DATA_DIR / "options.json"

#: Must equal `ingress_port` in config.yaml.
INGRESS_PORT = 8099

LOG_LEVELS = {"debug": "DEBUG", "info": "INFO", "warning": "WARNING", "error": "ERROR"}

logger = logging.getLogger("mcuhome.app")


def read_options() -> dict[str, Any]:
    """The App's options, or an empty set if they cannot be read.

    A missing or broken options file is not worth refusing to start over:
    every option here has a default, and an App that will not come up is
    a worse answer than one that comes up with its log level unchanged.
    """
    try:
        data = json.loads(OPTIONS_FILE.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return {}
    except (OSError, ValueError) as error:
        logger.warning("could not read %s (%s); continuing with defaults", OPTIONS_FILE, error)
        return {}
    return data if isinstance(data, dict) else {}


def prepare_project() -> None:
    """Make the configuration directory a project this dashboard can open.

    The state it is in is asked with the same function the server answers
    the browser with, so what the container acts on here and what a user
    would be shown are one judgement, not two that can disagree.
    """
    problem = builder.project_problem(CONFIG_ROOT)
    if problem is None:
        return

    code = problem["code"]
    if code == builder.PROBLEM_NO_PROJECT:
        _create_project()
    elif code == builder.PROBLEM_UPGRADE_REQUIRED:
        _upgrade_project(problem)
    elif code == builder.PROBLEM_VERSION_UNSUPPORTED:
        logger.error(
            "the project in %s states version %s, and this app speaks version %s. It was "
            "written by a newer MCUHome — update the app rather than the project.",
            CONFIG_ROOT,
            problem.get("project_version"),
            problem.get("expected_version"),
        )
    elif code == builder.PROBLEM_UPGRADING:
        logger.error(
            "the project in %s is held by an upgrade. If no upgrade is running, an earlier "
            "one was killed halfway: restore the directory from a backup.",
            CONFIG_ROOT,
        )
    else:
        logger.error("the project marker in %s cannot be read (%s)", CONFIG_ROOT, code)


def _create_project() -> None:
    """First start: there is no project here yet, so make one."""
    try:
        api.init_project(CONFIG_ROOT)
    except MCUHomeError as error:
        # The one case worth spelling out: somebody put files in the
        # directory that are not a project — a half-restored backup, a
        # clone that lost its marker. Forcing past that would write into
        # their work, so the app starts and says what it found instead.
        logger.error("could not create a project in %s: %s", CONFIG_ROOT, error)
        return
    logger.info("created a new MCUHome project in %s", CONFIG_ROOT)


def _upgrade_project(problem: dict[str, Any]) -> None:
    """Bring the project forward to the layout this app speaks.

    Unattended on purpose: in this deployment there is no terminal to
    confirm at, and a user who updated the app has already agreed to the
    part of it they can see. Home Assistant's backup is the way back.
    """
    logger.warning(
        "upgrading the project in %s from version %s to %s",
        CONFIG_ROOT,
        problem.get("project_version"),
        problem.get("expected_version"),
    )
    try:
        with api.upgrade_session(CONFIG_ROOT) as session:
            result = session.apply(on_event=_log_migration)
    except MCUHomeError as error:
        logger.error("the upgrade did not finish: %s", error)
        logger.error(
            "restore %s from a backup; this app will not try again on its own", CONFIG_ROOT
        )
        return
    if result.stopped:
        logger.error("the upgrade stopped at project version %s", result.to_version)
        return
    logger.info("project upgraded: version %s → %s", result.from_version, result.to_version)


def _log_migration(kind: str, migration: Any) -> None:
    if kind == "start":
        logger.info("  %s …", migration.description)
    else:
        logger.info("  done, project version %s", migration.to_version)


def main() -> None:
    options = read_options()
    level = LOG_LEVELS.get(str(options.get("log_level", "info")).lower(), "INFO")
    logging.basicConfig(level=level, format="%(levelname)s: %(message)s", stream=sys.stdout)

    prepare_project()

    # Replace this process rather than supervise it: the app is one
    # program, and the container's exit status should be the server's.
    server = "/opt/mcuhome/bin/mcuhome-ui"
    os.execv(
        server,
        [
            server,
            "--config-root",
            str(CONFIG_ROOT),
            "--data-dir",
            str(DATA_DIR),
            "--no-public-site",
            "--ingress-port",
            str(INGRESS_PORT),
            "--log-level",
            level,
        ],
    )


if __name__ == "__main__":
    main()
