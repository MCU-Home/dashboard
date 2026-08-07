# AGENTS.md — MCUHome Dashboard

Guide for AI coding agents (and new human contributors) working in this
repository.

## What this project is

The web interface for [MCUHome](https://github.com/mcu-home/mcuhome): a
standalone product to create, build, flash and manage Zephyr-based smart
home devices. Distribution targets: **Home Assistant App** (packaged in a
separate future packaging repo), Docker image, plain Python app.

**Current phase: pre-alpha scaffold.** The design phase is complete —
ADRs 0003–0011 fix the deployment topology, the backend and frontend
stacks, the build-service protocol, state layout, auth, flash flow and
the builder coupling. There is no functional code yet. Check `docs/adr/`
before assuming any design decision.

The architecture in one line: **two Home Assistant Apps** — the thin
`mcuhome-dashboard` (this repo) and the fat `mcuhome-build-server` —
because the dashboard never compiles (ADR 0003).

## Repository map

| Path | Role |
|---|---|
| `backend/` | Python ≥ 3.13 backend (`mcuhome_dashboard` package), aiohttp, WebSocket-first API (ADR 0004) |
| `frontend/` | TypeScript SPA: Lit 3 + `@home-assistant/webawesome` + CodeMirror 6, built with Vite (ADR 0005) |
| `docs/adr/` | Dashboard-specific architecture decision records |

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
- **Say "App", not "Add-on"** — Home Assistant renamed them in 2026.2.
  Applies to every user-facing string, screenshot and document.
- Stored device configurations may contain secrets (WiFi credentials,
  Thread network keys, Matter setup codes) — treat all config handling as
  security-sensitive (see SECURITY.md). The build server necessarily sees
  a device's commissioning credentials (ADR 0007); the firmware signing
  key never leaves this side (ADR 0008).

## Commands

```sh
# Backend setup
cd backend && python3 -m venv .venv && . .venv/bin/activate && pip install -e .

# Python lint/format
ruff check --fix . && ruff format .

# All lint hooks
pre-commit run --all-files
```

Frontend commands land with the first frontend commit (stack fixed in
ADR 0005). There is no CI yet — it is added together with the first
testable code.

## Coding standards

- **Python:** ruff (lint + format), line length 100, target Python 3.13+.
- **TypeScript:** Lit 3 + Vite (ADR 0005); eslint/prettier configuration
  lands with the frontend scaffolding.
- **Licensing:** everything is Apache-2.0 with SPDX headers in every new
  file; the repo is REUSE-compliant (`reuse lint` runs in pre-commit).

## Commit and PR conventions

- Conventional Commits (`feat:`, `fix:`, `docs:`, `chore:`, …).
- Every commit is DCO-signed-off: `git commit -s`.
- Default branch is `main`; short-lived `feat/…`, `fix/…` branches.
- Non-trivial design decisions require an ADR in `docs/adr/`.
