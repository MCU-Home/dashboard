# 0002 — Python backend with TypeScript frontend

- Status: accepted
- Date: 2026-08-02

## Context

The dashboard orchestrates firmware builds. The entire Zephyr toolchain is
Python-native (west, twister, the `mcuhome` builder package), so a Python
backend integrates with it directly instead of through subprocess/API
shims. ESPHome's 2026 Device Builder — their from-scratch dashboard
rewrite — validated exactly this split: Python backend, TypeScript SPA
frontend, Apache-2.0.

The alternative (pure Node/TypeScript stack) would unify web tooling but
put a process boundary between the dashboard and every builder interaction.

## Decision

- **Backend:** Python ≥ 3.11 (`backend/`, package `mcuhome_dashboard`),
  driving the `mcuhome` builder natively.
- **Frontend:** TypeScript single-page application (`frontend/`).
- **Deferred to the design phase:** the frontend framework and build
  tooling, the backend web framework, and the backend↔frontend API shape.
  Each gets its own ADR when decided.

## Consequences

- Native integration with west/twister/builder; one language boundary
  (HTTP/WebSocket API) instead of two.
- Two toolchains in one repo (pip + npm) — mirrored in dependabot,
  pre-commit and CI configuration.
- Frontend scaffolding (package.json, lockfile, lint config) waits for the
  framework ADR; `frontend/` stays a placeholder until then.
