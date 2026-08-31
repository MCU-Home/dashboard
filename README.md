# mcuhome-ui

mcuhome-ui is MCUHome's web interface: a browser front end for a project of
device configurations, from editing one to building and signing its firmware.
It is the graphical half of the project's tooling, working on the same project
tree the command line works on.

## What this repository holds

- A Python backend (`backend/`) that serves two `aiohttp` sites from one
  process: a Home Assistant ingress site and a password-protected standalone
  site.
- The `/ws` command vocabulary — device listing, editing, validation,
  commissioning codes and build control — defined in one place,
  `backend/mcuhome/ui/commands.py`.
- A TypeScript single-page application (`frontend/`): device list, YAML editor,
  new-device wizard, commissioning view and live build panel.
- The seam onto the workbench library, which runs a build in-process and
  applies the firmware signature to its unsigned result.
- The Dockerfile behind both published images, and the Home Assistant entry
  point that opens the project an App instance works on.

## Using it

The standalone image serves the interface on port 8099 against a project
directory mounted at `/config`, and keeps its signing key and build output in
`/data`. A bind other than loopback wants a password.

```sh
docker run -p 8099:8099 -v /path/to/project:/config -v mcuhome-data:/data \
  -e MCUHOME_DASHBOARD_PASSWORD=secret ghcr.io/mcu-home/ui
```

The second image, `ghcr.io/mcu-home/ui-homeassistant-app`, is the same program
as a Home Assistant App: it runs behind ingress, authenticated by the
Supervisor, and opens the App's own configuration directory as the project.

## How it fits into MCUHome

The interface imports
[mcuhome-workbench](https://github.com/mcu-home/mcuhome-workbench) in-process
for device model parsing, validation, build orchestration and signing; it
never invokes the [mcuhome-cli](https://github.com/mcu-home/mcuhome-cli) and
never compiles firmware itself. Which build method runs is configuration:
`local` drives a build environment image defined in
[mcuhome-sdk](https://github.com/mcu-home/mcuhome-sdk), `remote` hands the work
to a [mcuhome-buildserver](https://github.com/mcu-home/mcuhome-buildserver).
The App image is built and published from this repository;
[homeassistant-apps](https://github.com/mcu-home/homeassistant-apps) carries
only the metadata that makes it installable in Home Assistant.

## Layout

| Path | Purpose |
|---|---|
| `backend/` | The Python package `mcuhome.ui` and its test suite |
| `frontend/` | The single-page application and its test suite |
| `docker/` | The two-target Dockerfile and the Home Assistant entry point |
| `docs/` | Decision records for this repository |

## Development — how to work on this repository

This repository has its own virtual environment in `.venv/`; nothing is
installed into the system Python or into another repository's environment.
`bin/` holds the user-facing entry points, `scripts/` the development
tooling: `scripts/test` and `scripts/lint` dispatch the checks — `all` runs
every one, `list` names them, `<name>` runs one — and each check is its own
wrapper in `scripts/test.d/` or `scripts/lint.d/`. The wrappers select
`.venv` themselves (never activate one by hand) and are exactly what CI
runs, one job per check.

Needs Python ≥3.13 for two `.venv`s — the root one for the lint tools that
run over `backend` and `docker`, `backend/.venv` for pytest, where
`backend/requirements-dev.txt` adds the sibling checkouts of `mcuhome-sdk`'s
`packaging/model` and `mcuhome-workbench` — and Node 22 with pnpm for the
frontend, whose dependencies `scripts/test vitest` and the frontend lint
wrappers expect already installed.

```sh
python3 -m venv .venv && .venv/bin/pip install --group dev
(cd backend && python3 -m venv .venv && \
  .venv/bin/pip install -r requirements-dev.txt && \
  .venv/bin/pip install -e . --group dev)
(cd frontend && pnpm install --frozen-lockfile)
```

```sh
scripts/test all
scripts/lint all
```

The rules that hold across every MCUHome repository — coding standards,
commits, licensing — are in the organization's
[contributing guide](https://github.com/mcu-home/.github/blob/main/CONTRIBUTING.md).

## Configuration

Options come from the command line or from environment variables prefixed
`MCUHOME_DASHBOARD_`, the command line winning where both are given: the
project directory, the data directory, the bind address and password, and the
build method together with its build-server address and token. A Home
Assistant App reads `/data/options.json` instead, which
`docker/homeassistant-entrypoint.py` maps onto the same settings. The full set
is declared in [`backend/mcuhome/ui/config.py`](backend/mcuhome/ui/config.py).

## Security

The two sites answer to different authorities. The ingress site proves that its
peer is the Supervisor gateway before it reads a user header, then asks Home
Assistant whether that user is an administrator and treats an unanswerable
question as "no"; the standalone site requires a password unless it is bound to
loopback alone. The firmware signing key is read by this process and by nothing
else — what travels into a build is the public half, on either build method.
Vulnerabilities are reported through the organization's
[security policy](https://github.com/mcu-home/.github/blob/main/SECURITY.md).

## Documentation

- [`docs/adr/`](docs/adr/) — the decisions behind this interface
- [`backend/mcuhome/ui/commands.py`](backend/mcuhome/ui/commands.py) — the
  `/ws` command vocabulary
- [`docker/Dockerfile`](docker/Dockerfile) — how both images are assembled
- [MCUHome on GitHub](https://github.com/mcu-home) — the rest of the project

## Contributing and support

Bugs, questions and feature requests belong in this repository's
[issue tracker](https://github.com/mcu-home/mcuhome-ui/issues). The
organization's
[contributing rules](https://github.com/mcu-home/.github/blob/main/CONTRIBUTING.md)
apply before a pull request.

## License

Apache License 2.0, see [`LICENSE`](LICENSE).
