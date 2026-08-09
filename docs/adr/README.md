# Architecture Decision Records

Dashboard-specific design decisions, in lightweight
[MADR](https://adr.github.io/madr/) style: **Context / Decision /
Consequences**, plus a status (`proposed`, `accepted`, `deferred`,
`superseded by NNNN`). Project-wide decisions (license, repository split,
versioning) live in the firmware repository:
[mcu-home/mcuhome/docs/adr](https://github.com/mcu-home/mcuhome/tree/main/docs/adr).

| ADR | Title | Status |
|---|---|---|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | accepted |
| [0002](0002-python-backend-typescript-frontend.md) | Python backend with TypeScript frontend | accepted |
| [0003](0003-two-home-assistant-apps-dashboard-never-compiles.md) | Two Home Assistant Apps; the dashboard never compiles | superseded for the build-service subject by firmware ADR 0017-0020 |
| [0004](0004-aiohttp-backend-and-websocket-first-api.md) | aiohttp backend, Python ≥ 3.13, WebSocket-first API | accepted |
| [0005](0005-lit-webawesome-codemirror-frontend.md) | Frontend: Lit 3, webawesome, CodeMirror 6, TypeScript, Vite | accepted |
| [0006](0006-build-service-protocol.md) | The build-service protocol | superseded for the build-service subject by firmware ADR 0017-0020 |
| [0007](0007-wire-content-and-credential-exposure.md) | What crosses the wire, and what the build server learns | accepted |
| [0008](0008-state-layout-signing-key-and-backups.md) | State layout: config tree, signing key, retention, backups | accepted |
| [0009](0009-authentication-per-deployment.md) | Authentication per deployment | accepted |
| [0010](0010-flash-flow-ladder.md) | The flash-flow ladder; v0.1 stops at rung 1 | accepted |
| [0011](0011-builder-coupling-and-interface-contract.md) | Builder coupling and the firmware-side interface contract | superseded for the build-service subject by firmware ADR 0017-0020 |
| [0012](0012-build-server-extraction.md) | Build-server extraction into its own repository | accepted; amended 2026-08-09 |
