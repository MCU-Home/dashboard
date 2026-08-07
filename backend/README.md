# mcuhome-dashboard (backend)

Python backend of the [MCUHome Dashboard](https://github.com/mcu-home/dashboard):
device management, the API the frontend consumes, and — later — build
orchestration against a build server. It **never compiles firmware**
itself (ADR 0003).

Current state: **backend skeleton**. The device list, the configuration
tree watcher and validation work end to end; the build-server client,
the real frontend and the app packaging are separate work blocks.

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
`unauthorized`, `unavailable`, `internal_error`.

### Commands

| Command | Payload | Result |
|---|---|---|
| `server/info` | — | dashboard and builder versions, the supported builder range, the `model_version` range, trust mode, ingress base path, identity, tree state |
| `ping` | — | `{"pong": true, "time": …}` |
| `device/list` | — | every device in the tree, with a content hash and an unresolved summary |
| `device/get` | `{"name"}` | the raw YAML as it is on disk, plus the summary |
| `device/validate` | `{"name"}` | `{"ok", "errors": [...], "device": summary\|null}` |
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

Everything the builder still owes this side is marked `TODO(block-0):`
in `mcuhome_dashboard/builder.py`, which is the only module that knows
the builder's Python surface.

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
| `web.py` | static assets, SPA fallback, `X-Ingress-Path` |
| `static/` | the frontend build output (a placeholder shell for now) |
