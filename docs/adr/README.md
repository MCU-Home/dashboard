# Architecture Decision Records

Dashboard-specific design decisions, in lightweight
[MADR](https://adr.github.io/madr/) style: **Context / Decision /
Consequences**, plus a status. Project-wide decisions (license,
repository split, versioning) live in the firmware repository:
[mcu-home/mcuhome/docs/adr](https://github.com/mcu-home/mcuhome/tree/main/docs/adr).

## Lifecycle: draft first, final when real

ADRs follow the project-wide draft-first lifecycle of
[firmware ADR 0021](https://github.com/mcu-home/mcuhome/blob/main/docs/adr/0021-draft-first-adr-lifecycle.md):
an ADR starts in [`draft/`](draft/) as a **living document** — while
the component it decides about is being built, changes land as better
text, never as amendment or erratum sections; git history is the
changelog. `draft` describes the document's maturity, not missing
approval. When the component is implemented and verified, the ADR is
finalized: rewritten from the real result and moved to this directory
with a `Finalized:` date. Final ADRs are **immutable** except for their
status line (`superseded by NNNN`); changing a finalized decision means
a new draft that supersedes the old final. Numbers come from one
sequence and follow the document for life.

Statuses: `draft` (in `draft/`), `accepted`, `deferred`,
`superseded by NNNN`.

## Final ADRs

| ADR | Title | Status |
|---|---|---|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | accepted; lifecycle per firmware ADR 0021 |
| [0002](0002-python-backend-typescript-frontend.md) | Python backend with TypeScript frontend | accepted |
| [0003](0003-two-home-assistant-apps-dashboard-never-compiles.md) | Two Home Assistant Apps; the dashboard never compiles | superseded for the build-service subject by firmware ADR 0017-0020 |
| [0004](0004-aiohttp-backend-and-websocket-first-api.md) | aiohttp backend, Python ≥ 3.13, WebSocket-first API | accepted |
| [0005](0005-lit-webawesome-codemirror-frontend.md) | Frontend: Lit 3, webawesome, CodeMirror 6, TypeScript, Vite | accepted |
| [0006](0006-build-service-protocol.md) | The build-service protocol | superseded for the build-service subject by firmware ADR 0017-0020 |
| [0007](0007-wire-content-and-credential-exposure.md) | What crosses the wire, and what the build server learns | accepted |
| [0009](0009-authentication-per-deployment.md) | Authentication per deployment | accepted |
| [0011](0011-builder-coupling-and-interface-contract.md) | Builder coupling and the firmware-side interface contract | superseded for the build-service subject by firmware ADR 0017-0020 |
| [0012](0012-build-server-extraction.md) | Build-server extraction into its own repository | accepted; decision 3's session client superseded by 0013 |
| [0013](0013-building-over-the-builder-package.md) | Building over the builder package, not over a protocol | accepted |
| [0014](0014-ingress-admin-only-and-abuse-limits.md) | Ingress is admin-only; login throttling and concurrency limits | accepted |

## Draft ADRs

Numbers missing above live here — they are the same sequence.

| ADR | Title |
|---|---|
| [0008](draft/0008-state-layout-signing-key-and-backups.md) | State layout: config tree, signing key, retention, backups |
| [0010](draft/0010-flash-flow-ladder.md) | The flash-flow ladder; v0.1 stops at rung 1 |
| [0015](draft/0015-the-dashboard-opens-projects-it-does-not-manage-them.md) | The dashboard opens a project; it does not manage one |
| [0016](draft/0016-build-progress-travels-in-the-record.md) | Build progress travels in the record, as facts |
