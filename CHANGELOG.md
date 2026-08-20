# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
(0.x during incubation).

## [Unreleased]

## [0.1.2] - 2026-08-20

### Changed

- **The distribution is `mcuhome-ui` and it imports as `mcuhome.ui`.**
  The console script is `mcuhome-ui`, and the repository is
  `mcu-home/mcuhome-ui`. Every MCUHome distribution now imports from the
  one `mcuhome` namespace; the scheme is recorded in the workbench
  repository's ADR 0028.
- **The images are `ghcr.io/mcu-home/ui` and
  `ghcr.io/mcu-home/ui-homeassistant-app`.** A GHCR package cannot be
  renamed, so these come into existence with this release and the
  previous names stop receiving updates. An installed App follows when
  the app repository raises its `version:`.

### Removed

- **The `local-dev` build method**, which the workbench no longer has:
  the methods are `local` and `remote`. The step-list prediction, the
  "local workspace" label in the build view and the method's mention in
  `--build-method`'s help go with it.
- **The `build-manifest.json` half of signing.** A build delivers
  `build-report.json` and nothing else, so `manifest_is_signed` is
  `build_is_signed` — it answers from the signed files beside the report,
  under the names the signer itself uses — and `sign_build` takes no
  report name any more.

### Fixed

- **Detached signing was broken and no test could see it**: the signer
  was called with a `topdir=` argument the workbench dropped in
  2026-08-15, which every test stubbed out one level above. Found while
  removing the second report shape.

### Added

- **A build states which container it is compiled in.** The workbench
  resolves the build environment before it creates a context and reports
  it as a step of its own; the browser shows the image, the Zephyr it
  carries and the moving tag it was found under. The same seam as the
  command line, with no dependency in either direction.

### Changed

- Canonical device model version **2** — the workbench's format for a
  resolved device, which this dashboard sends and a build server
  advertises support for.

## [0.1.1] - 2026-08-17

### Fixed

- **The images are built for aarch64 as well**, which is what a Home
  Assistant on a Raspberry Pi needs — an App that declares only amd64 is
  not offered there at all, so 0.1.0 was installable on none of the most
  common Home Assistant hosts. Nothing here was ever gated on the
  aarch64 evidence ADR 0003 asks for: that gate is about a *build
  server* and its toolchain, and the dashboard carries none. The
  frontend stage builds on the runner's own architecture, its output
  being the same JavaScript for both.

## [0.1.0] - 2026-08-17

The first installable release: a Home Assistant App and a Docker image.

### Added

