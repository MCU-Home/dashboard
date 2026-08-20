# 0013 — Building over the builder package, not over a protocol

- Status: accepted
- Date: 2026-08-11

## Context

Since ADR 0012 decision 3 this dashboard has had no build path at all.
The job-protocol client of ADR 0006 was dismantled rather than migrated,
and what was to replace it was "the session client of firmware ADR
0019" — a second implementation, in this repository, of the protocol the
`mcuhome` command line would also have to speak.

That premise expired while it was being written. Firmware ADR 0020 and
decision E64 put the three build methods *into the builder package*:
`mcuhome.workbench.api.run_build` takes a `BuildRequest` and a method
name, and drives a build container on this machine (`local`), a build
server over the session protocol (`remote`), or the caller's own west
workspace (`local-dev`). The session client exists — in
`mcuhome-workbench`, which this dashboard already imports in-process
(ADR 0011). Writing a second one here would be writing a second opinion
about a protocol the package already speaks, in the repository that ADR
0012 spent its length separating from the build server.

So the question this ADR answers is not "how does the dashboard speak
the session protocol". It is: **what does a dashboard that calls
`run_build` still have to decide for itself?** Four things, below, plus
two the teardown left unfinished — the log offsets that
`events.py` and `backend/README.md` both recorded as "has to be rebuilt
rather than rediscovered", and the artifact route whose refusal
`app.py` kept a comment describing after the handler was gone.

`signing.py` is the other loose end: ADR 0012 decision 3 kept it
deliberately caller-less, because the dashboard is the only side that
has the private key (ADR 0007 decision 3, ADR 0008). This is what gives
it a caller.

## Decision

### 1. The dashboard does not restrict the build methods

The build method is **deployment configuration**
(`--build-method` / `MCUHOME_DASHBOARD_BUILD_METHOD`), passed through to
`run_build` and interpreted by nothing on this side. `None` means "no
preference" and takes the builder's own default. A per-build override
rides on `build/start` for the case where a user wants one build
elsewhere.

The dashboard neither subsets the list nor validates a name against a
copy of it: `mcuhome.workbench.api.resolve_method` owns the real names
and its refusal enumerates them, so a typo is answered by the package
that knows rather than by a list here that can go stale. Likewise, a
method this installation cannot run refuses in the builder's own words —
`MethodUnavailable` naming the exact `pip install` — which is a better
answer than a shorter menu.

The package abstracts *where* a build runs. A dashboard that hard-coded
one method would be re-deciding, worse and in a second place, something
already decided. The Home Assistant App packaging will ship with
`remote` preset, and that is a packaging default rather than a property
of this code.

**ADR 0003 is unchanged.** "The dashboard never compiles" is a statement
about the process, and it holds in its strong form: `mcuhome-compiler`
is not installed (`requirements-dev.txt` leaves it out on purpose), so
the two methods that compile refuse *in this very process* — which makes
the invariant checkable in the venv the tests run in, instead of being a
promise about code that was not written. What the amendment to that ADR
is: the *reason* is no longer "the dashboard cannot reach a build
environment", it is "the dashboard does not carry a toolchain, and
whether it drives one is the deployment's call".

### 2. Server and token configuration stay the dashboard's own

`--build-server-url`, `--build-server-token`,
`--build-server-token-file` and the `/share` auto-pairing file are
reactivated and mapped onto `BuildRequest.server` / `.token`. The
`--sdk-source` list is added beside them, because both container-shaped
methods resolve the SDK pin on this machine (firmware E65).

The `mcuhome` command line's XDG ladder — `build-servers.toml`,
`tokens/<label>` (firmware E53/E63) — is **not** read here. That ladder
is right for a human at a shell, who expects their own configuration to
be found. A dashboard is configured by whoever deploys it: App options,
`docker run` environment, flags. A second invisible ladder underneath
those would make an App's build server depend on a file in the home
directory of whichever user the container happens to run as, and would
give two answers to "where does this dashboard build" with no rule for
which wins.

`build_server_configured` means an address is set, not an address *and*
a token: firmware ADR 0019 permits a server that wants no
`Authorization` header, and `BuildRequest.token` is `None`-able for
exactly that reason.

### 3. One build at a time, for the whole process

Not one per device. The default method compiles in a container on this
machine, and two concurrent Zephyr builds on the hardware a Home
Assistant box actually is will thrash rather than finish sooner. A rule
that is true of the machine is worth more than one that is true of the
object the user happened to click.

A second `build/start` is refused with `conflict` **carrying the record
of the build that holds the slot**, so a UI can name it and offer to
cancel it rather than only saying no.

