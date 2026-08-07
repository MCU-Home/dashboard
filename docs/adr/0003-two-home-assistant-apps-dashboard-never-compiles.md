# 0003 — Two Home Assistant Apps; the dashboard never compiles

- Status: accepted
- Date: 2026-08-07

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
| `mcuhome-dashboard` | web interface, builder package, no toolchain | amd64 + aarch64, trivially |
| `mcuhome-build-server` | headless build service, builder image contents | amd64 now; aarch64 gated (see below) |

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
- One implementation question stays open for the build server's own
  design: whether the multi-GB west workspace is baked into the image
  (~6 GB) or provisioned by `west update` into `/data` on first run
  (ESPHome's pattern). Both work; the trade is image size against
  first-run time, and it is decided with real numbers when the build
  server is built.
- **Out of MVP, stated so nobody looks for it:** the dashboard does not
  know which Home Assistant entity a device it built became. v0.1 is
  Matter-only with no custom HA integration (firmware ADR 0010), so
  there is no channel through which that link could be established.
- Related standing decisions: firmware ADR 0007 (containerized
  toolchain, one build environment), firmware ADR 0010 (Matter-only, no
  custom integration), `builder-pipeline.md` §6, and dashboard ADRs
  0006 (the protocol) and 0011 (the builder coupling).
