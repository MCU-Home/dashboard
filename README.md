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

## Project status

**Pre-alpha.** The architecture is designed in the open and the design
phase is complete (see [docs/adr/](docs/adr/)). The backend serves the
API, watches the configuration tree and validates device configurations;
the frontend lists devices, edits their YAML with the builder's
diagnostics on the editor's gutter, and shows a device's Matter
commissioning codes. There is **no build path yet** — the build-server
client and the Home Assistant App packaging are still to come, and so is
creating a device from the browser. Firmware framework and YAML builder
live in [mcu-home/mcuhome](https://github.com/mcu-home/mcuhome).

## Architecture

| Path | Purpose |
|---|---|
| `backend/` | Python backend (aiohttp, WebSocket-first API): build orchestration, device management |
| `frontend/` | TypeScript single-page application: Lit 3, `@home-assistant/webawesome`, CodeMirror 6, Vite |
| `docs/adr/` | Architecture decision records (dashboard-specific) |

The backend drives the MCUHome builder (`mcuhome` Python package) and
serves the frontend. The YAML configuration schema and device metadata are
owned by the firmware repository and consumed here as a versioned artifact.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Questions and ideas:
[GitHub Discussions](https://github.com/mcu-home/dashboard/discussions).

## License

Apache License 2.0 — see [LICENSE](LICENSE). This repository follows the
[REUSE](https://reuse.software/) specification.
