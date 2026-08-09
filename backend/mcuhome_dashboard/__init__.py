# SPDX-FileCopyrightText: 2026 The MCUHome Contributors
# SPDX-License-Identifier: Apache-2.0
"""MCUHome Dashboard backend.

An aiohttp application (ADR 0004) whose entire UI surface is one
WebSocket endpoint. It imports the ``mcuhome`` builder package
in-process (ADR 0011) and never compiles anything itself (ADR 0003).

It also cannot get anything compiled: the build-server client was
removed with the job protocol (ADR 0012 decision 3) and its
session-protocol successor does not exist yet. There is no module in
this map that talks to a build server.

Module map:

===============================  =====================================
:mod:`mcuhome_dashboard.config`  runtime configuration (CLI + env)
:mod:`mcuhome_dashboard.app`     application factory, shared state
:mod:`mcuhome_dashboard.server`  the two sites of ADR 0009
:mod:`mcuhome_dashboard.ws`      the ``/ws`` endpoint
:mod:`mcuhome_dashboard.commands`  the command vocabulary
:mod:`mcuhome_dashboard.events`  in-process event bus
:mod:`mcuhome_dashboard.devices` config-tree scanning
:mod:`mcuhome_dashboard.builder` adapter over the ``mcuhome`` package
:mod:`mcuhome_dashboard.security`  trust modes, origin, password, CSRF
:mod:`mcuhome_dashboard.web`     static assets, SPA fallback, ingress
===============================  =====================================
"""

__version__ = "0.1.0.dev0"

__all__ = ["__version__"]
