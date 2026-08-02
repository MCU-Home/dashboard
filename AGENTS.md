# AGENTS.md — MCUHome Dashboard

Guide for AI coding agents (and new human contributors) working in this
repository.

## What this project is

The web interface for [MCUHome](https://github.com/mcu-home/mcuhome): a
standalone product to create, build, flash and manage Zephyr-based smart
home devices. Distribution targets: Home Assistant add-on (packaged in a
separate future `home-assistant-addon` repo), Docker image, plain Python
app.

**Current phase: pre-alpha scaffold.** The architecture is being designed;
there is no functional code yet. Check `docs/adr/` before assuming any
design decision.

## Repository map

| Path | Role |
|---|---|
| `backend/` | Python backend (`mcuhome_dashboard` package): build orchestration, device management, frontend API |
| `frontend/` | TypeScript SPA — framework selection pending (ADR 0002) |
| `docs/adr/` | Dashboard-specific architecture decision records |

Project-wide decisions (license, repo split, versioning) are recorded in
the firmware repo:
[mcu-home/mcuhome/docs/adr](https://github.com/mcu-home/mcuhome/tree/main/docs/adr).

## Non-obvious invariants

- **Contract ownership:** the YAML configuration schema and device
  metadata are owned by the firmware repository. The dashboard consumes
  them as a versioned artifact — never duplicate or fork schema definitions
  here.
- **The dashboard is a standalone product** with its own release cycle.
  Using the firmware builder CLI directly must never require the
  dashboard, or any specific dashboard version.
- **HA add-on packaging does not live here** — no `config.yaml`/Dockerfile
  add-on files in this repo; they go to the future `home-assistant-addon`
  repo.
- Stored device configurations may contain secrets (WiFi credentials,
  Thread network keys, Matter setup codes) — treat all config handling as
  security-sensitive (see SECURITY.md).

## Commands

```sh
# Backend setup
cd backend && python3 -m venv .venv && . .venv/bin/activate && pip install -e .

# Python lint/format
ruff check --fix . && ruff format .

# All lint hooks
pre-commit run --all-files
```

Frontend commands follow once the framework is chosen (ADR 0002). There is
no CI yet — it is added together with the first testable code.

## Coding standards

- **Python:** ruff (lint + format), line length 100, target Python 3.11+.
- **TypeScript:** tooling defined with the framework decision.
- **Licensing:** everything is Apache-2.0 with SPDX headers in every new
  file; the repo is REUSE-compliant (`reuse lint` runs in pre-commit).

## Commit and PR conventions

- Conventional Commits (`feat:`, `fix:`, `docs:`, `chore:`, …).
- Every commit is DCO-signed-off: `git commit -s`.
- Default branch is `main`; short-lived `feat/…`, `fix/…` branches.
- Non-trivial design decisions require an ADR in `docs/adr/`.