- **The dashboard ships as two container images** (ADR 0018 draft), both
  built from `docker/Dockerfile` here and published to GHCR on a `v*`
  tag: `mcuhome-ui` for `docker run`, and
  `mcuhome-ui-homeassistant` for the Home Assistant App, whose
  metadata lives in
  [mcu-home/homeassistant-apps](https://github.com/mcu-home/homeassistant-apps).
  The App's entry point creates the project on first start and migrates
  an outdated one before the server comes up — the dashboard still does
  not manage projects; its container does. Both images default to the
  `remote` build method, because `mcuhome-compiler` is not installed in
  them and a build step in the Dockerfile fails if it ever is.

### Changed

- **A project the dashboard cannot open now says so, and lists nothing**
  (ADR 0015 draft). Only the project marker's *presence* was checked, so
  a project written by older tools listed its devices as though all were
  well and then refused every action on one of them — validating,
  saving, building. The project's version is now part of the same
  question the rest of the tree scan answers, and `tree_state` carries a
  `problem` code saying which of the five reasons it is. The wording
  happens in the browser, because it differs by deployment: standalone
  it names the command line and links the documentation, under Home
  Assistant ingress it says the App should have upgraded the project
  before the dashboard ever saw it.

### Added

- **A device can be created from the browser** (ADR 0017 draft). A form
  that offers boards, parts and endpoints — and knows none of them:
  every list comes from the builder's registry over the new
  `device/boards`, and the choices are constrained against each other
  from that same data, so a part is only offered a bus its driver
  speaks and an entry only a reading whose quantity fits the cluster.
  What it collects is written as real configuration by
  `mcuhome.workbench.api.new_device`; nothing is checked twice, so a
  name that cannot become a hostname or a device that already exists is
  refused by the builder, with the hint the command line prints, and
  nothing is written. `device/matter-pairing` draws the device's
  commissioning identity — once, into its secrets file — and answers
  with **none of the codes**: those still come from
  `device/commissioning` and from nothing else.

- **A build now shows how far along it is and where it is running** (ADR
  0016 draft). The record carries `steps` — check configuration, collect
  sources, compile, collect files, sign — with the state of each, and a
  line saying what a step established: which SDK and Zephyr line the
  build context pinned, which patches it carries, the context id, the
  board and endpoint count of the device, the container image and the
  number of parallel jobs. It comes from the workbench's `on_step` seam,
  which the dashboard was passing `None` for, so it is the same progress
  the command line renders rather than a second opinion about it. Facts
  cross to the browser through an allowlist: a remote build announces the
  build server's address, and this API still publishes only whether one
  is configured. A build cancelled before it took its first step leaves
  every step untouched — it did nothing, and blaming its first step
  would say the configuration check failed about a build that never
  looked at one.

- **Continuous integration** (`.github/workflows/ci.yml`): `ruff` + REUSE,
  the backend `pytest` and the frontend `pnpm check`, on every push and
  pull request. The test gate installs the workbench and model from
  public sibling checkouts and leaves `mcuhome-compiler` out, so "the
  dashboard never compiles" is verified in the environment the tests run
  in. `dependabot.yml` gained the `github-actions` entry that keeps the
  SHA pins current.

### Fixed

- **The test suite follows the project format instead of restating it.**
  The fixture wrote the project marker by hand, so it stayed at version 0
  when the workbench moved to version 1 and 47 tests failed on a project
  the workbench would not resolve. It now creates the project with the
  workbench's own `init_project`, which cannot drift.
- **The build directory is held across signing.** `run_build` gives the
  directory back when the compile ends, leaving the host-side signing and
  OTA wrapping outside its guard; the dashboard now holds the same lock
  across both, as the command line does. Each build already owns its
  directory, so this is what makes the later operations on an existing
  one — flashing it, cleaning it — safe to add.
- **The declared Node floor was below what the pinned pnpm accepts.**
  `engines.node` said `>=22.12.0`, but pnpm 11.20.0 refuses to start
  below 22.13. The floor is now `>=22.13.0`, and CI runs exactly that
  version so the declaration cannot quietly stop being true again.
- Two more flaky tests, of the same shape as the one below: the
  retention rule of ADR 0013 decision 5 also runs in the window between
  a record's terminal state and the slot being handed back, so a test
  asserting on **what is left on disk** was racing a `rmtree` that had
  not run yet. Present on `main` before this change (one failure in
  twenty runs) and now waited for properly.
- A flaky test: a build record reaches its terminal state a few
  statements before the registry hands the slot back, so a test asserting
  on the slot had to wait for the later of the two. No client could
  observe the gap — the finished record is published after the slot is
  cleared.

### Security

- **The Home Assistant ingress site is now admin-only** (ADR 0014). Every
  Home Assistant user who could open the ingress panel had full trust and
  could read a device's Matter commissioning passcode, download
  passcode-bearing build artifacts, edit devices and start builds. The
  ingress site now derives the user's admin status from the Supervisor —
  the peer check authenticates `X-Remote-User-Name`, and the Supervisor's
  authenticated `/auth/list` (over `SUPERVISOR_TOKEN`) turns that username
  into the admin decision, never a client-settable header. `device/save`,
  `device/commissioning`, `build/start`, `build/cancel` and the artifact
  download route are refused for non-admins; read-only views stay open.
  The check **fails closed** (unresolved status ⇒ non-admin), and
  `server/info` now reports `identity.is_admin`. The public (password)
  site is unchanged.
- **Failed-login throttling on the public site** (ADR 0014). Both password
  paths (`POST /auth/login` and the bearer token) now share a per-source
  lockout with exponential backoff answered as `429` + `Retry-After`, plus
  a process-wide backstop for distributed guessing.
- **Concurrency limits** to keep one authenticated client from stalling
  every socket: a per-connection in-flight command cap (backpressure on
  the reader) and a process-wide gate on the CPU-bound `device/validate`
  and `device/commissioning` work, so it cannot exhaust the shared thread
  pool.

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
    - Superseded within this same unreleased block by ADR 0013 (see
      **Added**): building, log streaming and artifact download came
      back — over `mcuhome.workbench.api.run_build`, not over a
      protocol client. The vocabulary above stays removed; the verbs
      that returned are `build/start`, `build/status`, `build/log`,
      `build/cancel` and `build/subscribe`, and they answer with build
      *records* rather than job frames. Flashing is still absent
      (ADR 0010).
  - Kept, because ADR 0012 decision 3 carries them forward and the
    session client needs them: `--build-server-url`/`-token`/
    `-token-file` and the `/share/mcuhome/build-server.token`
    auto-pairing (ADR 0006 decision 8), now covered by
    `backend/tests/test_config.py` since no client exercises them;
    `signing.py` in full (ADR 0007/0008 — it had no caller then and was
    not dead code; ADR 0013 gave it one); the event bus; the frame
    envelope; the version-range machinery of ADR 0011. The URL
    normalization that turned one
    configured address into its `http` and `ws` forms moved from
    `buildclient.py` into `config.py`, where the address is configured.
  - `resolve_build_server_token` takes its pairing-file default at call
    time instead of in its signature, so the pairing path is testable
    without a real Home Assistant share.
- Backend test suite: 168 → 151 tests. The 27 build tests of
  `tests/test_builds.py` went with the client; 10 new tests in
  `tests/test_config.py` cover the settings that outlived it. (ADR 0013
  then took it to 199, with a new `tests/test_builds.py` about builds
  rather than about jobs.)

### Added

- **Building, over the builder package rather than over a protocol**
  (ADR 0013). The successor the entry above was waiting for turned out
  to be already written: firmware ADR 0020 / E64 put the three build
  methods behind `mcuhome.workbench.api.run_build`, in the package this
  dashboard already imports in-process. So there is still no build
  protocol client here — and the dashboard builds.
  - `build/start`, `build/status`, `build/log`, `build/cancel` and
    `build/subscribe` on `/ws`; the `builds` event topic with
    `build_started`, `build_changed` and `build_output`; a `build` block
    in `server/info` (configured method, the builder's default, every
    method it has, whether a server is configured — never the address,
    never the token).
  - `GET /api/builds/{build}/artifacts/{path}` is back, with the refusal
    the removal notice demanded: an unknown build, a missing file and a
    path escaping the build's directory are all **404 and never 403**.
    It serves **only what the record declares** in `artifacts` — a build
    method's scratch area lives inside the build directory and holds the
    resolved model, so serving the directory would have handed out the
    device's Matter pairing tuple over a plain `GET`.
  - Artifacts live at `<data-dir>/builds/<device>/<build id>/`: one
    directory per **build**, because the URL space is keyed by build id
    and a record's artifacts have to be the files behind that id. A
    build that succeeds removes the older directories of its device; one
    that did not succeed removes its own.
  - **Which build method runs is deployment configuration**
    (`--build-method`), not a decision taken in this code. The dashboard
    neither subsets the builder's methods nor validates a name against a
    copy of the list. ADR 0003 is unchanged and now *checkable*:
    `mcuhome-compiler` is deliberately not installed, so the compiling
    methods refuse in-process, naming the distribution they need.
  - **Build logs are resumable.** Every `build_output` carries the line
    offset it starts at, and `build/log` serves any suffix from any
    offset with a `truncated` flag. This is the property `events.py` and
    the README both recorded as having to come back with any successor,
    because the bus drops the oldest events for a subscriber that fell
    behind and build output is what makes one fall behind. Offsets count
    lines rather than bytes, and output is batched so the drop is rare
    as well as recoverable.
  - `signing.py` gets its caller and loses its subprocess: the detached
    signature is applied in-process through `mcuhome.workbench.imgtool`
    — the same library `mcuhome sign` runs — because that command
    belongs to the CLI distribution this package does not install. A
    Matter `.ota` is wrapped around the freshly signed image where the
    device can take one. The signer runs with `create=False`: the key is
    created, if at all, by the call that produces the build's public
    input, and a key that vanished in between is a loud failed build
    rather than a second key. A key-custody failure names the key by its
    role and never by its path — a build record's `errors` reaches every
    subscribed tab.
  - Settings reactivated and given a reader: `--build-server-url`,
    `--build-server-token`, `--build-server-token-file` and the `/share`
    auto-pairing file now map onto `BuildRequest.server`/`.token`. New:
    `--build-method`, `--build-jobs`, `--sdk-source`. The `mcuhome`
    command line's `build-servers.toml` ladder is deliberately **not**
    read (ADR 0013 decision 2).
  - One build at a time for the whole process; a second start is refused
    with `conflict` carrying the record that holds the slot. **The slot
    belongs to the work**: cancelling stops this process waiting, not
    the container it started, so the record ends `cancelled` at once
    while the slot stays taken until the work really ends — and the
    refusal in that window says which of the two it is.
  - 48 new backend tests (`tests/test_builds.py`, plus one in
    `tests/test_versions.py`): 151 → 199. Two of them drive the *real*
    `run_build` and assert that its refusals — a missing compiler
    distribution, `remote` without a server address — arrive as rendered
    build errors carrying the builder's own fix, never as a crash.

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
- Backend skeleton (`backend/mcuhome/ui/`): aiohttp application
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

- **The build server moved to its own repository** ([mcu-home/mcuhome-buildserver](https://github.com/mcu-home/mcuhome-buildserver),
  ADR 0012): `buildserver/` and its test suite leave this repository.
  The dashboard kept its client at the time of the move; the client was
  removed shortly afterwards — see **Removed**, above. The
  frame-vocabulary cross-check retires with it (ADR 0012's
  Consequences).
- `mcuhome/ui/builder.py` consumes `mcuhome.api` — the builder's
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
  `mcuhome-ui` entry point and pytest wiring. The `mcuhome`
  builder package is installed from the sibling checkout via
  `requirements-dev.txt` until it is published; the supported version
  range is declared in `mcuhome/ui/versions.py` and asserted at
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

