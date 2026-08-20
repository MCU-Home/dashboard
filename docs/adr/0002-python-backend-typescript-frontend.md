# 0002 — Python backend with TypeScript frontend

- Status: accepted
- Date: 2026-08-02
- Finalized: 2026-08-14

## Context

The dashboard orchestrates firmware builds. The entire Zephyr toolchain
is Python-native — west, twister, and MCUHome's own build tooling, which
was one `mcuhome` package when this was decided and is the
`mcuhome-workbench` distribution since firmware ADR 0020 named the
packages — so a Python backend integrates with it directly instead of
through subprocess/API shims. ESPHome's 2026 Device Builder — their
from-scratch dashboard rewrite — validated exactly this split: Python
backend, TypeScript SPA frontend, Apache-2.0.

The alternative (pure Node/TypeScript stack) would unify web tooling but
put a process boundary between the dashboard and every builder
interaction.

## Decision

- **Backend:** Python (`backend/`, package `mcuhome.ui`), driving
  the build tooling natively — the in-process import that ADR 0011 later
  made a rule, today of `mcuhome-workbench`. The version floor was
  ≥ 3.11 at decision time; ADR 0004 raised it to ≥ 3.13 together with
  the framework choice, and `backend/pyproject.toml` carries that.
- **Frontend:** TypeScript single-page application (`frontend/`).
- **Deferred to the design phase, each to its own ADR — and since
  decided:** the backend web framework and the backend↔frontend API
  shape went to ADR 0004 (aiohttp, WebSocket-first); the frontend
  framework and build tooling went to ADR 0005 (Lit 3, webawesome,
  CodeMirror 6, Vite). Until then the rule was that `frontend/` stays a
  placeholder — no package.json, no lockfile, no lint config ahead of
  the framework ADR — and it held until ADR 0005's first frontend
  commit.

## Consequences

- Native integration with west/twister/the builder package; one language
  boundary (HTTP/WebSocket API) instead of two.
- Two toolchains in one repo (pip + pnpm), each with its own lint/format
  chain — run in CI (ruff for the backend; prettier/eslint
  through `pnpm exec` for the frontend) and mirrored in dependabot configuration.
  There is no CI yet; when it arrives it mirrors the same split.
- Deferring the frontend scaffolding did its job: the tooling arrived
  with the framework decision rather than before it, so none of it was
  thrown away.
