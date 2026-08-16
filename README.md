# MCUHome Dashboard

[![License: Apache-2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Status: pre-alpha](https://img.shields.io/badge/status-pre--alpha-red.svg)](#project-status)

**The web interface for [MCUHome](https://github.com/mcu-home/mcuhome):
create, build, flash and manage Zephyr-based smart home devices from your
browser.**

The dashboard is a standalone product with its own release cycle. It will
be distributed as a Home Assistant App (packaged in a separate,
yet-to-be-created packaging repository), as a Docker image, and as a
plain Python application.

**The dashboard never compiles.** Firmware builds always run on a
separate build server, on any machine you point the dashboard at. See
[ADR 0003](docs/adr/0003-two-home-assistant-apps-dashboard-never-compiles.md).
That build server lives in its own repository,
[mcu-home/build-server](https://github.com/mcu-home/build-server)
([ADR 0012](docs/adr/0012-build-server-extraction.md)).

**And it speaks no build protocol of its own.** The client that spoke
the job protocol of [ADR 0006](docs/adr/0006-build-service-protocol.md)
was dismantled rather than migrated (ADR 0012 decision 3), and its
successor was not written here:
[ADR 0013](docs/adr/0013-building-over-the-builder-package.md) found it
already written, in `mcuhome-workbench`, which this package imports
in-process. So the dashboard builds — `build/*` commands, streamed logs,
artifact downloads and host-side signing — by calling
`mcuhome.workbench.api.run_build`, and *where* that build runs is
deployment configuration: a build container on this machine, a build
server, or a west workspace.

## Project status

**Pre-alpha.** The architecture is designed in the open and the design
phase is complete (see [docs/adr/](docs/adr/)). The backend serves the
API, watches the configuration tree and validates device configurations;
the frontend lists devices, edits their YAML with the builder's
diagnostics on the editor's gutter, and shows a device's Matter
commissioning codes. **Building works end to end** (ADR 0013): the
`build/*` commands, the `builds` topic, logs streamed with resumable
offsets, the artifact endpoint and host-side signing — the private
signing key never leaves this side. Still to come: the flash views in
the browser, creating a device from the browser, and the Home Assistant
App packaging. Firmware framework and
YAML builder live in
[mcu-home/mcuhome](https://github.com/mcu-home/mcuhome).

## Architecture

| Path | Purpose |
|---|---|
| `backend/` | Python backend (aiohttp, WebSocket-first API): device management |
| `frontend/` | TypeScript single-page application: Lit 3, `@home-assistant/webawesome`, CodeMirror 6, Vite |
| `docs/adr/` | Architecture decision records (dashboard-specific) |

Two products, two version numbers, one protocol — and since ADR 0012 two
repositories: the headless build service lives in
[mcu-home/build-server](https://github.com/mcu-home/build-server).
Neither package depends on the other: a build server is installable
where the dashboard is not, and the dashboard will talk to one over the
network even when both run on the same host. The protocol joining them
is being replaced (ADR 0012 decision 3), and until the new client
exists the two are not joined at all.

The backend drives the MCUHome builder (`mcuhome` Python package) and
serves the frontend. The YAML configuration schema and device metadata are
owned by the firmware repository and consumed here as a versioned artifact.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Questions and ideas:
[GitHub Discussions](https://github.com/mcu-home/dashboard/discussions).

## License

Apache License 2.0 — see [LICENSE](LICENSE). This repository follows the
[REUSE](https://reuse.software/) specification.
