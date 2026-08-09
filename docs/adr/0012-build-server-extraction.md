# 0012 — Build-server extraction into its own repository

- Status: accepted; terminology and one consequence amended 2026-08-09
- Date: 2026-08-08

## Terminology (2026-08-09)

This ADR stands, and it is part of the valid 2026-08-08 layer, so its
wording is corrected rather than annotated as history. Two terms it uses
were retired the day after it was written. Where it says **"builder
container"** — in the Context and in decision 1 — read **build
container**, and the document it names is
`mcuhome/docs/design/build-container-contract.md` (firmware ADR 0019's
amendment). Where it says **"the lib"** — in the Context, in decision 2
and in the Consequences — read the published packages of firmware
ADR 0020 decision 1: the dashboard's in-process dependency is
`mcuhome-workbench`, which brings `mcuhome-model` with it. Nothing about
decision 2 changes except the name: the dependency is still a published
pip package rather than a sibling checkout, still declared as a version
range, and still points in one direction (ADR 0011).

## Context

The finalized remote-build architecture (firmware ADR 0017–0019) fixes
a four-repository layout: `mcuhome` (SDK + spec + codegen + lib,
published as a pip package, one shared version), `cli`, `dashboard`,
and `build-server`. Today the build server lives in this repository as
`buildserver/`, next to the dashboard backend, per ADR 0003 — two
products, two packages, one repo, joined by the ADR 0006 protocol and
kept from importing each other by test.

The architecture work moved the build server's substance out from
under this repository's decisions: its input is now the self-contained
build context (firmware ADR 0018), its client interface is the session
protocol, and its container interface is the builder container
contract (both firmware ADR 0019). What the build server depends on is
that contract — not the dashboard, and not even the `mcuhome` lib.

## Decision

### 1. The build server moves to its own repository

`buildserver/` leaves this repository for the `build-server` repo of
firmware ADR 0017. It depends on the build-container contract
(`mcuhome/docs/design/build-container-contract.md` — renamed with the
term on 2026-08-09, firmware ADR 0019's amendment) and versions
against it — not against this repository and not against the lib.

### 2. The dashboard depends on the lib pip package

The dashboard consumes the `mcuhome` lib as a published pip package,
replacing the sibling-checkout install. ADR 0011 is otherwise
unchanged: in-process import, a declared supported version range, and
a dependency that points in exactly one direction.

### 3. The dashboard talks to build servers via the session protocol

The dashboard is a client of the session protocol of firmware
ADR 0019 — `capabilities`, `open-session`, `send-context`,
`extend-context`, `lock-context`, `verify`, `build`, `cancel`,
`get-artifact`, `attach-session`, `close-session` — against any
conforming build server, local or remote. (`lock-context` and `cancel`
were added to the verb set on 2026-08-09 by that ADR's amendment; the
list is completed here rather than left short, because a client that
never locks the context can never reach `build`.) ADR 0006's transport and
threat-model decisions (WebSocket + bearer token, TLS at the
deployment, the leaked-token threat model, mDNS naming per the
amendment) carry forward under the new verbs; its job-frame vocabulary
and `GET /capabilities` endpoint are replaced by the session verbs and
the `capabilities` verb. The dashboard keeps what only it has: user
key handling and detached signing (ADR 0007/0008) — the build server
still never signs, and the dashboard still never compiles (ADR 0003).

## Consequences

- What this ADR decides stands: the build server's *source* lives in
  its own repository, and it versions against the build-container
  contract rather than against this one. What does **not** stand is
  ADR 0003's deployment topology. This consequence originally added
  that the two Home Assistant Apps — the thin dashboard and the fat
  build server — "stand unchanged", and that clause is struck
  (amended 2026-08-09): it was the one sentence in which the
  2026-08-08 layer contradicted itself, since the same layer replaces
  that pair. A build server is an orchestrator and never itself a
  build environment; standalone and self-hosted is its primary
  deployment target, and the Home Assistant App is one further target,
  served by the `subprocess` backend profile of the contract's §1.2.
  ADR 0003 records what of it survives and what does not.
- The "two packages never import each other" invariant becomes a
  repository boundary instead of a test. The shared-vocabulary
  comparison test arrangement goes away with the move; protocol
  conformance is anchored in the session protocol and the container
  contract instead.
- The dashboard's build views are written against the session
  protocol: session lifecycle, typed progress events with resumable
  offsets (which is what ADR 0006's resumable log follow becomes), and
  artifact download verified against result hashes.
- This ADR records the decision; the actual move is sequenced by the
  merge plan (firmware ADR 0017's consequences). Until it executes,
  `buildserver/` remains here and AGENTS.md describes the interim
  state.
- Related standing decisions: ADR 0003 (topology), ADR 0006
  (transport, carried forward; frame vocabulary replaced), ADR 0007
  (wire content), ADR 0008 (signing key custody), ADR 0011 (lib
  coupling); firmware ADR 0017, ADR 0018, ADR 0019.
