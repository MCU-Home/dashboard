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

**Right now it does not build at all.** The client that spoke the job
protocol of [ADR 0006](docs/adr/0006-build-service-protocol.md) has been
removed: ADR 0012 decision 3 replaced that vocabulary with the session
protocol of the firmware repository's ADR 0019, and the decision was to
dismantle rather than migrate. The session client has not been written
yet, so this dashboard has no build commands, no build events and no
artifact downloads. Everything else — editing, validating and
commissioning devices — is unaffected.

## Project status

**Pre-alpha.** The architecture is designed in the open and the design
phase is complete (see [docs/adr/](docs/adr/)). The backend serves the
API, watches the configuration tree and validates device configurations;
the frontend lists devices, edits their YAML with the builder's
diagnostics on the editor's gutter, and shows a device's Matter
commissioning codes. **Building is not implemented** — the job-protocol
client was dismantled with ADR 0012 decision 3 and its session-protocol
successor is the next piece of work. What was kept for it: the
build-server address, token and auto-pairing, the detached signing
module, the event bus and the frame envelope. Still to come after it:
the build and flash views in the browser, creating a device from the
browser, and the Home Assistant App packaging. Firmware framework and
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
