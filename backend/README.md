# mcuhome-dashboard (backend)

Python backend of the [MCUHome Dashboard](https://github.com/mcu-home/dashboard):
device management and the API the frontend consumes. It **never compiles
firmware** itself (ADR 0003).

Current state: the device list, the configuration tree watcher,
validation, editing and the commissioning codes work end to end.

> **This backend cannot build firmware.** Not "cannot yet get a build to
> run" — there is no build path in the package at all. The job-protocol
> client of ADR 0006 was **removed** when
> [ADR 0012](../docs/adr/0012-build-server-extraction.md) decision 3 made
> the session protocol of firmware ADR 0019 the way a dashboard reaches a
> build server, and the session client that replaces it has not been
> written. So: no `build/*` commands on `/ws`, no `builds` event topic,
> no artifact download and no `build_server` block in `server/info`. What
> survived is what that decision carries forward — the transport
> settings, the signing module, the event bus and the frame envelope.

## Running it

```sh
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements-dev.txt      # installs the builder from ../../mcuhome
mcuhome-dashboard --config-root ~/mcuhome-config
```

Then open <http://127.0.0.1:8099>. Every option also has an environment
variable, prefixed `MCUHOME_DASHBOARD_` (`--config-root` →
`MCUHOME_DASHBOARD_CONFIG_ROOT`); the command line wins. `--help` lists
them all.

### The two sites (ADR 0009)

The process serves up to two HTTP sites with two different trust
assumptions, and which of them exist is a matter of configuration:

| Site | Enabled by | Binds | Authentication |
|---|---|---|---|
| public | on by default | `--host` (default `127.0.0.1`), `--port` | password, see below |
| ingress | `--ingress-port` | loopback + this container's Supervisor-network address | none of its own — Home Assistant already authenticated the user |

The ingress site additionally refuses any peer that is neither loopback
nor the Supervisor gateway (`172.30.32.2`). Its `X-Remote-User-*`
headers are display-only and never an authorization input.

**Password rules.** A loopback-only bind runs without a password.
Binding anything else requires one: pass `MCUHOME_DASHBOARD_PASSWORD`,
or one is generated at startup and printed to the log as a warning.
There is no configuration in which the dashboard listens on a network
interface without authentication.

> An authenticated dashboard session is equivalent to shell access on
> the build server and holds the firmware signing key.

## The API

One WebSocket endpoint, `/ws`, carries everything (ADR 0004). REST
exists only where a browser primitive needs a URL.

### Frames

```jsonc
// client → server
{"id": "7", "type": "device/list", "payload": {}}
// server → client, answering it
{"id": "7", "type": "result", "payload": {"devices": [/* … */]}}
{"id": "7", "type": "error",  "error": {"code": "not_found", "message": "…"}}
// server → client, unprompted — no id, because it answers nothing
{"type": "event", "event": "device_changed", "payload": {"device": {/* … */}}}
```

An `error` frame means the command could not be carried out. A
configuration that fails to validate is **not** an error frame: it is a
successful `device/validate` whose result says `ok: false` and carries
the diagnostics.

Error codes: `bad_request`, `unknown_command`, `not_found`,
`unauthorized`, `unavailable`, `conflict`, `internal_error`. An error
object may carry extra fields beside `code` and `message`, so a refusal
with numbers in it hands them over as data and not only in its sentence.

There is no `unsupported` code any more: it existed for the build-server
version negotiation of ADR 0006 decision 4, and both sides of that
negotiation went with the job protocol (ADR 0012 decision 3).

### Commands

| Command | Payload | Result |
|---|---|---|
| `server/info` | — | dashboard and builder versions, the supported builder range, the `model_version` range, trust mode, ingress base path, identity, tree state. **No `build_server` block** — see above |
| `ping` | — | `{"pong": true, "time": …}` |
| `device/list` | — | every device in the tree, with a content hash and an unresolved summary |
| `device/get` | `{"name"}` | the raw YAML as it is on disk, plus the summary |
| `device/save` | `{"name", "content", "expected_hash"?}` | `{"name", "device": entry, "content_hash"}` |
| `device/validate` | `{"name"}` | `{"ok", "errors": [...], "device": summary\|null}` |
| `device/commissioning` | `{"name"}` | `{"ok", "errors": [...], "commissioning": codes\|null}` |
| `config/subscribe` | — | the `device/list` snapshot, and every later change as an event |
| `subscribe_events` | `{"topics": ["devices"]}` | the topics this socket now receives |
| `unsubscribe_events` | `{"topics": [...]}` | the topics that remain |

Lists follow **snapshot-then-events**: the subscription's result is the
current state and every change after it arrives as an event. Clients do
not poll and do not re-fetch.

Events on the `devices` topic: `device_added`, `device_changed`,
`device_removed`, `tree_state`, plus `events_dropped` when a connection
fell so far behind that the server discarded events for it — the cue to
re-subscribe rather than trust what is held.

The `builds` topic is gone. Its two events — `build_job_changed` and
`build_job_output` — belonged to the job protocol. The session
protocol's typed progress events will register a topic of their own; one
property of the old pair has to come back with them, because the bus
drops the oldest events for a subscriber that fell behind and build
output is exactly the traffic that makes one fall behind: a progress
event has to say the position it starts at, or a client cannot notice
the hole.

## Building: it does not (ADR 0012 decision 3)

There is no build path in this package. The client that used to drive
one spoke the job vocabulary of ADR 0006 — `submit_job`, `cancel_job`,
`follow_job`, `download_artifacts`, `queue_status` and
`GET /capabilities` — and ADR 0012 decision 3 replaced that vocabulary
with the session verbs of firmware ADR 0019. The decision was to
dismantle rather than migrate, so the client, the five `build/*`
commands, the `builds` topic and the artifact endpoint were removed and
nothing stands in for them.

What that costs, listed so nobody looks for it: a device cannot be
compiled, a build log cannot be streamed, an artifact cannot be
downloaded, and nothing is signed — because nothing produces anything to
sign. Editing, validation, the device list and the commissioning codes
are untouched; they never went near a build server.

What survives, and why:

| Kept | Why |
|---|---|
| `--build-server-url` / `-token` / `-token-file`, the pairing file | ADR 0012 decision 3 carries ADR 0006's transport and threat model forward unchanged. Dropping the pairing would silently un-pair every existing installation |
| `signing.py` | ADR 0012 decision 3: "the dashboard keeps what only it has — user key handling and detached signing" (ADR 0007/0008). It has no caller today |
| the event bus, the frame envelope, `versions.py` | Protocol-independent; ADR 0011 is untouched |

**These settings are inert.** A configured URL and token change nothing
in this release — the startup log says so at warning level rather than
letting an operator wait for a build button that does not exist.

> **A build server learns the Matter commissioning passcode of every
> device it builds** — those credentials are compile-time Kconfig (ADR
> 0007 decision 2). Operate it as a trusted machine. That warning used
> to be carried to the user by `server/info` and `build/status`; with
> both gone it lives here and in `--help` until the session client can
> put it in front of whoever configures a server.

### Configuring one (for the client that does not exist yet)

| Option | Environment | What it is |
|---|---|---|
| `--build-server-url` | `MCUHOME_DASHBOARD_BUILD_SERVER_URL` | `http(s)://` or `ws(s)://`; both forms are accepted and normalized |
| `--build-server-token` | `MCUHOME_DASHBOARD_BUILD_SERVER_TOKEN` | the bearer token the build server logged at startup |
| `--build-server-token-file` | `MCUHOME_DASHBOARD_BUILD_SERVER_TOKEN_FILE` | read it from a file instead |
| `--data-dir` | `MCUHOME_DASHBOARD_DATA_DIR` | the signing key, and the artifact directory nothing writes to yet (default `/data` in an App) |

**Two Apps on one Home Assistant instance pair themselves** (ADR 0006
decision 8, carried forward by ADR 0012 decision 3): the build server
writes its token to `/share/mcuhome/build-server.token`, the dashboard
reads it from there and assumes `http://127.0.0.1:8100`. Nothing is
configured by hand, and an explicit URL or token always wins over the
pairing file. `tests/test_config.py` is what keeps that true while there
is no client to exercise it.

With nothing configured, nothing happens — which is also what happens
with everything configured, until the session client lands.

### Writing: `device/save` and the `conflict` code

The dashboard is not the only writer of the configuration tree, so the
same content hash that `device/get` hands out is what a save presents
back as `expected_hash`: "I edited *that* version". If the file changed
since, the write is refused with `conflict` and the client re-reads with
`device/get` instead of silently discarding somebody else's work.
Omitting `expected_hash` is a deliberate force-overwrite — how a client
that has resolved the conflict retries.

The file is replaced with a sibling-plus-rename, so neither the tree poll
nor another editor ever sees a half-written configuration. Saving does
**not** validate: half-finished YAML is the normal state of an open
editor, and `device/validate` is the separate command that says whether
what was saved is good. Creating a device is not this command's job —
that is `mcuhome new` (ADR 0011).

### Commissioning codes

`device/commissioning` returns `qr_payload`, `manual_code`,
`discriminator` and `test_credentials` — the same data
`mcuhome validate` prints on a terminal.

It is a command of its own rather than a field of the device summary
because **the QR payload contains the passcode**. That is why a
commissioning view is worth having and why the codes may not ride along
on `device/list` or `device/validate`, which every open tab receives
without asking. They cross the wire only when a user pressed a button
(ADR 0007). `null` means the device has no Matter pairing tuple.

### Validation diagnostics

Each entry of `errors` carries `message`, `file` (relative to the
configuration tree), `line`, `column`, `key` (the dotted config path),
`hint` and `kind`. That is what puts a marker with a fix hint on the
editor's gutter instead of a line of text in a log pane.

### REST

| Endpoint | Purpose |
|---|---|
| `GET /health` | version and liveness; open on both sites |
| `POST /auth/login` | public site only — exchanges the password for an `HttpOnly` session cookie and a CSRF token |
| `POST /auth/logout` | public site only — needs the CSRF token in `X-CSRF-Token` |
| `GET /{path}` | built frontend assets, with SPA fallback |

`GET /api/builds/{job}/artifacts/{path}` used to be in that table and is
not any more. It served the local, verified, locally signed copy of a
finished build, and it was REST for the reason ADR 0004 decision 4
gives: `<a download>`, the flasher hand-off of ADR 0010 and `curl` all
take a URL and none of them can take a frame. That reason still holds —
what does not is the directory, since nothing fills it. The route was
removed rather than left answering 404 to everything. When the session
protocol's `get-artifact` brings it back, its refusal has to come back
with it: a path resolving outside the job's own directory is a 404 and
never a 403, because naming which guess escaped is free reconnaissance.

## Development

```sh
pytest                       # the whole suite, in-process, ~3 s
ruff check --fix . && ruff format .
```

The suite uses aiohttp's test client and never starts a subprocess, a
container or a real build.

## The builder dependency

The `mcuhome` package is imported in-process — never spawned, never
parsed from stdout (ADR 0011). The supported range lives in
`mcuhome_dashboard/versions.py` and is asserted at startup; a mismatch
refuses to start and names both versions. It is not in
`pyproject.toml`'s `dependencies` yet because the builder is not
published; `requirements-dev.txt` installs it from the sibling checkout,
and moving the range into the metadata is part of release tooling.

The direction of the dependency is the invariant: the dashboard follows
the builder's releases, and using the builder CLI never requires the
dashboard.

`mcuhome_dashboard/builder.py` is the only module that knows the
builder's Python surface, and since Block 0 it consumes
[`mcuhome.api`](https://github.com/mcu-home/mcuhome) — the builder's
supported programmatic surface — rather than reaching into the package:
tree discovery, stages 1-3, and the typed errors with their own
`to_dict`. Two helpers there still import past `api` (`pairing.Pairing`
for the commissioning codes, `loader.load_yaml_file` for the summary of
a configuration that does not resolve); both are named in that module's
docstring as candidates for `api`.

## Layout

| Module | Role |
|---|---|
| `config.py` | command line, environment, the password rules |
| `server.py` | process entry point; binds the sites |
| `app.py` | shared state, the two application factories, the REST surface |
| `security.py` | trust modes, peer and origin checks, sessions, CSRF |
| `ws.py` | the `/ws` endpoint: one reader, one writer, one event pump |
| `protocol.py` | the frame vocabulary and its hand-written validation |
| `commands.py` | one function per command |
| `events.py` | the in-process event bus |
| `devices.py` | configuration-tree scanning and change detection |
| `builder.py` | the adapter over the `mcuhome` package |
| `signing.py` | the firmware signing key and the detached signature (ADR 0007/0008) — kept by ADR 0012 decision 3, with no caller until a build produces something to sign |
| `web.py` | static assets, SPA fallback, `X-Ingress-Path` |
| `static/` | the frontend build output (a placeholder shell for now) |
