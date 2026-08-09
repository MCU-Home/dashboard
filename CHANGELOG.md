# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
(0.x during incubation).

## [Unreleased]

### Removed

- **The build-server client and everything that existed only for it**
  (ADR 0012 decision 3). The job-frame vocabulary of ADR 0006 is
  replaced by the session protocol of firmware ADR 0019, and the
  decision was to dismantle rather than migrate — there was no
  session-protocol client to migrate to, and a client left speaking a
  retired vocabulary would have been a second thing to remove later.
  Gone: `buildclient.py`; the commands `build/submit`, `build/cancel`,
  `build/status`, `build/log` and `build/artifacts`; the `builds` event
  topic with `build_job_changed` and `build_job_output`; the
  `build_server` block of `server/info`; the REST endpoint
  `GET /api/builds/{job}/artifacts/{path}`; the `unsupported` error
  code, whose only producer was the ADR 0006 decision 4 negotiation.
  - **The dashboard therefore cannot build, flash, stream a build log
    or download an artifact.** No stub stands in for any of it — a
    command that disappeared disappeared, and `server/info` no longer
    advertises a build server it cannot reach. The startup log says so
    at warning level. Editing, validation, the device list and the
    commissioning codes are untouched.
  - Kept, because ADR 0012 decision 3 carries them forward and the
    session client needs them: `--build-server-url`/`-token`/
    `-token-file` and the `/share/mcuhome/build-server.token`
    auto-pairing (ADR 0006 decision 8), now covered by
    `backend/tests/test_config.py` since no client exercises them;
    `signing.py` in full (ADR 0007/0008 — it has no caller today and is
    not dead code); the event bus; the frame envelope; the version-range
    machinery of ADR 0011. The URL normalization that turned one
    configured address into its `http` and `ws` forms moved from
    `buildclient.py` into `config.py`, where the address is configured.
  - `resolve_build_server_token` takes its pairing-file default at call
    time instead of in its signature, so the pairing path is testable
    without a real Home Assistant share.
- Backend test suite: 168 → 151 tests. The 27 build tests of
  `tests/test_builds.py` went with the client; 10 new tests in
  `tests/test_config.py` cover the settings that outlived it.

### Added

- Frontend (`frontend/`): the single-page application of ADR 0005 — Lit 3,
  `@home-assistant/webawesome`, CodeMirror 6, TypeScript, Vite, vitest,
  eslint and prettier.
  - Device list, live from `config/subscribe` — no polling and no
    re-fetch — with a validity badge per device, filled in one
    `device/validate` at a time.
  - Device editor: CodeMirror 6 with YAML syntax, the builder's
    diagnostics as gutter markers with their fix hints, and a problem
    panel that moves the cursor to the line it names.
  - Saving, with content-hash conflict detection: a file that changed on
    disk since it was opened offers reload or overwrite rather than
    silently discarding somebody's work.
  - Commissioning view: the QR code and manual pairing code, rendered in
    the browser and only after an explicit "show commissioning codes" —
    the payload contains the device's passcode.
  - One WebSocket for everything, with request/response correlation,
    reconnect-and-resubscribe, request timeouts, and a `/health` probe
    that tells a refused connection apart from an unreachable server.
  - Base-path aware throughout (ingress, reverse-proxy sub-path, bare
    root from one build), light/dark by `prefers-color-scheme` with a
    remembered override, and a login form for the public site.
  - Frontend test suite (117 vitest tests).
- Backend `device/save`: writes one device's configuration file, replaced
  in one step, with `expected_hash` conflict detection and a new
  `conflict` error code. It does not validate — `device/validate` is the
  separate command for that.
- Backend `device/commissioning`: `qr_payload`, `manual_code`,
  `discriminator` and `test_credentials` for one device, the same data
  `mcuhome validate` prints. A command of its own rather than a field of
  the device summary, because the QR payload contains the passcode and
  must not ride along on every list response (ADR 0007).
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
- Backend test suite (137 tests) on aiohttp's test client — no
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

- **The build server moved to its own repository** ([mcu-home/build-server](https://github.com/mcu-home/build-server),
  ADR 0012): `buildserver/` and its test suite leave this repository.
  The dashboard kept its client at the time of the move; the client was
  removed shortly afterwards — see **Removed**, above. The
  frame-vocabulary cross-check retires with it (ADR 0012's
  Consequences).
- `mcuhome_dashboard/builder.py` consumes `mcuhome.api` — the builder's
  supported programmatic surface since its Block 0 — instead of reaching
  into the package: `load_model`, `open_config_tree`, `error_dicts` and
  `ConfigError.to_dict` are the builder's own now, and the local
  `_relative`/`error_to_dict` workarounds are gone. Two helpers still
  import past `api` (commissioning codes, unresolved YAML summaries) and
  say so in the module docstring.
- `ProtocolError` gained structured detail fields, so a refusal carries
  its numbers as data and not only in its sentence. (The `unsupported`
  error code added alongside them has since been removed with the
  negotiation it served — see **Removed**, above.)
- Configuration: `--build-server-url`, `--build-server-token`,
  `--build-server-token-file` and `--data-dir`, each with a
  `MCUHOME_DASHBOARD_`-prefixed environment form.
- ADR 0007 records detached signing as implemented rather than pending.
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
- The backend's default static root now serves a diagnostic page rather
  than a placeholder shell: the built frontend is pointed at with
  `--static-root ../frontend/dist` until packaging copies it into the
  wheel.
- `pre-commit` runs prettier and eslint over `frontend/`, discharging
  ADR 0005's "the frontend hooks land with the first frontend commit".

### Fixed

- The ingress base-path injection no longer keys off a `<head>` inside
  an HTML comment, and no longer mistakes `<header>` for `<head>`. Both
  would have injected `<base>` where the browser never parses it, and
  the page would have loaded every asset from the wrong prefix without
  any visible error.

