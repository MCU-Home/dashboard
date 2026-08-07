# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
(0.x during incubation).

## [Unreleased]

### Added

- Backend skeleton (`backend/mcuhome_dashboard/`): aiohttp application
  with the two sites of ADR 0009 (an ingress site that trusts the
  Supervisor gateway, a public site that authenticates itself), the
  WebSocket-first API of ADR 0004 on a single `/ws` endpoint, and an
  in-process event bus.
  - Commands: `server/info`, `ping`, `device/list`, `device/get`,
    `device/validate`, `config/subscribe`, `subscribe_events`,
    `unsubscribe_events`. Lists follow snapshot-then-events.
  - The configuration tree is watched by an mtime poll with content
    hashing, so a touch or an identical rewrite produces no event.
  - `device/validate` runs the builder in-process (ADR 0011) and returns
    its errors as structured diagnostics with file, line, column, dotted
    key and fix hint.
  - Static assets with SPA fallback and per-request `X-Ingress-Path`
    handling; a placeholder frontend shell that proves the plumbing.
  - REST is limited to `GET /health` and the public site's login and
    logout, the latter CSRF-protected.
- Backend test suite (111 tests) on aiohttp's test client — no
  subprocesses, no containers.
- Initial project scaffold: backend package skeleton, frontend placeholder,
  community health files and architecture decision records.
- Design phase: ADRs 0003–0011 — two Home Assistant Apps with the
  dashboard never compiling (0003), aiohttp backend with a
  WebSocket-first API (0004), Lit 3 + webawesome + CodeMirror 6 frontend
  (0005), the build-service protocol (0006), wire content and credential
  exposure (0007), state layout and signing-key custody (0008),
  authentication per deployment (0009), the flash-flow ladder (0010),
  and the builder coupling with the firmware-side interface contract
  (0011).

### Changed

- Backend requires Python ≥ 3.13 (was ≥ 3.11) — ADR 0004.
- Backend depends on `aiohttp` and gains a `dev` extra, a
  `mcuhome-dashboard` entry point and pytest wiring. The `mcuhome`
  builder package is installed from the sibling checkout via
  `requirements-dev.txt` until it is published; the supported version
  range is declared in `mcuhome_dashboard/versions.py` and asserted at
  startup.
- AGENTS.md reflects the design decisions: "App" instead of "Add-on",
  two-App packaging, always-remote builds, and the in-process builder
  import with a declared version range.
