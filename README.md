# MCUHome Dashboard

[![License: Apache-2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Status: pre-alpha](https://img.shields.io/badge/status-pre--alpha-red.svg)](#project-status)

**The web interface for [MCUHome](https://github.com/mcu-home/mcuhome-workbench):
create, build, flash and manage Zephyr-based smart home devices from your
browser.**

The dashboard is a standalone product with its own release cycle,
distributed as a **Home Assistant App**, as a **Docker image**, and as a
plain Python application.

**The dashboard never compiles.** Firmware builds always run on a
separate build server, on any machine you point the dashboard at. See
[ADR 0003](docs/adr/0003-two-home-assistant-apps-dashboard-never-compiles.md).
That build server lives in its own repository,
[mcu-home/mcuhome-buildserver](https://github.com/mcu-home/mcuhome-buildserver)
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
signing key never leaves this side. A device can be **created** from the
browser, and both container images are built and published from this
repository. Still to come: the flash views in the browser. Firmware
framework and YAML builder live in
[mcu-home/mcuhome-workbench](https://github.com/mcu-home/mcuhome-workbench).

## Architecture

| Path | Purpose |
|---|---|
| `backend/` | Python backend (aiohttp, WebSocket-first API): device management |
| `frontend/` | TypeScript single-page application: Lit 3, `@home-assistant/webawesome`, CodeMirror 6, Vite |
| `docker/` | The two published container images, built from one Dockerfile |
| `docs/adr/` | Architecture decision records (dashboard-specific) |

Two products, two version numbers, one protocol — and since ADR 0012 two
repositories: the headless build service lives in
[mcu-home/mcuhome-buildserver](https://github.com/mcu-home/mcuhome-buildserver).
Neither package depends on the other: a build server is installable
where the dashboard is not, and the dashboard will talk to one over the
network even when both run on the same host. The protocol joining them
is being replaced (ADR 0012 decision 3), and until the new client
exists the two are not joined at all.

The backend drives the MCUHome builder (`mcuhome` Python package) and
serves the frontend. The YAML configuration schema and device metadata are
owned by the firmware repository and consumed here as a versioned artifact.

## Running it

### In Home Assistant

Add the MCUHome app repository once —
**Settings → Apps → App Store → ⋮ → Repositories**:

```
https://github.com/mcu-home/homeassistant-apps
```

then install **MCUHome Dashboard** and open its web interface. The App
creates its project directory on first start and keeps it current across
updates; only Home Assistant administrators can change or build anything.

### With Docker

```sh
docker run -d --name mcuhome-ui \
  -p 8099:8099 \
  -e MCUHOME_DASHBOARD_PASSWORD='choose-one' \
  -v mcuhome-config:/config \
  -v mcuhome-data:/data \
  ghcr.io/mcu-home/ui:latest
```

`/config` is the MCUHome project — devices, secrets, shared pieces — and
`/data` is private: the firmware signing key and build output. **Back up
the signing key.** Every device you bootstrap accepts only firmware
signed with it.

The password is what the public site asks for; there is no Home Assistant
here to say who is asking. Unlike the App, this image does not create a
project for you — point it at one, or make one with
[the command line](https://github.com/mcu-home/mcuhome-cli).

Both images are built from `docker/Dockerfile` in this repository and
published on a `v*` tag; the App's metadata lives in
[mcu-home/homeassistant-apps](https://github.com/mcu-home/homeassistant-apps)
([ADR 0018](docs/adr/draft/0018-images-here-app-metadata-in-its-own-repository.md)).

### Building firmware

Neither deployment compiles. Point the dashboard at a
[build server](https://github.com/mcu-home/mcuhome-buildserver) with
`MCUHOME_DASHBOARD_BUILD_SERVER_URL`; everything else — creating,
editing, validating devices and drawing commissioning credentials — works
without one.

## Contributing

See [the contributing rules](https://github.com/mcu-home/.github/blob/main/CONTRIBUTING.md). Questions and ideas:
[GitHub Discussions](https://github.com/mcu-home/mcuhome-ui/discussions).

## License

Apache License 2.0 — see [LICENSE](LICENSE). This repository follows the
[REUSE](https://reuse.software/) specification.
