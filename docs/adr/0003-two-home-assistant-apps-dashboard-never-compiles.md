# 0003 — Two Home Assistant Apps; the dashboard never compiles

- Status: superseded for the build-service subject by firmware
  ADR 0017-0020 (2026-08-09)
- Date: 2026-08-07

## What is superseded, and what carries forward (2026-08-09)

The remote-build architecture was re-decided on 2026-08-08/09. For the
build-service subject the valid layer is firmware ADR 0017-0020,
`mcuhome/docs/design/build-container-contract.md` and ADR 0012 of this
repository; this ADR is dismantled against it rather than migrated.

**Superseded.**

- **Decision 1's two-App topology** — the fixed pair of a thin
  `mcuhome-ui` App and a fat `mcuhome-buildserver` App, with
  the build server's shape fixed by that pairing. What replaces it is a
  backend, in one of two profiles: `container`, in which the backend
  materializes one build container per session, and `subprocess`, in
  which the build environment runs in the same filesystem as the build
  server but as a separate process — the Home Assistant case
  (build-container contract §1.2).

  The pairing is gone as a *deployment topology*, not merely as the
  build server's internal shape. A build server is an orchestrator and
  never itself a build environment, in both profiles (contract §1.2),
  and its primary deployment target is standalone and self-hosted — a
  plain container on a desktop, a NAS, a workstation. The Home
  Assistant App is one **additional** target, and what survives of the
  fat App is exactly the `subprocess` profile. Nothing about this
  deployment fixes the number of Apps: a dashboard App may talk to a
  build server that is an App, a machine in the same flat, or a host on
  the other side of the internet, and it is the same client either way.
  ADR 0012's first consequence, which asserted that this ADR's
  topology "stands unchanged", is amended to match on 2026-08-09: what
  that ADR decided — the build server's own repository and its
  dependency on the contract — stands; the two-App pair does not.
- **Decision 3's "the build server … *is* the toolchain container"**
  (firmware ADR 0007's builder image, one build environment). A build
  server drives **any** conforming build container, identified by its
  digest and reached across a frozen invocation ABI (ADR 0019
  decision 4, contract §5); which packages it itself consumes is
  ADR 0020 decision 4. It is therefore no longer definitionally one
  image.

**Carries forward.**

- **Decision 2 — the dashboard never compiles.** There is no local
  build path and none is to be added; the same-host case is not a
  special case. ADR 0012 decision 3 restates it, and the four-repository
  layout of firmware ADR 0017 §1 keeps the dashboard free of the
  toolchain (firmware ADR 0017 §2).
- **The deployment facts in the Context**, unchanged and still
  decisive: Apps are `amd64`/`aarch64` only; the reference host is an
  `rpi4-64` with 1.93 GiB of RAM against a Zephyr+Matter build measured
  at 13:38 cold on a 4-core/15 GiB machine and 1:12 on a 16-core/28 GiB
  machine; the image is ~560 KiB of near-non-optional Matter and
  OpenThread; our build-container image is 2.74 GB uncompressed, 798 MB
  of it zap/Electron. With them, decision 5's evidence gate on aarch64,
  the consequence that a user with only a Raspberry Pi cannot build
  firmware, and decision 4's rule that App packaging lives in the
  packaging repository.
- **"App", never "Add-on"**, in every user-facing string.

### Correction of fact: a Home Assistant App *can* start containers

The Context's second fact — "Docker-in-docker is impossible for an app.
The Supervisor grants `docker_api` read-only, so an app cannot start the
builder image (firmware ADR 0007) as a child container" — is wrong as
stated, and is corrected here rather than left standing.

The published option is documented as: *"Allow read-only access to the
Docker API for the app. Works only for not protected apps."* (Home
Assistant developer documentation, App configuration). The second
sentence is the operative one. `docker_api: true` is unlocked by
`protected: false`, and what an unprotected App then gets is a bind
mount of the host's real Docker socket. "Read-only" is not a restriction
on a Unix domain socket: a client *connects* to a socket, it does not
read a file through the mount, so the read-only flag constrains nothing
about the Engine API reachable through it. An App configured that way
has full Engine API access on the host and can start containers.

