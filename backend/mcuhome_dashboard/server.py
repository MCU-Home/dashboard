# SPDX-FileCopyrightText: 2026 The MCUHome Contributors
# SPDX-License-Identifier: Apache-2.0
"""Process entry point: bind the sites of ADR 0009 and serve them.

The ingress site's binding deserves a note, because ADR 0009 phrases it
as "binds loopback plus the Supervisor gateway" and an address you bind
is a *local* address, while ``172.30.32.2`` is the gateway's — a peer.
Taken literally the sentence cannot be implemented. What it means, and
what :func:`supervisor_interface` does, is:

* bind loopback, plus this container's own address on the Supervisor
  network (``172.30.32.0/23``), which is what the gateway can reach;
* refuse any peer that is neither loopback nor the gateway
  (:func:`mcuhome_dashboard.security.is_trusted_peer`).

Binding and peer check together are the trust boundary the ADR
describes. Outside a Home Assistant app there is no such interface, the
ingress site binds loopback only, and it is off by default anyway.
"""

from __future__ import annotations

import asyncio
import ipaddress
import logging
import socket
import sys
from collections.abc import Sequence

from aiohttp import web

from mcuhome_dashboard import versions
from mcuhome_dashboard.app import AppState, create_app
from mcuhome_dashboard.builder import DEFAULT_BUILD_METHOD, MCUHOME_VERSION
from mcuhome_dashboard.config import Config, http_url, load_config
from mcuhome_dashboard.security import TrustMode

__all__ = ["main", "run", "serve", "supervisor_interface"]

logger = logging.getLogger(__name__)

#: The Supervisor's private network. An app's own address is in it; so
#: is the gateway that proxies ingress traffic.
SUPERVISOR_NETWORK = ipaddress.ip_network("172.30.32.0/23")


def supervisor_interface() -> str | None:
    """This container's address on the Supervisor network, if any."""
    try:
        infos = socket.getaddrinfo(socket.gethostname(), None, family=socket.AF_INET)
    except OSError:  # pragma: no cover - depends on the host's resolver
        return None
    for info in infos:
        address = info[4][0]
        try:
            if ipaddress.ip_address(address) in SUPERVISOR_NETWORK:
                return address
        except ValueError:  # pragma: no cover - defensive
            continue
    return None


def _ingress_hosts() -> list[str]:
    hosts = ["127.0.0.1"]
    own = supervisor_interface()
    if own is not None:
        hosts.append(own)
    else:
        logger.warning(
            "no address on the Supervisor network %s found; the ingress site will only "
            "be reachable from this container. This is expected outside a Home "
            "Assistant app.",
            SUPERVISOR_NETWORK,
        )
    return hosts


def _announce(config: Config) -> None:
    logger.info(
        "MCUHome Dashboard %s (builder mcuhome %s, model_version %d)",
        versions.DASHBOARD_VERSION,
        MCUHOME_VERSION,
        versions.MODEL_VERSION,
    )
    logger.info("configuration tree: %s", config.config_root or "not configured")
    logger.info("sites: %s", config.site_summary())
    # Where a build will go, said once at startup rather than at the
    # first build. Which method runs is deployment configuration (ADR
    # 0013 decision 1), and an operator who set one — or who set none and
    # gets the package's default — should learn it from the log rather
    # than from a build that took an unexpected path.
    logger.info(
        "builds: method %s%s",
        config.build_method or f"{DEFAULT_BUILD_METHOD} (the builder's default)",
        (
            f", build server {http_url(config.build_server_url or '')}"
            if config.build_server_configured
            else ", no build server configured"
        ),
    )
    if config.build_method == "remote" and not config.build_server_configured:
        logger.warning(
            "The remote build method is configured but no build server address is. "
            "There is no discovery and no default, so every build will refuse until "
            "MCUHOME_DASHBOARD_BUILD_SERVER_URL (or --build-server-url) is set."
        )
    if config.password_generated:
        # The code-server pattern (ADR 0009 decision 2). Printed once,
        # at a level nobody filters out, because it is the only way the
        # operator can get in.
        logger.warning(
            "No password was configured and this dashboard is not bound to loopback, "
            "so one was generated for this run:\n\n    %s\n\n"
            "Set MCUHOME_DASHBOARD_PASSWORD to keep a password across restarts.",
            config.password,
        )
    elif config.public_site and not config.auth_required:
        logger.info(
            "The public site is bound to %s and runs without a password. Set "
            "MCUHOME_DASHBOARD_PASSWORD before binding any other address.",
            config.host,
        )


async def serve(config: Config, *, ready: asyncio.Event | None = None) -> None:
    """Run both configured sites until cancelled."""
    state = AppState(config)
    await state.start()

    runners: list[web.AppRunner] = []
    try:
        if config.public_site:
            runner = web.AppRunner(create_app(state, TrustMode.PUBLIC))
            await runner.setup()
            runners.append(runner)
            await web.TCPSite(runner, config.host, config.port).start()

        if config.ingress_port is not None:
            runner = web.AppRunner(create_app(state, TrustMode.INGRESS))
            await runner.setup()
            runners.append(runner)
            await web.TCPSite(runner, _ingress_hosts(), config.ingress_port).start()

        if ready is not None:
            ready.set()
        await asyncio.Event().wait()
    finally:
        for runner in runners:
            await runner.cleanup()
        await state.stop()


def run(config: Config) -> int:
    logging.basicConfig(
        level=getattr(logging, config.log_level, logging.INFO),
        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
    )
    try:
        versions.check_mcuhome_version(MCUHOME_VERSION)
    except versions.IncompatibleBuilderError as exc:
        logger.error("%s", exc)
        return 2

    _announce(config)
    try:
        asyncio.run(serve(config))
    except KeyboardInterrupt:  # pragma: no cover - interactive
        logger.info("stopped")
    return 0


def main(argv: Sequence[str] | None = None) -> int:
    return run(load_config(argv))


if __name__ == "__main__":  # pragma: no cover
    sys.exit(main())
