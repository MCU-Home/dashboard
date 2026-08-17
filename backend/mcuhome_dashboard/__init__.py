# SPDX-FileCopyrightText: 2026 The MCUHome Contributors
# SPDX-License-Identifier: Apache-2.0
"""MCUHome Dashboard backend.

An aiohttp application (ADR 0004) whose entire UI surface is one
WebSocket endpoint. It imports ``mcuhome-workbench`` in-process (ADR
0011) and never compiles anything itself (ADR 0003) — it carries no
toolchain, and ``mcuhome-compiler`` is deliberately not installed.

It does get things compiled (ADR 0013): a build is
``mcuhome.workbench.api.run_build``, the builder package's one awaitable
over a build container, a build server or a west workspace. Which of
them runs is deployment configuration. No module here speaks a build
protocol — the package does.

Module map:

=================================  ===================================
:mod:`mcuhome_dashboard.config`    runtime configuration (CLI + env)
:mod:`mcuhome_dashboard.app`       application factory, shared state
:mod:`mcuhome_dashboard.server`    the two sites of ADR 0009
:mod:`mcuhome_dashboard.ws`        the ``/ws`` endpoint
:mod:`mcuhome_dashboard.commands`  the command vocabulary
:mod:`mcuhome_dashboard.events`    in-process event bus
:mod:`mcuhome_dashboard.devices`   config-tree scanning
:mod:`mcuhome_dashboard.builds`    the build registry and its log stream
:mod:`mcuhome_dashboard.builder`   adapter over the builder package
:mod:`mcuhome_dashboard.signing`   the signing key and the signature
:mod:`mcuhome_dashboard.security`  trust modes, origin, password, CSRF
:mod:`mcuhome_dashboard.web`       static assets, SPA fallback, ingress
=================================  ===================================
"""

__version__ = "0.1.0"

__all__ = ["__version__"]