This is measured, not reasoned. On the development machine, 2026-08-09,
a container started with
`-v /var/run/docker.sock:/var/run/docker.sock:ro` sent
`POST /v1.43/containers/create` (128 bytes) through that socket and
received `HTTP/1.1 400 Bad Request`. The daemon accepted the write
request, answered it, and rejected only its body — a mount that
actually blocked writing would have failed the connection or the
request itself, not validated a payload. Stated plainly: the `ro` flag
protects the socket *file*; it does not touch the API behind it.

The price is real, which is why this is recorded as a correction of fact
and not as a recommendation: it requires the operator to turn protection
mode off, and the Supervisor rates an App on the rights it asks for and
surfaces that rating to the user (Home Assistant developer
documentation, App security).

The exact price is worth stating precisely, because the two published
sources for it do not agree and this ADR does not get to pick the more
convenient one.

The **implementation** rates on a 1-8 scale. `rating_security()` in
`supervisor/apps/utils.py` of `home-assistant/supervisor` (read at
`main`, 2026-08-09; the directory is `apps/`, not `addons/`, since the
Apps rename) is documented in its own docstring as *"Return 1-8 for
security rating. 1 = not secure, 8 = high secure"* (`:19-24`), starts
every App at `rating = 5` (`:25`), applies the
individual adjustments, and finally returns `max(min(8, rating), 1)`
(`:86`). Immediately before that clamp it discards everything computed
so far: `if app.access_docker_api or app.with_full_access: rating = 1`
(`:83-84`). Either right forces the rating to the minimum outright.

The **documentation prose** describes a 1-6 scale. *"Each app starts
with a base rating of 5, on a scale of 1 to 6"* (Home Assistant
developer documentation, Presenting your app — Security), and *"An app
with a rating of 6 is very secure. If an app has a rating of 1, you
shouldn't run this app unless you are 100% sure that you can trust the
source"* (App security).

Both agree on the part that decides anything here: the same
documentation table lists `docker_api: true` as *"Security set to 1 —
Overrides all other adjustments"*, which is exactly what `:83-84` does.
They disagree only about the top of the scale. Until upstream resolves
that, MCUHome quotes the outcome — the lowest rating the Supervisor
gives — and never a ratio, because "1/8" and "1/6" cannot both be
cited as ours.

One figure from the engineering note this correction started from does
not survive the check at all: `docker_api` does not bring
`hassio_role: admin` with it. The admin penalty is a separate branch on
`hassio_role` in the same function (`:65-68`), so an App that asks for
the Docker API has not thereby asked for the admin role.

**The topology decision no longer rests on this either way.** Under the
two backend profiles the Home Assistant case does not start a container
at all: the backend runs the program as a subprocess in a shared
filesystem namespace (contract §1.2). And what actually decides against
compiling on the reference host is the third Context fact — 1.93 GiB of
RAM — which no Supervisor option changes.

## Context

The dashboard's primary deployment is a Home Assistant app. Three facts
about that target decide the topology, and none of them is negotiable.

**Home Assistant renamed "Add-ons" to "Apps" in 2026.2.** The old term is
wrong in every user-facing string from now on. Apps are built for
`amd64` and `aarch64` only — 32-bit support ended in 2025.12.

**Docker-in-docker is impossible for an app.** The Supervisor grants
`docker_api` read-only, so an app cannot start the builder image
(firmware ADR 0007) as a child container. The one mechanism that would
let a single app both serve a UI and run the containerized toolchain
does not exist.

**The reference host cannot compile anyway.** Stefan's production Home
Assistant is an `rpi4-64` with **1.93 GiB of RAM**. A Zephyr+Matter
build is 13:38 cold on a 4-core/15 GiB developer machine at `-j2` and
1:12 on a 16-core/28 GiB machine (measured 2026-08-07); the image
`samples/matter-node` produces is ~560 KiB of almost entirely
non-optional Matter and OpenThread. A Pi 4 with 1.93 GiB is not a slow
build machine, it is not a build machine.

Two more numbers frame the packaging: our builder image is 2.74 GB
uncompressed and amd64-only today, of which **798 MB is zap/Electron**;
ESPHome's app image is ~350 MiB compressed and downloads toolchains into
`/data` at first build. Shipping one fat image to every dashboard user
is not an option that was rejected — it is an option that does not fit
the target.

`builder-pipeline.md` §6 already designed a build-service boundary with
a local and a remote implementation, expecting v0.1 to ship "local".
The facts above invert that.

