# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
(0.x during incubation).

## [Unreleased]

### Added

- **Build server** (`buildserver/`, package `mcuhome_buildserver`): the
  fat half of ADR 0003's two-App topology, as a headless aiohttp service
  speaking the protocol of ADR 0006.
  - Frames `submit_job`, `cancel_job`, `follow_job`,
    `download_artifacts` and `queue_status` on one `/ws` endpoint, with
    `job_state_changed` and `job_output` events.
  - `GET /capabilities` for negotiation before a job exists: builder
    version, `model_version` range, architecture, job slots, image tag,
    workspace, and what the installed builder can actually do.
  - Bearer-token authentication with a constant-time comparison, from
    the command line, the environment or a file; one is generated and
    logged when none is configured, and published to
    `/share/mcuhome/build-server.token` for a same-host App pair
    (ADR 0006 decision 8).
  - A job queue with **compile lane 1** as a hard default (ADR 0006
    decision 5), raised only by `--slots` and never silently.
  - Builds run as a subprocess of `mcuhome build --json` in their own
    process group, so `cancel_job` stops exactly one job's whole process
    tree — the property that cannot be had in-process.
  - A job is a directory: the record, the submitted model, the log
    sidecar and the build tree. Records survive a restart; a job that
    was queued or running when the process stopped comes back as
    `interrupted` rather than `failed`. Retention is a per-server cap
    plus a time-to-live (ADR 0008 decision 4).
  - Log sidecars with the resumable follow of ADR 0006 decision 6:
    history-then-live from a byte offset the client states, with the
    subscription registered before the history is read so the join
    cannot lose a chunk.
  - Artifacts in chunks, each with its own SHA-256, indexed by the build
    manifest's file list — which is also the whitelist, so path
    traversal is unreachable rather than defended against.
  - The submitted `device-model.json` is written mode 0600 into a 0700
    job directory and deleted when the build process exits: it carries
    the device's Matter passcode (ADR 0007 decision 2), and the job
    record and the log never do.
- Backend build-server client (`buildclient.py`) and the commands
  `build/submit`, `build/cancel`, `build/status`, `build/log` and
  `build/artifacts`, plus a `builds` event topic carrying
  `build_job_changed` and `build_job_output`.
  - `build/submit` resolves the device in-process and sends only the
    resolved model and the signing **public** key (ADR 0007); a
    configuration that does not resolve is a successful command carrying
    diagnostics, and nothing crosses the wire.
  - `GET /capabilities` is checked before every submission; a
    `model_version` or builder mismatch is refused with the new
    `unsupported` error code, naming both sides' numbers.
  - Artifacts are downloaded, verified against both the per-chunk and
    the per-file hash, and **signed locally** with `mcuhome sign`
    (`signing.py`, ADR 0007 decision 3 / ADR 0008 decision 2) as soon as
    a build succeeds.
  - `GET /api/builds/{job}/artifacts/{path}` serves the local, signed
    copy — REST because a browser download needs a URL.
  - With no build server configured, every build command refuses with a
    message naming the two environment variables; two Apps on one Home
    Assistant instance pair themselves through the shared token file.
- Build-server test suite (105 tests) driving a fake builder subprocess:
  no real build, no container, no west.

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

- `mcuhome_dashboard/builder.py` consumes `mcuhome.api` — the builder's
  supported programmatic surface since its Block 0 — instead of reaching
  into the package: `load_model`, `open_config_tree`, `error_dicts` and
  `ConfigError.to_dict` are the builder's own now, and the local
  `_relative`/`error_to_dict` workarounds are gone. Two helpers still
  import past `api` (commissioning codes, unresolved YAML summaries) and
  say so in the module docstring.
- The frame vocabulary gained an `unsupported` error code and
  `ProtocolError` gained structured detail fields, so a refusal carries
  its numbers as data and not only in its sentence.
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

