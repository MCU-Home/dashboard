# mcuhome-dashboard (backend)

Python backend of the [MCUHome Dashboard](https://github.com/mcu-home/dashboard):
device management, the API the frontend consumes, and build
orchestration against a build server. It **never compiles firmware**
itself (ADR 0003).

Current state: the device list, the configuration tree watcher,
validation and the **build-server client** work end to end. The client
resolves a device in-process, sends the model to a build server (ADR
0006), streams its events to the browser, downloads and verifies the
artifacts and applies the firmware signature locally (ADR 0007/0008).
What it cannot do yet is get a build to actually run — see the "Status"
section of [`../buildserver/README.md`](../buildserver/README.md) for
the one builder flag that is missing.

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
`unauthorized`, `unavailable`, `conflict`, `unsupported`,
`internal_error`. `unsupported` is the negotiation failure of ADR 0006
decision 4 — nothing about the frame is wrong and retrying will not
help, because one of the two sides has to change version; the error
object carries the numbers as fields as well as in its sentence.

### Commands

| Command | Payload | Result |
|---|---|---|
| `server/info` | — | dashboard and builder versions, the supported builder range, the `model_version` range, trust mode, ingress base path, identity, tree state |
| `ping` | — | `{"pong": true, "time": …}` |
| `device/list` | — | every device in the tree, with a content hash and an unresolved summary |
| `device/get` | `{"name"}` | the raw YAML as it is on disk, plus the summary |
| `device/save` | `{"name", "content", "expected_hash"?}` | `{"name", "device": entry, "content_hash"}` |
| `device/validate` | `{"name"}` | `{"ok", "errors": [...], "device": summary\|null}` |
| `device/commissioning` | `{"name"}` | `{"ok", "errors": [...], "commissioning": codes\|null}` |
| `build/submit` | `{"name", "options"?}` | `{"name", "ok", "errors", "job_id", "job"}` |
| `build/cancel` | `{"job_id"}` | the build server's job record |
| `build/status` | `{"limit"?}` | `{"server": {...}, "queue": {...}\|null}` |
| `build/log` | `{"job_id", "offset"?}` | history from `offset`, then live events |
| `build/artifacts` | `{"job_id", "fetch"?}` | the local artifact set, each file with a download URL |
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

Events on the `builds` topic: `build_job_changed` (the whole job record)
and `build_job_output` (`{"job_id", "offset", "text"}`). The build
commands subscribe the socket to `builds` themselves, so a client that
submitted a build is already receiving its events.

## Building (ADR 0003, 0006, 0007, 0008)

The dashboard **never compiles**, not even when the build server runs on
the same host. `build/submit` therefore does this, in this order:

1. loads, validates and resolves the device **here**, in-process — so a
   broken configuration is a refusal in a second with a line number in
   it, not a failed compile in ten minutes;
2. checks the build server's `GET /capabilities` before sending anything
   (ADR 0006 decision 4): a `model_version` or builder mismatch is a
   refusal naming both sides, never a silent fallback;
3. sends the resolved `device-model.json` and the signing **public** key
   with `no_sign: true`. The private key does not cross (ADR 0007
   decision 3), and neither does the schema, `secrets.yaml`, or any file
   name;
4. follows the job's log from byte 0.

A configuration that does not resolve is a **successful** command whose
result says `ok: false` and carries the diagnostics — the same contract
`device/validate` follows. Nothing is sent in that case.

**Artifacts arrive and are signed automatically.** When the job
succeeds, the client downloads every artifact the manifest names,
verifies each chunk against its own SHA-256 and each file against the
hash the *build* computed, and then runs `mcuhome sign` with the key
from `/data/signing.key` (ADR 0008 decision 2; `MCUHOME_SIGNING_KEY`
overrides the location). A key is generated on first need and that is
logged at warning level, because every device bootstrapped afterwards
trusts it. A file whose hash does not match is refused and not written:
a corrupted artifact that got signed would be a corrupted artifact with
a valid signature.

> **A build server learns the Matter commissioning passcode of every
> device it builds** — those credentials are compile-time Kconfig (ADR
> 0007 decision 2). Operate it as a trusted machine. The dashboard says
> so in `server/info` and `build/status`, next to the server's address.

### Configuring one

| Option | Environment | What it is |
|---|---|---|
| `--build-server-url` | `MCUHOME_DASHBOARD_BUILD_SERVER_URL` | `http(s)://` or `ws(s)://`; both forms are accepted and normalized |
| `--build-server-token` | `MCUHOME_DASHBOARD_BUILD_SERVER_TOKEN` | the bearer token the build server logged at startup |
| `--build-server-token-file` | `MCUHOME_DASHBOARD_BUILD_SERVER_TOKEN_FILE` | read it from a file instead |
| `--data-dir` | `MCUHOME_DASHBOARD_DATA_DIR` | the signing key and downloaded artifacts (default `/data` in an App) |

**Two Apps on one Home Assistant instance pair themselves** (ADR 0006
decision 8): the build server writes its token to
`/share/mcuhome/build-server.token`, the dashboard reads it from there
and assumes `http://127.0.0.1:8100`. Nothing is configured by hand, and
an explicit URL or token always wins over the pairing file.

With nothing configured, every build command refuses with a message
naming both environment variables. The rest of the dashboard is
unaffected — it never compiled anything in the first place.

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
| `GET /api/builds/{job}/artifacts/{path}` | one artifact of a finished build — the **local**, signed copy |
| `GET /{path}` | built frontend assets, with SPA fallback |

The artifact endpoint is REST because a browser primitive needs a URL:
`<a download>`, the flasher hand-off of ADR 0010 and `curl` all take one
and none of them can take a frame. It serves what this dashboard
downloaded, verified and signed — never a pass-through of the build
server's bytes, which would hand out an unsigned image.

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
| `buildclient.py` | the WebSocket client for a build server (ADR 0006) |
| `signing.py` | the firmware signing key and the detached signature (ADR 0007/0008) |
| `web.py` | static assets, SPA fallback, `X-Ingress-Path` |
| `static/` | the frontend build output (a placeholder shell for now) |