**The slot is held by the work, not by the task awaiting it.** Two of
the three methods block in a worker thread that Python cannot interrupt,
so a cancelled build's container goes on using the machine after this
process has stopped waiting for it — and a rule that is true of the
machine is worth nothing if it is given back at the moment the *waiting*
ends. So the call into the builder is shielded, the record ends
`cancelled` immediately for the user, and the slot is returned when the
work actually returns (decision 7). A start in that window is refused
with the same `conflict`, naming a record that reads `cancelled`.

The honest cost, accepted: with `remote`, a build server that could take
three builds gets one at a time from this dashboard. Lifting the limit
per method later is a change to this decision, not to the code's shape.

### 4. Build output is a stream with resumable line offsets

Every `build_output` event carries the offset of its first line;
offsets are per build, monotonic and never reused; `build/log` serves
any suffix from any offset and marks `truncated` when the requested
offset has already been dropped from the bounded retained log.

This is the property `events.py` and `backend/README.md` both flagged as
having to come back with any successor: **this bus drops the oldest
events for a subscriber that fell behind, and build output is exactly
the traffic that makes one fall behind.** A stream without a resumable
position shows a log with a hole in it and no way to notice. It is the
snapshot-then-events contract of ADR 0004 decision 3 applied to a stream
instead of a list — `build/log` is to `build_output` what
`config/subscribe` is to `device_changed`.

Offsets count **lines, not bytes**, which is where this differs from the
job protocol's `build_job_output`. Lines are the unit `run_build`'s
`on_line` sink actually produces; counting bytes would mean
re-serialising to invent a number nothing else in the path uses.

Output is **batched** onto the bus (a flush every 200 ms, capped at 250
lines per event) rather than one event per line. The bus queue is 256
deep: one event per line would guarantee, on every build, the drop the
offsets exist to recover from. Batching makes it rare; the offsets make
it survivable either way.

### 5. Artifacts come back as REST, keyed by build id

`GET /api/builds/{build}/artifacts/{path}` returns, and its refusal
returns with it: **an unknown build, an undeclared name, a missing file
and a path that resolves outside the build's own directory all answer
404, never 403**, because naming which guess was close is free
reconnaissance. It is under `/api/`, so `security.PROTECTED_PREFIXES`
already requires an identity on the public site — the same door as
`/ws`, which matters because these bytes are firmware signed with this
installation's key.

REST rather than a frame for the reason ADR 0004 decision 4 gives, and
only that reason: `<a download>`, the flash-tool hand-off of ADR 0010
and `curl` all take a URL and none of them can take a WebSocket frame.

Only artifacts the build method **declared and verified** are copied out
of the delivery directory, and **only those are served**. Nothing
undeclared rides along, on any method — and nothing undeclared can be
asked for either: the requested path has to appear in the record's
`artifacts`. That second half is not symmetry for its own sake. Both
container-shaped methods put their scratch area *inside* the build
directory, and that area holds the build context: the resolved device
model, verbatim, with the Matter pairing tuple in it. A route that
served the directory would put it one plain `GET` away, in a system
where commissioning codes travel only when a user asked for them.

Artifacts live at `<data_dir>/builds/<device>/<build id>/` — one
directory per **build**, under the device's own. Per *device* was the
first shape and it was wrong in a way that only showed up at the seam
with the record: the URL space is keyed by build id, so a cancelled
build's URLs served the previous build's signed firmware, and two builds
of one device wrote into the same build context — one of them
`rmtree`-ing the other's while a container was mounted on it.

**Retention keeps what the per-device shape was after.** A build that
succeeds *is* that device's current image, so the older directories of
that device go when it completes; a build that did not succeed declared
no artifacts and can serve nothing, so it removes its own — after the
work stopped writing to it, which for a cancelled build is when the slot
comes back (decision 7). What is on disk is therefore still one current
image per device rather than a dated pile, and a record whose files were
reclaimed answers 404 like anything else that is not there.

Build *records* are in memory and die with the process, so after a
restart the files are there and unattributed, and `build/status` says so
by not listing them rather than by inventing a record for bytes it did
not write. The next successful build of that device sweeps them.

### 6. Signing is one host-side step here, and it is in-process

Every build method delivers an **unsigned** image (firmware E56); one
step afterwards signs it, and in this deployment that step is here,
because this is the side that has the key (ADR 0007 decision 3, ADR
0008 decision 2 — `/data/signing.key`).

`signing.py` gets its caller and loses its subprocess. It used to shell
out to `mcuhome sign`, which belongs to the `mcuhome` **CLI**
distribution — a console script this dashboard does not install and must
not start requiring (firmware ADR 0020 decision 2 reserves that name for
the command line). The step is taken through `mcuhome.workbench.imgtool`
instead, the same library the command runs, so the bytes are the same
bytes and neither side spells out the imgtool argument order.

