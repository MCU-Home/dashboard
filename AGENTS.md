# AGENTS.md — MCUHome Dashboard

Guide for AI coding agents (and new human contributors) working in this
repository.

## What this project is

The web interface for [MCUHome](https://github.com/mcu-home/mcuhome): a
standalone product to create, build, flash and manage Zephyr-based smart
home devices. Distribution targets: **Home Assistant App**, Docker image,
plain Python app. The first two are container images built here and
published to GHCR; the App's metadata lives in
[mcu-home/ha-apps-repository](https://github.com/mcu-home/ha-apps-repository)
(ADR 0018).

> **FEATURE-FROZEN (product-owner decision, 2026-08-14; narrowed
> 2026-08-16).** This repository is not being grown while the CLI phase
> settles `mcuhome.workbench` and its API: no new features here, and the
> rebuild pass that follows the CLI is where the dashboard catches up
> with the CLI-era product.
>
> What is **no longer** accepted is a red suite. The freeze was read as
> "red tests are expected", and under that reading 47 failing tests sat
> here for two days hiding the fact that exactly *one* thing was wrong —
> the fixture wrote a version-0 project marker after the workbench moved
> to version 1. A permanently red suite cannot tell you that the 48th
> failure is real. So the suite is green, CI runs it (`.github/
> workflows/ci.yml`), and it stays that way: following a workbench
> change that breaks the build is maintenance, not feature work, and is
> done as it arrives.

**Current phase: pre-alpha.** ADRs 0003–0011 fix the backend and
frontend stacks, state layout, auth, flash flow and the builder
coupling. **ADR 0012 supersedes the build-service protocol of ADR 0006**
and amends ADR 0003's topology; **ADR 0013 then supersedes 0012's
decision 3** — the dashboard does not speak the session protocol at all,
it calls `mcuhome.workbench.api.run_build` and the *package* speaks it.
Read 0013 first, then 0012; treat 0006 as history except for its
transport and threat-model decisions, which both carry forward. Check
`docs/adr/` — immutable finals at the top level, living drafts in
`draft/` (state layout 0008 and flash flow 0010 are drafts; lifecycle
per firmware ADR 0021, see `docs/adr/README.md`) — before assuming any
design decision.

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

The **build server** lives in its own repository since ADR 0012:
[mcu-home/build-server](https://github.com/mcu-home/build-server).

**There is no build *protocol* client here, and none is to be added.**
ADR 0012 decision 3 dismantled ADR 0006's job client and named the
session protocol of firmware ADR 0019 as its successor; **ADR 0013**
found that successor already written, in `mcuhome-workbench`, which this
package imports in-process (ADR 0011). Writing a second one here would
be a second opinion about a protocol the package already speaks.

So the dashboard **builds** — `build/*` commands, the `builds` topic,
streamed logs with resumable offsets, step-by-step progress over the
workbench's `on_step` seam (ADR 0016 draft), the artifact endpoint and
detached signing — by calling `mcuhome.workbench.api.run_build`, and
speaks no build protocol itself. Which build method runs is **deployment
configuration** (`--build-method`): a build container on this machine, a
build server, or a west workspace. `backend/mcuhome_dashboard/builds.py`
is the registry; `builder.py` holds the one seam.

A device can also be **created** from the browser (ADR 0017 draft):
`device/boards` hands the builder's registry to a form that offers
nothing MCUHome has not brought up, `device/new` writes the first
`main.yaml` through `mcuhome.workbench.api.new_device`, and
`device/matter-pairing` draws the device's commissioning identity once.
None of those verbs judges a name — every refusal is the builder's.

It is also **packaged** (ADR 0018): `docker/Dockerfile` builds the
standalone and the Home Assistant image, `.github/workflows/release.yml`
publishes them on a `v*` tag, and the App's entry point creates or
migrates the project before the server starts — the one thing this
program will not do for itself.

Still missing: flash views in the browser.

The architecture in one line: the dashboard never compiles (ADR 0003) —
it carries no toolchain, and `mcuhome-compiler` is deliberately not
installed, so that invariant is checkable in the venv rather than
promised — and a build therefore runs in a build container or on a build
server, the latter having its own repository (ADR 0012). **Neither
Python package depends on the other**: separate products with separate
version numbers, joined by one protocol that a third package speaks for
both. (The two-Home-Assistant-Apps framing of ADR 0003 is what ADR
0012's Consequences struck; a build server is an orchestrator
whose primary target is standalone.)

## Repository map

| Path | Role |
|---|---|
| `backend/` | Python ≥ 3.13 backend (`mcuhome_dashboard` package), aiohttp, WebSocket-first API (ADR 0004) |
| `frontend/` | TypeScript SPA: Lit 3 + `@home-assistant/webawesome` + CodeMirror 6, built with Vite (ADR 0005) |
| `docker/` | The two published images: one Dockerfile, the runtime pins, and the Home Assistant entry point |
| `docs/adr/` | Dashboard-specific architecture decision records |

The two READMEs here are the contracts: `backend/README.md` is the
`/ws` frame vocabulary, `frontend/README.md` the application that
consumes it. The build protocol and the build server's deployment are
documented in the
[build-server repository](https://github.com/mcu-home/build-server)'s
README.

Project-wide decisions (license, repo split, versioning) are recorded in
the workbench repo:
[mcu-home/mcuhome/docs/adr](https://github.com/mcu-home/mcuhome/tree/main/docs/adr).

## Non-obvious invariants

- **Contract ownership:** the YAML configuration schema and device
  metadata are owned by the workbench and SDK repositories (ADR 0024).
  The dashboard consumes them as a versioned artifact — never duplicate
  or fork schema definitions here.
- **The dashboard never compiles** (ADR 0003), and the enforcement is
  the dependency, not a missing code path: this package **must never
  depend on `mcuhome-compiler`**. That distribution holds stages 4-5;
  without it the compiling build methods refuse in-process, naming what
  they need, which is why `requirements-dev.txt` leaves it out on
  purpose. What ADR 0013 superseded is the older phrasing "there is no
  local-build code path": there is one code path, `run_build`, and
  *where* it runs is configuration.
- **The dashboard is a standalone product** with its own release cycle,
  *and* it imports `mcuhome-workbench` in-process (ADR 0011). Both hold
  because the dependency has one direction: the dashboard declares a
  supported version range (`versions.py`) and follows the builder's
  releases; the builder never depends on the dashboard, and using the
  builder CLI must never require the dashboard or any dashboard version.
  The range names `mcuhome-workbench`, never the bare `mcuhome` — since
  firmware ADR 0020 decision 2 that is the *command line's* distribution.
- **The images are built here; the app metadata is not** (ADR 0018).
  `docker/Dockerfile` builds both published images — `standalone` for
  `docker run`, `homeassistant` for the App — because the repository that
  holds the source is the one that knows how to build it. What lives
  elsewhere is `repository.yaml` and the App's `config.yaml`, in
  [mcu-home/ha-apps-repository](https://github.com/mcu-home/ha-apps-repository),
  which builds nothing and only names the image and pins its tag. This
  replaces the older rule ("no Dockerfile here either"), whose reason —
  one packaging place for a fixed pair of Apps — went away with the
  two-App topology.
- **The dashboard and the build server do not import each other** —
  since ADR 0012 a repository boundary, not just a rule. The dashboard
  keeps its own copy of the frame codec on purpose. The cross-repository
  vocabulary comparison retires with the job protocol (ADR 0012's
  Consequences); conformance is anchored in the session protocol and the
  build-container contract instead.
- **The build server never signs, and the dashboard never compiles.**
  Each half is missing what the other has, by construction: the private
  signing key is only ever on the dashboard side (ADR 0007 decision 3,
  ADR 0008), and no toolchain is installed here. Every build method
  delivers an *unsigned* image and one host-side step signs it, here
  (ADR 0013 decision 6). The structural half of that invariant is in the
  builder: `BuildRequest` has no field a private key fits in, on any
  method — only the PEM public half travels.
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
# Backend setup. requirements-dev.txt also installs the workbench and
# model packages from the sibling checkouts (../../mcuhome and
# ../../mcuhome-sdk) — imported in-process (ADR 0011), not published yet.
cd backend && python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements-dev.txt

# Backend tests (in-process, no subprocess, no container, ~4 s)
pytest

# Python lint/format
ruff check --fix backend && ruff format backend

# Frontend (Node >= 22.13; `corepack enable` picks up the pinned pnpm)
cd frontend && pnpm install
pnpm dev            # dev server on :5173, proxying /ws /health /auth /api
pnpm check          # format, lint, types and tests — what a commit must pass
pnpm build          # emits frontend/dist

# Run it, serving the built frontend
mcuhome-dashboard --config-root ~/mcuhome-config --static-root frontend/dist

# All lint hooks
pre-commit run --all-files
```

CI (`.github/workflows/ci.yml`) runs three gates on every push and pull
request: `ruff` + REUSE, the backend `pytest`, and the frontend
`pnpm check`. It installs the workbench and model from public sibling
checkouts and deliberately leaves `mcuhome-compiler` out, so "the
dashboard never compiles" is checked in the environment the tests run
in rather than promised.

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
- Non-trivial design decisions require an ADR **draft** in
  `docs/adr/draft/`; the final ADR is written from the real result once
  the component is done (lifecycle: `docs/adr/README.md`, firmware
  ADR 0021).