## Decision

### 1. Two apps, with different shapes and different audiences

| App | Contents | Architectures |
|---|---|---|
| `mcuhome-ui` | web interface, builder package, no toolchain | amd64 + aarch64, trivially |
| `mcuhome-buildserver` | headless build service, builder image contents | amd64 now; aarch64 gated (see below) |

The dashboard is thin by construction: pure Python plus static assets,
so multi-arch is a build-matrix line and nothing else. The build server
is the fat half, and it is installable **inside Home Assistant or
self-hosted anywhere** — a plain container on a desktop, a NAS, a
workstation under a desk. The two are separate products with separate
version numbers, joined by one protocol (ADR 0006).

### 2. The dashboard never compiles — every build is remote

There is no local build path, not even when both apps run on the same
Home Assistant instance. That case is not a special case: the dashboard
talks to the build server over the protocol, across the Supervisor
network, exactly as it talks to a machine on the other side of the
internet. Auto-pairing (ADR 0006) makes it feel local; nothing in the
code knows the difference.

This is the point of the decision. One code path is tested by every
user, on every deployment, from day one. `builder-pipeline.md` §6's
"local, in-process invocation of the builder package" is retired as a
v0.1 shape.

### 3. Distribution targets beyond Home Assistant

Unchanged from AGENTS.md, and now spelled out per app: standalone Docker
images for both, and a plain Python installation for the dashboard.
The build server has no plain-Python installation — it *is* the
toolchain container (firmware ADR 0007: the builder image is the single
build environment, used identically by developers, CI and the apps).

### 4. Packaging lives in the packaging repo; source lives with the code

App packaging — `config.yaml`, Dockerfile, s6-overlay v3 services,
repository metadata — for **both** apps goes to the future packaging
repo. No app packaging files in this repository (the existing AGENTS.md
invariant, now covering two apps instead of one).

The dashboard's source is this repository. The build server's source is
a thin server wrapping the `mcuhome` builder package and must version in
lockstep with the builder image it contains; where that source lives is
fixed when it is implemented, with the lockstep requirement as the
deciding argument.

### 5. aarch64 for the build server is gated on evidence

The Zephyr SDK is first-class on aarch64 and `gn` has a CIPD linux-arm64
build, but `zap-cli` is shaky there (it has broken on arm64 at least
once), and no measured RAM or time figure for a Zephyr+Matter build on a
Pi-class machine exists at all. The build server therefore ships amd64
first. Two levers make the aarch64 question winnable and both are
firmware-side work items (ADR 0011): pre-generated ZAP output, which
removes Node/Electron entirely, and CI-produced ccache packs. Until then
the honest statement is that an aarch64 build server is unverified, and
a self-hosted amd64 machine is the supported path.

## Consequences

- Two release artifacts, two version numbers, one negotiated protocol
  between them (ADR 0006). Version-range and `model_version` handshakes
  are not optional plumbing; they are how the pair stays coherent.
- **A user with only a Raspberry Pi cannot build firmware.** They need a
  second machine, or eventually a hosted build server (ADR 0006's
  outlook). This belongs at the top of the install documentation, not in
  a troubleshooting section, because it decides whether the product is
  usable for someone before they install anything.
- The dashboard's footprint stays inside what the reference host has
  left over after running Home Assistant.
- "App", never "Add-on", in every user-facing string, screenshot and
  document.
- The multi-GB west workspace is **baked into the build-server image**
  (PO decision, 2026-08-07): a container without internet access must
  still be able to build — offline capability outranks image size. A
  second, *minimal* build-server variant that provisions the workspace
  on first run (ESPHome's pattern) may be offered later as an
  alternative for users who prefer a small download; both variants
  would share the same protocol and app surface.
- **Out of MVP, stated so nobody looks for it:** the dashboard does not
  know which Home Assistant entity a device it built became. v0.1 is
  Matter-only with no custom HA integration (firmware ADR 0010), so
  there is no channel through which that link could be established.
- Related standing decisions: firmware ADR 0007 (containerized
  toolchain, one build environment), firmware ADR 0010 (Matter-only, no
  custom integration), `builder-pipeline.md` §6, and dashboard ADRs
  0006 (the protocol) and 0011 (the builder coupling).
