# 0012 — Build-server extraction into its own repository

- Status: accepted
- Date: 2026-08-08

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
firmware ADR 0017. It depends on the builder container contract
(`mcuhome/docs/design/builder-container-contract.md`) and versions
against it — not against this repository and not against the lib.

### 2. The dashboard depends on the lib pip package

The dashboard consumes the `mcuhome` lib as a published pip package,
replacing the sibling-checkout install. ADR 0011 is otherwise
unchanged: in-process import, a declared supported version range, and
a dependency that points in exactly one direction.

### 3. The dashboard talks to build servers via the session protocol

The dashboard is a client of the session protocol of firmware
ADR 0019 — `capabilities`, `open-session`, `send-context`, `build`,
`get-artifact`, `attach-session`, `close-session` — against any
conforming build server, local or remote. ADR 0006's transport and
threat-model decisions (WebSocket + bearer token, TLS at the
deployment, the leaked-token threat model, mDNS naming per the
amendment) carry forward under the new verbs; its job-frame vocabulary
and `GET /capabilities` endpoint are replaced by the session verbs and
the `capabilities` verb. The dashboard keeps what only it has: user
key handling and detached signing (ADR 0007/0008) — the build server
still never signs, and the dashboard still never compiles (ADR 0003).

## Consequences

- ADR 0003's deployment topology — two Home Assistant Apps, the thin
  dashboard and the fat build server — stands unchanged; what this ADR
  changes is only where the build server's *source* lives and which
  contract it implements.
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
