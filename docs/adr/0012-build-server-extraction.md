# 0012 — Build-server extraction into its own repository

- Status: accepted; decision 3's session client superseded by 0013
- Date: 2026-08-08
- Finalized: 2026-08-14

## Context

The remote-build architecture (firmware ADR 0017–0019) fixes a
four-repository layout: `mcuhome` (SDK + spec + codegen + the builder's
Python packages — since firmware ADR 0020 the distributions
`mcuhome-model`, `mcuhome-workbench` and `mcuhome-compiler`, one shared
version), `cli`, `dashboard`, and `build-server`. When this was decided
the build server lived in this repository as `buildserver/`, next to
the dashboard backend, per ADR 0003 — two products, two packages, one
repo, joined by the ADR 0006 protocol and kept from importing each
other by test.

The architecture work moved the build server's substance out from under
this repository's decisions: its input is the self-contained build
context (firmware ADR 0018), its client interface is the session
protocol, and its container interface is the build-container contract
(`mcuhome/docs/design/build-container-contract.md`, firmware ADR 0019).
What the build server depends on is that contract — not the dashboard,
and not the builder beyond its shared vocabulary.

(This ADR was written one day before the project's terminology
settled; the text uses the settled terms. What was briefly called the
"builder container" is the **build container** of the contract above,
and "the lib" — then a single planned pip package — became the three
distributions of firmware ADR 0020 decision 1.)

## Decision

### 1. The build server moves to its own repository

`buildserver/` leaves this repository for the `build-server` repo of
firmware ADR 0017. It depends on the build-container contract and
versions against it — not against this repository. Of the builder's
packages it consumes exactly one, `mcuhome-model`: the shared
vocabulary, dependency-free by construction, carrying the context
format and the frozen context-ID rule of firmware ADR 0018 §6 that the
server is obliged to recompute from received bytes. As first written
this decision said "not even the lib", when the lib was one package
that included the whole builder; firmware ADR 0020's split made the
precise statement possible, and its decision 4 fixes it: the
vocabulary is shared as one implementation rather than two that agree
by inspection, and everything behind it stays out of reach by
declaration (`build-server/pyproject.toml` states the rule in place).

### 2. The dashboard depends on the builder pip package

The dashboard consumes the builder as a published pip package —
`mcuhome-workbench`, which brings `mcuhome-model` with it — replacing
the sibling-checkout install. ADR 0011 is otherwise unchanged:
in-process import, a declared supported version range (`versions.py`
names `mcuhome-workbench`, never the bare `mcuhome`, which since
firmware ADR 0020 decision 2 is the command line's distribution), and
a dependency that points in exactly one direction. While the
repositories are private and nothing is on PyPI,
`requirements-dev.txt` installs the same package from the sibling
checkout; the decision fixes what is declared and in which direction,
not the interim install source.

### 3. The dashboard talks to build servers via the session protocol

The dashboard is a client of the session protocol of firmware
ADR 0019 — `capabilities`, `open-session`, `send-context`,
`extend-context`, `lock-context`, `verify`, `build`, `cancel`,
`get-artifact`, `attach-session`, `close-session` — against any
conforming build server, local or remote. That is the complete
eleven-verb set, in this order, and completeness is the point:
`lock-context` and `cancel` entered the verb set on 2026-08-09 when
firmware ADR 0019 added them, and neither is optional — a client that
never locks the context can never reach `build`, and without `cancel`
a closed socket would be a client's only stop signal, which is no stop
signal at all.

ADR 0006's job client is dismantled rather than migrated: its
job-frame vocabulary and its `GET /capabilities` endpoint are replaced
by the session verbs and the `capabilities` verb. ADR 0006's transport
and threat-model decisions carry forward under the new verbs —
WebSocket + bearer token, TLS at the deployment boundary, the
leaked-token threat model, the same-host auto-pairing of its
decision 8, and its mDNS naming scheme (whose resolution stays with
the future packaging repo). The dashboard keeps what only it has: user
key handling and detached signing (ADR 0007/0008) — the build server
still never signs, and the dashboard still never compiles (ADR 0003).

One part of this decision was overtaken before it was implemented: the
session-protocol *client* was to be written here, and never was. By
the time the dashboard's build path was rebuilt, that client already
existed in `mcuhome-workbench` (`mcuhome.workbench.sessionclient`),
the package the dashboard imports in-process anyway — so the dashboard
calls `mcuhome.workbench.api.run_build` and the *package* speaks the
protocol (ADR 0013). Everything else in this decision stands: the
dismantling, the carry-forward, and the key custody.

## Consequences

- What this ADR decides stands: the build server's *source* lives in
  its own repository, and it versions against the build-container
  contract rather than against this one. What does **not** stand is
  ADR 0003's deployment topology. As first written, this consequence
  added that the two Home Assistant Apps — the thin dashboard and the
  fat build server — "stand unchanged"; that clause was struck the
  next day as the one sentence in which the 2026-08-08 layer
  contradicted itself, since the same layer replaces that pair. A
  build server is an orchestrator and never itself a build
  environment; standalone and self-hosted is its primary deployment
  target, and the Home Assistant App is one further target, served by
  the `subprocess` backend profile of the contract's §1.2. ADR 0003
  records what of it survives and what does not.
- The "two packages never import each other" invariant becomes a
  repository boundary instead of a test. The shared-vocabulary
  comparison test arrangement went away with the move; protocol
  conformance is anchored in the session protocol and the container
  contract instead.
- The dashboard's build views are written against the session
  semantics: session lifecycle, typed progress events with resumable
  offsets (which is what ADR 0006's resumable log follow becomes), and
  artifact download verified against result hashes. As built they get
  those semantics through `run_build` rather than through a protocol
  client of their own (ADR 0013).
- The move was sequenced by the merge plan of firmware ADR 0017's
  consequences and has executed: `buildserver/` and its test suite
  left this repository on 2026-08-08, and the build server lives at
  [mcu-home/build-server](https://github.com/mcu-home/build-server).
- Related standing decisions: ADR 0003 (topology; superseded in part,
  as above), ADR 0006 (transport and threat model, carried forward;
  frame vocabulary replaced), ADR 0007 (wire content), ADR 0008
  (signing key custody), ADR 0011 (builder coupling), ADR 0013 (the
  build path over the builder package); firmware ADR 0017, ADR 0018,
  ADR 0019, ADR 0020.
