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
separate build server — a second Home Assistant App, or a container on
any machine you point it at. See
[ADR 0003](docs/adr/0003-two-home-assistant-apps-dashboard-never-compiles.md).
That build server lives in this repository too, in
[`buildserver/`](buildserver/README.md), because the protocol between the
two ([ADR 0006](docs/adr/0006-build-service-protocol.md)) is the thing
that has to stay coherent.

## Project status

**Pre-alpha.** The architecture is designed in the open and the design
phase is complete (see [docs/adr/](docs/adr/)). The backend serves the
API, watches the configuration tree and validates device configurations;
the frontend lists devices, edits their YAML with the builder's
diagnostics on the editor's gutter, and shows a device's Matter
commissioning codes. The build server and the dashboard's client for it
are implemented — queue, resumable logs, chunked artifacts, detached
signing — but **no build can run yet**: the builder's CLI has no way to
consume the resolved device model that ADR 0007 makes the wire format,
and `buildserver/README.md` says exactly what is missing. Still to come:
the build views in the browser, creating a device from the browser, and
the Home Assistant App packaging. Firmware framework and YAML builder
live in [mcu-home/mcuhome](https://github.com/mcu-home/mcuhome).

## Architecture

| Path | Purpose |
|---|---|
| `backend/` | Python backend (aiohttp, WebSocket-first API): device management, build orchestration |
| `buildserver/` | The headless build service (ADR 0003's second App): the queue, the compiler, the artifacts |
| `frontend/` | TypeScript single-page application: Lit 3, `@home-assistant/webawesome`, CodeMirror 6, Vite |
| `docs/adr/` | Architecture decision records (dashboard-specific) |

Two products, two version numbers, one protocol. Neither package depends
on the other: a build server is installable where the dashboard is not,
and the dashboard talks to one over the network even when both run on the
same host.

The backend drives the MCUHome builder (`mcuhome` Python package) and
serves the frontend. The YAML configuration schema and device metadata are
owned by the firmware repository and consumed here as a versioned artifact.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Questions and ideas:
[GitHub Discussions](https://github.com/mcu-home/dashboard/discussions).

## License

Apache License 2.0 — see [LICENSE](LICENSE). This repository follows the
[REUSE](https://reuse.software/) specification.
