# AGENTS.md — MCUHome Dashboard

Guide for AI coding agents (and new human contributors) working in this
repository.

## What this project is

The web interface for [MCUHome](https://github.com/mcu-home/mcuhome): a
standalone product to create, build, flash and manage Zephyr-based smart
home devices. Distribution targets: **Home Assistant App** (packaged in a
separate future packaging repo), Docker image, plain Python app.

**Current phase: pre-alpha.** The design phase is complete — ADRs
0003–0011 fix the deployment topology, the backend and frontend stacks,
the build-service protocol, state layout, auth, flash flow and the
builder coupling. Check `docs/adr/` before assuming any design decision.

The **backend** is implemented: the two sites of ADR 0009, the `/ws`
command and event vocabulary of ADR 0004, configuration-tree watching,
in-process validation through the builder, reading and writing device
configurations, and static serving with ingress base paths.
`backend/README.md` documents the frame vocabulary — it is the
hand-written substitute for the OpenAPI document a WebSocket API does not
produce.

The **frontend** is implemented for the MVP views of ADR 0005: device
list, YAML editor with the builder's diagnostics on the gutter, saving
with conflict detection, and the commissioning view.
`frontend/README.md` is its guide.

The **build server** is implemented (`buildserver/`): the ADR 0006
protocol, a queue with compile lane 1, per-job log sidecars with
resumable follow, chunked and hashed artifacts, `GET /capabilities`, and
retention. So is the dashboard's client for it, including the detached
signing hand-off of ADR 0007/0008. **No build runs yet**, for one reason
recorded at the top of `buildserver/README.md`: `mcuhome build` cannot
take the resolved `device-model.json` that ADR 0007 makes the wire
format. The server probes for that and refuses jobs with the reason
until the builder grows the option.

Still missing: build and flash views in the browser, creating a device
from the browser (needs `mcuhome new` as API, ADR 0011), and app
packaging.

The architecture in one line: **two Home Assistant Apps** — the thin
`mcuhome-dashboard` and the fat `mcuhome-build-server` — because the
dashboard never compiles (ADR 0003). Both live in this repository, and
**neither Python package depends on the other**: they are separate
products with separate version numbers, joined by one protocol.

## Repository map

| Path | Role |
|---|---|
| `backend/` | Python ≥ 3.13 backend (`mcuhome_dashboard` package), aiohttp, WebSocket-first API (ADR 0004) |
| `buildserver/` | Python ≥ 3.13 build service (`mcuhome_buildserver` package), aiohttp, the ADR 0006 protocol |
| `frontend/` | TypeScript SPA: Lit 3 + `@home-assistant/webawesome` + CodeMirror 6, built with Vite (ADR 0005) |
| `docs/adr/` | Dashboard-specific architecture decision records |

The three READMEs are the contracts: `backend/README.md` is the `/ws`
frame vocabulary, `buildserver/README.md` the build protocol and how the
server is deployed, `frontend/README.md` the application that consumes
the first.

Project-wide decisions (license, repo split, versioning) are recorded in
the firmware repo:
[mcu-home/mcuhome/docs/adr](https://github.com/mcu-home/mcuhome/tree/main/docs/adr).

## Non-obvious invariants

- **Contract ownership:** the YAML configuration schema and device
  metadata are owned by the firmware repository. The dashboard consumes
  them as a versioned artifact — never duplicate or fork schema definitions
  here.
- **The dashboard never compiles** (ADR 0003). Every build goes to a
  build server over the protocol of ADR 0006 — including when both Apps
  run on the same host. There is no local-build code path, and none is
  to be added.
- **The dashboard is a standalone product** with its own release cycle,
  *and* it imports the `mcuhome` builder package in-process (ADR 0011).
  Both hold because the dependency has one direction: the dashboard
  declares a supported `mcuhome` version range and follows the builder's
  releases; the builder never depends on the dashboard, and using the
  builder CLI must never require the dashboard or any dashboard version.
- **App packaging does not live here** — no `config.yaml`/Dockerfile App
  files in this repo, for either App; they go to the future packaging
  repo. The build server's own source wraps the `mcuhome` builder package
  and versions in lockstep with the builder image.
- **The two Python packages do not import each other.** `buildserver/`
  must be installable on a machine that has no dashboard, and the
  dashboard must be installable without the toolchain half. They keep
  separate copies of the frame codec on purpose; the *vocabulary* is kept
  from drifting by a test (`buildserver/tests/test_protocol.py`) that
  compares the two whenever both are importable, not by an import.
- **The build server never signs, and the dashboard never compiles.**
  Each half is missing what the other has, by construction: the private
  signing key is only ever on the dashboard side (ADR 0007 decision 3,
  ADR 0008), and there is no build path in `backend/`. A `submit_job`
  that asks the build server to sign is refused rather than quietly
  built unsigned.
- **Say "App", not "Add-on"** — Home Assistant renamed them in 2026.2.
  Applies to every user-facing string, screenshot and document.
- Stored device configurations may contain secrets (WiFi credentials,
  Thread network keys, Matter setup codes) — treat all config handling as
  security-sensitive (see SECURITY.md). The build server necessarily sees
  a device's commissioning credentials (ADR 0007); the firmware signing
  key never leaves this side (ADR 0008).
- **Commissioning codes travel only when a user asked for them.** The QR
  payload contains the device's passcode, so it is returned by
  `device/commissioning` and by nothing else — never by `device/list`,
  `device/get` or `device/validate`, whose answers every open browser tab
  receives without asking. The UI keeps them behind an explicit action
  for the same reason. `backend/tests/test_commissioning.py` asserts the
  negative half.
- **Nothing in the frontend bundle may hard-code a path.** The base path
  arrives per request from `X-Ingress-Path` (ADR 0005 decision 4); Vite
  runs with `base: './'`, routing lives in the URL fragment, and
  `src/base-path.ts` is the only module that reads
  `window.MCUHOME_BASE_PATH`.

## Commands

```sh
# Backend setup. requirements-dev.txt also installs the mcuhome builder
# package from the sibling checkout (../../mcuhome) — it is imported
# in-process (ADR 0011) and is not published yet.
cd backend && python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements-dev.txt

# Backend tests (in-process, no subprocess, no container, ~4 s)
pytest

# Build-server setup and tests (a fake builder subprocess, never a real
# build, ~5 s)
cd ../buildserver && pip install -r requirements-dev.txt
pytest

# Python lint/format — one run over both packages, one configuration
ruff check --fix backend buildserver && ruff format backend buildserver

# Frontend (Node >= 22.12; `corepack enable` picks up the pinned pnpm)
cd frontend && pnpm install
pnpm dev            # dev server on :5173, proxying /ws to the backend
pnpm check          # format, lint, types and tests — what a commit must pass
pnpm build          # emits frontend/dist

# Run it, serving the built frontend
mcuhome-dashboard --config-root ~/mcuhome-config --static-root frontend/dist

# All lint hooks
pre-commit run --all-files
```

There is no CI yet.

## Coding standards

- **Python:** ruff (lint + format), line length 100, target Python 3.13+.
- **TypeScript:** Lit 3 + Vite (ADR 0005), `strict` plus
  `noUncheckedIndexedAccess`; eslint (type-aware) and prettier, both in
  pre-commit. Lit uses TypeScript's legacy decorators with
  `useDefineForClassFields: false` — changing either silently breaks
  every reactive property. See `frontend/README.md`.
- **Licensing:** everything is Apache-2.0 with SPDX headers in every new
  file; the repo is REUSE-compliant (`reuse lint` runs in pre-commit).

## Commit and PR conventions

- Conventional Commits (`feat:`, `fix:`, `docs:`, `chore:`, …).
- Every commit is DCO-signed-off: `git commit -s`.
- Default branch is `main`; short-lived `feat/…`, `fix/…` branches.
- Non-trivial design decisions require an ADR in `docs/adr/`.