The key is created, if it does not exist, by the call that produces the
**public** PEM the build needs as input — so it exists before the
signer runs, and the signer is called with `create=False` so that it
*cannot* invent one. A first generation is reported in the record and
logged at warning level; a *second* is what must never happen quietly,
because it orphans every device already bootstrapped against the first
(ADR 0008 decision 3). A key that vanished between the two calls is a
loud, recoverable failed build, which is the only correct outcome: an
image signed with a key its own MCUboot does not carry is firmware
nothing accepts.

The private half never enters a `BuildRequest` — there is no field it
fits in, on any method — and never enters a build record: the wire form
carries *that* it was signed and *what* was written, never where the key
is. **Not even in an error message.** The libraries underneath name the
key file in their refusals, and with `MCUHOME_SIGNING_KEY` set that is a
path the operator chose; a build record's `errors` is published to every
subscribed tab, so key-custody failures are raised path-free — naming
the key by its role, with the file and the reason in the server log.

A `.ota` is wrapped around the freshly signed binary when the device can
take one, and only then. A flashable lookalike beside fresh unsigned
firmware is prevented by the shape of the directories rather than by
deleting one: a build directory has exactly one writer (decision 5), and
the signing step is the only thing that ever writes a signed name into
it.

### 7. Cancellation stops this process's build, and says only that

`build/cancel` stops this process waiting for the build. What it does
**not** do is stop the work, and that is documented rather than smoothed
over: `local` and `local-dev` are blocked in a worker thread that Python
cannot interrupt, so the container or the compiler runs to its own end
whatever this process decides. `remote` is left running for the same
reason the slot is held — a build server that is still building is still
the machine this dashboard promised to use once at a time — rather than
unwound, which would have made the *meaning* of a cancel depend on the
method a deployment happened to configure.

The record ends `cancelled` immediately, nothing is collected and
nothing is signed — so a cancelled build never leaves a flashable image
behind, which is the part that matters for safety — and its build
directory is removed once nothing is writing into it.

**The one build slot is returned when the work returns, not when the
waiting ends** (decision 3). Giving it back at the cancel would accept a
second Zephyr build onto a machine the first is still compiling on,
which is the whole of what decision 3 exists to prevent; before the
per-build directories of decision 5 it also let the second build
`rmtree` the first one's live build context. So a `build/start` in that
window is refused with `conflict` naming a record that reads
`cancelled`, and the refusal says which of the two it is.

Claiming more than that ("build stopped") would be the kind of statement
a user later discovers was false while watching their CPU fan.

## Consequences

- **`backend/README.md`'s "Building: it does not" section is obsolete**
  and is replaced by the build vocabulary. The two paragraphs about
  inert settings and the missing artifact route go with it.
- **The "there is no build client here" invariant is amended.**
  There is still no *protocol* client here and none is to be added; what
  there is, is a caller of the builder package. The sentence "there is
  no local-build code path, and none is to be added" is superseded:
  which method runs is configuration, and the constraint that survives
  is the one that always mattered — **this package never depends on
  `mcuhome-compiler`**.
- The supported-version range in `versions.py` now names
  `mcuhome-workbench` rather than `mcuhome`. The plain name is the
  command line's distribution since firmware ADR 0020 decision 2;
  declaring it would have named a console script this package neither
  imports nor wants. The range itself is unchanged and still admits the
  installed builder.
- A dashboard session was already equivalent to holding the firmware
  signing key (SECURITY.md). It is now also equivalent to *starting a
  build*, which on the `local` method means starting a container on the
  host. That is not a new trust boundary — it is the boundary ADR 0009
  already draws around `/ws` — but it is a sharper consequence of
  crossing it.
- One build at a time is a **global** lock, so a busy `remote` build
  server is underused by this dashboard. Deliberate; see decision 3.
- A cancel does not free the slot. A user who cancels a twenty-minute
  container build and immediately starts another is refused until the
  first one's container exits — the honest report of a machine that is
  still busy, and the cost of decision 3 being about the machine.
- Build records do not survive a restart. Artifacts do, for the latest
  successful build of each device: retention (decision 5) is the reason
  a restart does not accumulate directories nothing can attribute. A
  future decision could persist the records beside the artifacts they
  describe; inventing them by reading the directory is what this ADR
  rules out.
- `pyproject.toml` still declares no builder dependency, for the reason
  it already gives: the packages are not published. Nothing about that
  changes here.
