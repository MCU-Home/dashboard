# MCUHome Dashboard

[![License: Apache-2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Status: pre-alpha](https://img.shields.io/badge/status-pre--alpha-red.svg)](#project-status)

**The web interface for [MCUHome](https://github.com/mcu-home/mcuhome):
create, build, flash and manage Zephyr-based smart home devices from your
browser.**

The dashboard is a standalone product with its own release cycle. It will
be distributed as a Home Assistant add-on (packaged in a separate,
yet-to-be-created `home-assistant-addon` repository), as a Docker image,
and as a plain Python application.

## Project status

**Pre-alpha.** This repository is a scaffold; the architecture is being
designed in the open (see [docs/adr/](docs/adr/)). Nothing is functional
yet. Firmware framework and YAML builder live in
[mcu-home/mcuhome](https://github.com/mcu-home/mcuhome).

## Architecture (planned)

| Path | Purpose |
|---|---|
| `backend/` | Python backend: build orchestration, device management, API for the frontend |
| `frontend/` | TypeScript single-page application (framework selection pending, see ADR 0002) |
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
