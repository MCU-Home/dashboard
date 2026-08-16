# mcuhome-dashboard (backend)

Python backend of the [MCUHome Dashboard](https://github.com/mcu-home/dashboard):
device management and the API the frontend consumes. It **never compiles
firmware** itself (ADR 0003).

Current state: the device list, the configuration tree watcher,
validation, editing, the commissioning codes, and — since
[ADR 0013](../docs/adr/0013-building-over-the-builder-package.md) —
building, signing and artifact download work end to end.

> **It still does not compile anything itself** (ADR 0003). A build goes
> to `mcuhome.workbench.api.run_build`, the builder package's one
> awaitable over its three build methods: a build container on this
> machine, a build server, or a west workspace. Which one runs is
> deployment configuration (`--build-method`) and nothing in this package
> branches on it. `mcuhome-compiler` is deliberately **not** installed —
> `requirements-dev.txt` leaves it out — so the two methods that compile
> refuse in this very process, naming the distribution they need. That is
> what makes "the dashboard never compiles" checkable here rather than
> promised.
>
> There is no *protocol* client in this repository and none is to be
> added: the session protocol of firmware ADR 0019 is spoken by
> `mcuhome-workbench`, which this package imports in-process (ADR 0011).

## Running it

```sh
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements-dev.txt      # workbench + model from the sibling checkouts
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
nor the Supervisor gateway (`172.30.32.2`). `X-Remote-User-*` is never
trusted as a raw client input — but on a request that passed the peer
check the Supervisor has stripped and re-injected it, so
`X-Remote-User-Name` is the authenticated username.

**Ingress is admin-only** (ADR 0014). Dashboard access in Home Assistant
is reserved for administrators: the site resolves the user's admin status
from the Supervisor's authenticated `/auth/list` (over `SUPERVISOR_TOKEN`)
and gates the mutating and secret-bearing verbs behind it — `device/new`,
`device/save`, `device/matter-pairing`, `device/commissioning`,
`build/start`, `build/cancel` and the artifact
download route answer `unauthorized`/`403` for a non-admin, while the
read-only views stay open. `device/boards` is not among them: it is a
catalogue of what the software supports, with nothing about this
deployment in it. It fails closed: an unresolved user is
non-admin, and a deployment with no token grants the admin verbs to
nobody. The public (password) site is unchanged — its one password is the
operator — and reports `identity.is_admin: true`. Its two password paths
are rate-limited (per-source lockout with backoff, `429` + `Retry-After`).

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
| `server/info` | — | dashboard and builder versions, the supported builder range, the `model_version` range, the `build` block (below), trust mode, ingress base path, identity, tree state |
| `ping` | — | `{"pong": true, "time": …}` |
| `device/list` | — | every device in the tree, with a content hash and an unresolved summary |
| `device/get` | `{"name"}` | the raw YAML as it is on disk, plus the summary |
| `device/new` | `{"name", "board", "friendly_name"?, "outline"?}` | what `device/get` answers, for the device just created |
| `device/boards` | — | the builder's registry: boards, drivers, clusters, device types, each with the planned ones |
| `device/save` | `{"name", "content", "expected_hash"?}` | `{"name", "device": entry, "content_hash"}` |
| `device/validate` | `{"name"}` | `{"ok", "errors": [...], "device": summary\|null}` |
| `device/commissioning` | `{"name"}` | `{"ok", "errors": [...], "commissioning": codes\|null}` |
| `device/matter-pairing` | `{"name", "force"?}` | `{"name", "replaced", "secrets_file"}` — never the codes |
| `build/start` | `{"name", "method"?}` | `{"build": record}` — accepted, not finished |
| `build/status` | `{"build_id"?}` | one `{"build": record}`, or `{"builds": [...], "running": id\|null}` |
| `build/log` | `{"build_id", "from_offset"?}` | `{"offset", "lines", "next_offset", "first_offset", "truncated", "state"}` |
| `build/cancel` | `{"build_id"}` | `{"build": record}` |
| `build/subscribe` | — | the `build/status` snapshot, and every later change as an event |
| `config/subscribe` | — | the `device/list` snapshot, and every later change as an event |
| `subscribe_events` | `{"topics": ["devices", "builds"]}` | the topics this socket now receives |
| `unsubscribe_events` | `{"topics": [...]}` | the topics that remain |

Lists follow **snapshot-then-events**: the subscription's result is the
current state and every change after it arrives as an event. Clients do
not poll and do not re-fetch.

Events on the `devices` topic: `device_added`, `device_changed`,
`device_removed`, `tree_state`. Events on the `builds` topic:
`build_started`, `build_changed`, `build_output`. On either,
`events_dropped` arrives when a connection fell so far behind that the
server discarded events for it — the cue to re-subscribe rather than
trust what is held.

`tree_state` carries `{root, available, problem}`, and `problem` is why
the tree cannot be used when `available` is false: `{"code": …}` plus,
for a version mismatch, `project_version` and `expected_version`. The
codes are `no_project`, `project_upgrade_required`,
`project_version_unsupported`, `project_upgrading` and
`project_file_unreadable`.

It is a **code and never a sentence**, for two reasons. The project's
version is checked here rather than in each command — a project written
by older tools used to list its devices normally and then refuse
everything done to one of them — and the sentence that belongs with a
refusal differs by deployment: standalone, a person installs the command
line and repairs the project; in the Home Assistant App the container
keeps it current, so a user seeing this at all means something upstream
failed. Both sites publish onto one bus, so the wording belongs to the
client, which knows from `server/info` which site answered it.

## Building (ADR 0013)

A build is `mcuhome.workbench.api.run_build`: one awaitable over the
builder's three build methods. **Which one runs is configuration**, not
code here — `--build-method local` drives a build container on this
machine, `remote` a build server, `local-dev` a west workspace — and a
method this installation cannot run refuses in the builder's own words,
naming the exact `pip install` it is missing. The dashboard neither
subsets the list nor validates a name against a copy of it.

### The build record

```jsonc
{
  "id": "9f2c…", "device": "bench-node", "method": "local",
  "state": "queued" | "running" | "succeeded" | "failed" | "cancelled",
  "started": 1770000000.0, "finished": null,
  "context_id": "…",          // the identity the work is attributed to
  "image": "ghcr.io/mcu-home/builder:…",
  "status": "success",        // the method's own word
  "errors": [/* diagnostics, the same shape device/validate returns */],
  "artifacts": [{"role": "firmware", "path": "firmware.bin", "size": 1234, "signed": false}],
  "signing": {"signed": true, "created_key": false, "outputs": ["firmware.signed.bin"]},
  "ota": {"path": "bench-node-0.1.0.ota", "version": "0.1.0", "software_version": 1},
  "log_first_offset": 0, "log_next_offset": 5821
}
```

A build that runs and **fails is a successful command** — the refusal is
the record's `state` and its `errors`, in the same diagnostics shape
`device/validate` uses, so one component renders both. An error frame
from `build/start` means the build could not be *started*: `not_found`
for an unknown device, `conflict` when the one build slot is taken
(carrying `build`, the record that holds it), `bad_request` for a method
name the builder does not have.

**One build at a time, for the whole process** (ADR 0013 decision 3).
The default method compiles in a container on this machine, and two
concurrent Zephyr builds thrash rather than finish sooner.

**The slot belongs to the work, not to the record.** `build/cancel`
stops this process waiting; it cannot interrupt a container or a
compiler in a worker thread, so the record ends `cancelled` at once —
nothing collected, nothing signed, and its directory removed as soon as
nothing is writing to it — while the slot stays taken until the work
really ends. A `build/start` in that window is refused with `conflict`
naming a record that already reads `cancelled`, which is the honest
answer: the machine is still busy.

### Progress: what the build is doing, and what it found out

The record carries `steps`, a list of `{"key", "state", "facts"}` in the
order they happen, and it carries it from the moment `build/start`
answers — every step `pending`, because "how far along is this" needs
the steps still to come. `state` is one of `pending`, `running`, `done`,
`failed`. Changes arrive as ordinary `build_changed` events, so a client
that reconnects finds the progress in the next snapshot and has nothing
extra to ask for.

The five keys are `validate`, `context`, `compile`, `artifacts`, `sign`.
Two of them are the *builder's* own progress vocabulary — it announces
`context` and `compile` from inside `run_build` — and three are this
dashboard's, for the work around that call. A method that skips one
never claims it (`local-dev` builds no build context, so it lists no
`context` step), and a step announced that is not in the list is
inserted where it happened. **Both halves of the vocabulary are
append-only:** render the keys you know, show an unknown one by its
name, never fail on it.

`facts` is what a step established, once it knows: `validate` answers
with `board`, `transport`, `thread_role`, `matter`, `endpoints`,
`channels`; `context` with `sdk`, `zephyr`, `patches`, `files` and the
context `id`; `compile` with `image` and `jobs`. Every key is optional
and the set only grows — read them defensively.

Facts are **filtered on the way out, by allowlist** (ADR 0016 decision
4). The builder announces more than this: a remote build's `compile`
step carries the build server's address, and this API publishes only
*whether* a build server is configured (`server/info`), never where it
is. A value that is not plain JSON data is dropped rather than sent.

### Log offsets, and the hole the bus is allowed to punch

`build_output` carries `{"build_id", "offset", "lines"}`, where `offset`
is the line number of `lines[0]` in that build's log — counted from
zero, monotonic, never reused. This is the property the old
`build_job_output` had and that had to come back with any successor:
**the bus drops the oldest events for a subscriber that fell behind, and
build output is exactly the traffic that makes one fall behind.** A
stream without a resumable position shows a log with a hole and no way
to notice.

So a client that sees `events_dropped`, or a batch that does not start
where the last one ended, calls `build/log` with the last offset it
holds. The answer's `offset` is where it actually starts, which is not
always what was asked for: the retained log is bounded, and a request
for something already dropped comes back from `first_offset` with
`truncated: true` rather than quietly beginning in the middle.

Output is batched (every 200 ms, ≤ 250 lines per event) because the bus
queue is 256 deep and one event per line would guarantee the drop on
every build. Offsets are lines, not bytes — lines are the unit
`run_build`'s sink produces.

### After the build: artifacts, signature, OTA

Every build method delivers an **unsigned** image; one host-side step
signs it, here, because this is the side that has the private key (ADR
0007 decision 3, ADR 0008 decision 2). In order:

1. the artifacts the method **declared and verified** are copied into
   `<data-dir>/builds/<device>/<build id>/` — nothing undeclared rides
   along, and the directory is that build's own, so what a record says
   about its artifacts is true of the files behind its id;
2. `mcuhome.workbench.imgtool` signs, in-process — the same library
   `mcuhome sign` runs, called rather than spawned, because that command
   belongs to the CLI distribution this package does not install;
3. a Matter `.ota` is wrapped around the freshly **signed** binary when
   the board and the device can take one.

A build directory is kept until a newer build of that device succeeds;
one that did **not** succeed removes its own, because it declared no
artifacts and can serve nothing. So the device's directory holds the
current image and not a dated pile of them, and no cancelled or failed
build can leave a flashable lookalike behind.

The signing key is generated on first need, by the same call that
produces the public PEM the build needs as input — the signer itself
runs with `create=False` and refuses loudly rather than inventing one.
A generation is reported (`signing.created_key`) and logged loudly: a
*second* one orphans every device already bootstrapped against the first
(ADR 0008 decision 3). The private half never enters a build request —
there is no field it fits in, on any method — and never enters a build
record, not even inside an error message: a key that cannot be read or
created says so by the key's *role*, and the path is in the server log.

### Configuring where it builds

| Option | Environment | What it is |
|---|---|---|
| `--build-method` | `MCUHOME_DASHBOARD_BUILD_METHOD` | `local`, `remote` or `local-dev`; unset takes the builder's default |
| `--build-server-url` | `MCUHOME_DASHBOARD_BUILD_SERVER_URL` | `http(s)://` or `ws(s)://`; both forms are accepted and normalized |
| `--build-server-token` | `MCUHOME_DASHBOARD_BUILD_SERVER_TOKEN` | the bearer token the build server logged at startup |
| `--build-server-token-file` | `MCUHOME_DASHBOARD_BUILD_SERVER_TOKEN_FILE` | read it from a file instead |
| `--sdk-source` (repeatable) | `MCUHOME_DASHBOARD_SDK_SOURCE` (`PATH`-style) | where the hash-pinned MCUHome SDK package is; both container-shaped methods resolve the pin here |
| `--build-jobs` | `MCUHOME_DASHBOARD_BUILD_JOBS` | parallel compile jobs (default: this machine's cores) |
| `--data-dir` | `MCUHOME_DASHBOARD_DATA_DIR` | the signing key and every build's artifacts (default `/data` in an App) |

`server/info`'s `build` block reports the configured method, the
builder's default, every method it has, `server_configured` and `jobs`.
It is deliberately a **boolean** for the server and carries no token:
the address may name a host an operator does not publish, and the token
never leaves this process. It reports no reachability, because nothing
opens a connection until a build asks it to.

**This is the dashboard's own configuration surface** (ADR 0013 decision
2). The `mcuhome` command line's `build-servers.toml` / `tokens/<label>`
ladder is *not* read here: a dashboard is configured by whoever deploys
it, and a second invisible ladder underneath App options would make the
build server depend on the home directory of whichever user the
container happens to run as.

**Two Apps on one Home Assistant instance pair themselves** (ADR 0006
decision 8, carried forward by ADR 0012 decision 3): the build server
writes its token to `/share/mcuhome/build-server.token`, the dashboard
reads it from there and assumes `http://127.0.0.1:8100`. Nothing is
configured by hand, and an explicit URL or token always wins over the
pairing file.

> **A build server learns the Matter commissioning passcode of every
> device it builds** — those credentials are compile-time Kconfig (ADR
> 0007 decision 2). Operate it as a trusted machine. It never learns the
> firmware signing key: the image comes back unsigned and is signed here.

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
that is `device/new`.

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

### Creating a device

`device/new` writes `devices/<name>/main.yaml` and answers with exactly
what `device/get` would, so a client opens the editor on it without a
second round trip.

`outline` is optional and is what a form collected:

```json
{
  "buses": [{"id": "i2c0", "controller": "arduino_i2c"}],
  "peripherals": [{"id": "probe", "driver": "bosch,bmp180", "bus": "i2c0"}],
  "endpoints": [
    {
      "device_type": "temperature_sensor",
      "clusters": [{"cluster": "temperature_measurement", "source": "probe.temperature"}]
    }
  ]
}
```

Given one, those sections are written as real configuration; without
one, the file carries the commented example the command line writes.
Every name in it comes from `device/boards`, and **every name in it is
judged by the builder** — this side checks the shape of the frame and
nothing else, because a second opinion about what a valid configuration
is, is exactly what this dashboard does not keep. Refusals arrive as
error frames carrying the builder's diagnostics under `errors`, with
`conflict` reserved for "there is already a device called that", which a
client can act on by offering to open it. Nothing is written when the
command refuses.

`device/boards` is the registry those choices come from: boards (each
with the buses it breaks out), drivers (each with its channels and the
bus kind it speaks), clusters (each with the quantity it measures) and
device types (each with the clusters it makes mandatory), plus the
`planned_*` list beside each — "not yet, because …" is a better answer
than an absence. It reads no project, so it answers before one is open.

### Drawing commissioning credentials

`device/matter-pairing` draws a device's discriminator, passcode and
salt — **once, ever**. The values go to the device's own secrets file and
`main.yaml` gets `!secret` references, so the file a project commits
never carries them. `force` replaces credentials that are already there
and the builder refuses without it: every controller that knows the
device would have to commission it again.

It answers with `replaced` and the path written, and with **none of the
codes**. Those come from `device/commissioning` and from nothing else.

The write changes `main.yaml`, so the command re-scans before answering
and an editor holding the old `content_hash` learns about it as a
`device_changed` event rather than as a conflict on its next save.

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
| `GET /api/builds/{build}/artifacts/{path}` | one file of a finished build: the local, verified, locally signed copy |
| `GET /{path}` | built frontend assets, with SPA fallback |

The artifact route is REST for the reason ADR 0004 decision 4 gives, and
only that reason: `<a download>`, the flash-tool hand-off of ADR 0010
and `curl` all take a URL and none of them can take a frame.

**It serves what the record declares in `artifacts`, and no other file
in the build directory.** A build method puts its scratch area inside
that directory, and that area holds the build context — the resolved
model, with the device's Matter pairing tuple in it. Serving the
directory would hand it out over a plain `GET`, in a backend where
commissioning codes travel only when a user asked for them.

**Everything unservable is a 404, and nothing is a 403.** An unknown
build id, a build with no artifacts, an undeclared file, a missing file
and a path that resolves outside the build's own directory all answer
identically —
naming which guess was close is free reconnaissance, and a legitimate
caller follows a link out of a record it was given. It lives under
`/api/`, so it needs an identity on the public site by the same rule as
`/ws`; that matters, because these bytes are firmware signed with this
installation's key.

Build *records* are in memory and die with the process; artifacts do
not. After a restart the files are still on disk and no record claims
them, so they are not served — inventing a record for bytes this process
did not write is the thing ADR 0013 decision 5 rules out.

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
published; `requirements-dev.txt` installs it from the sibling
checkouts, and moving the range into the metadata is part of release
tooling.

The direction of the dependency is the invariant: the dashboard follows
the builder's releases, and using the builder CLI never requires the
dashboard.

`mcuhome_dashboard/builder.py` is the module that knows the builder's
Python surface, and it consumes
[`mcuhome.workbench.api`](https://github.com/mcu-home/mcuhome) — the
builder's supported programmatic surface — rather than reaching into the
package: tree discovery, stages 1-3, the typed errors with their own
`to_dict`, and `run_build` with its request and outcome types. **The
build seam is there and nowhere else**, so the whole of this package's
knowledge of how a build is started is one file.

Three helpers there still import past `api` (`model.pairing.Pairing` for
the commissioning codes, `workbench.loader.load_yaml_file` for the
summary of a configuration that does not resolve,
`model.manifest.ota_parameters` plus `workbench.otafile` for the Matter
OTA wrapper); all three are named in that module's docstring as
candidates for `api`. `signing.py` reaches past it as well, to
`workbench.signing` and `workbench.imgtool`, and says why.

The imported distribution is **`mcuhome-workbench`** (which brings
`mcuhome-model`). `mcuhome-compiler` is not installed and is not to be:
that is what keeps ADR 0003 checkable rather than promised. The plain
name `mcuhome` is the command line's distribution since firmware ADR
0020 decision 2, which is why the range in `versions.py` does not use
it.

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
| `builds.py` | the build registry: one slot, the log offsets, and what happens after an outcome (ADR 0013) |
| `builder.py` | the adapter over the `mcuhome` package — including the `run_build` seam |
| `signing.py` | the firmware signing key and the detached signature (ADR 0007/0008), in-process over `mcuhome.workbench.imgtool` |
| `web.py` | static assets, SPA fallback, `X-Ingress-Path` |
| `static/` | the frontend build output (a placeholder shell for now) |
