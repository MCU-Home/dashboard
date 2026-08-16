# frontend/

The MCUHome Dashboard's single-page application: **Lit 3**,
**`@home-assistant/webawesome`**, **CodeMirror 6**, TypeScript, built
with **Vite** (ADR 0005).

It talks to the backend over one WebSocket (`/ws`, ADR 0004);
`../backend/README.md` is the frame vocabulary and this application's
only contract.

## Getting started

Node ≥ 22.13 and pnpm. The pnpm version is pinned by `packageManager` in
`package.json`; `corepack enable` picks it up from there. (The floor is
22.13 because the pinned pnpm refuses to start below it — CI runs exactly
this floor so it cannot quietly stop being true. If corepack fails with
"Cannot find matching keyid", its bundled signing keys are older than the
registry's: update corepack, or install pnpm directly.)

```sh
pnpm install
pnpm dev            # dev server on http://127.0.0.1:5173
```

`pnpm dev` serves the frontend and proxies `/ws`, `/health`, `/auth` and
`/api` — the last one being the artifact downloads of ADR 0013 — to a
backend you start yourself:

```sh
# in another terminal, from the repository root
cd backend && mcuhome-dashboard --config-root ~/mcuhome-config
```

Point the proxy somewhere else with
`MCUHOME_DASHBOARD_URL=http://otherhost:8099 pnpm dev`.

| Command | What it does |
|---|---|
| `pnpm dev` | Vite dev server, hot module replacement, proxy to the backend |
| `pnpm build` | type-check both programs, then emit `dist/` |
| `pnpm test` | vitest, once |
| `pnpm test:watch` | vitest, watching |
| `pnpm lint` / `pnpm lint:fix` | eslint (type-aware) |
| `pnpm format` / `pnpm format:check` | prettier |
| `pnpm check` | format, lint, types and tests — what a commit should pass |

## Serving the built application

`pnpm build` writes `dist/`. The backend serves whatever directory
`--static-root` names:

```sh
pnpm build
mcuhome-dashboard --config-root ~/mcuhome-config --static-root ../frontend/dist
```

Equivalently `MCUHOME_DASHBOARD_STATIC_ROOT=…`. The backend's *default*
static root is `backend/mcuhome_dashboard/static/`, which ships a
diagnostic page rather than the application — packaging (a separate work
block) is what copies a `dist/` into the wheel. Nothing in this
directory writes into the Python package, so a source checkout never has
build output sitting inside it.

## Two things that are not obvious

### The base path is injected, and nothing may hard-code a path

The dominant deployment is an iframe behind Home Assistant ingress,
served from `/api/hassio_ingress/<token>/` while the backend still sees
`/`. The backend reads `X-Ingress-Path` per request and injects a `base`
element plus `window.MCUHOME_BASE_PATH` into the document (ADR 0005
decision 4). So:

- Vite runs with `base: './'` — every asset URL in `dist/index.html` is
  relative and resolves against the injected base.
- `src/base-path.ts` is the **only** module that reads
  `window.MCUHOME_BASE_PATH`. It exists for the URLs the base element
  does not cover, and above all for `new WebSocket()`, which resolves
  against the document URL and ignores it entirely.
- Routing lives in the fragment (`#/devices/<name>`), which no proxy
  rewrites and which needs no prefix at all.

**The injection is found by a regular expression matching the opening
`head` tag.** Anything earlier in `index.html` that spells that tag —
including inside a comment — is matched first and the injection lands
somewhere inert. `index.html` says so where it matters; if you edit it,
keep prose about that tag *inside* the element.

### The new-device form knows no hardware

`mh-new-device` offers boards, parts, buses, clusters and device types,
and not one of them is written down in this repository. They arrive from
`device/boards` — the builder's registry — and the form constrains the
choices against each other from that same data: a part is only offered a
bus of the kind its driver speaks, and an entry is only offered a
reading whose quantity matches the cluster. That is what makes the form
correct rather than merely convenient, and it is why it grows when the
registry does without anybody editing it. It is also why it looks thin
today: MCUHome supports one board and one part.

Nothing is pre-validated. A name that cannot become a hostname, a board
nobody brought up, a device that already exists — all of those are
refusals the builder gives, with the fix hint the command line prints,
because a check here would be a second opinion about a rule that lives
in another repository.

### The theme is self-detected

No theme crosses the ingress iframe boundary — there is no mechanism,
and that was checked (ADR 0005 decision 5). The application follows
`prefers-color-scheme` and offers an override it remembers, which is the
fix for a dark Home Assistant on a light desktop. Web Awesome switches on
a `wa-light`/`wa-dark` class on the root element; components style
themselves in `--wa-*` custom properties and follow along without knowing
a theme exists.

## Layout

| Path | Role |
|---|---|
| `src/base-path.ts` | every URL, built from the injected prefix |
| `src/router.ts` | fragment routing |
| `src/strings.ts` | every user-facing string, structured for a future translation |
| `src/webawesome.ts` | the Web Awesome components in use, and the theme stylesheet |
| `src/api/protocol.ts` | the frame vocabulary; the only place text becomes a typed frame |
| `src/api/client.ts` | the WebSocket: correlation, reconnect, events |
| `src/api/commands.ts` | one function per command |
| `src/api/session.ts` | login, logout, the reachability probe |
| `src/api/types.ts` | what the command results contain |
| `src/state/device-store.ts` | the device list, as snapshot-then-events |
| `src/state/build-store.ts` | the builds, and the log offsets that make a lost batch of output recoverable |
| `src/state/validity.ts` | the per-device validity badges, and their queue |
| `src/state/theme.ts` | light/dark |
| `src/components/mh-new-device.ts` | the new-device form, made entirely out of `device/boards` |
| `src/components/` | the Lit elements |
| `test/` | vitest |

## Conventions

- TypeScript `strict`, plus `noUncheckedIndexedAccess` and
  `verbatimModuleSyntax`. Two programs (`tsconfig.app.json`,
  `tsconfig.node.json`) so browser code cannot reach a Node API by
  accident.
- eslint with `recommendedTypeChecked`, prettier for formatting, both
  wired into `pre-commit`.
- Lit with TypeScript's legacy decorators and
  `useDefineForClassFields: false` — the combination Home Assistant's and
  ESPHome's frontends run. Changing either silently breaks every
  reactive property.
- Apache-2.0 with an SPDX header in every new file; `reuse lint` runs in
  pre-commit.
- English only in this repository. Strings live in `src/strings.ts` and
  are never written inline, so adding a locale later is a second object
  rather than a sweep.

### Pinned versions, and why

- **`@home-assistant/webawesome` is pinned exactly.** It is Home
  Assistant's fork and they control its release cadence; ADR 0005 says to
  pin it and treat a breaking bump as an ordinary dependency event.
- **TypeScript 6, not 7.** TypeScript 7 is the native port and where the
  ecosystem is heading, but `typescript-eslint` 8 still caps at `<6.1`.
  Type-aware linting is worth more here than being on the newest
  compiler; revisit when `typescript-eslint` ships TypeScript 7 support.
- **Vite 8, and the decorator dialect has exactly one source.** Vite 8
  replaced esbuild with Rolldown, and this pin used to say "7, not 8"
  because the decorator dialect above is the kind of thing a bundler swap
  breaks quietly. It half did: `vitest.config.ts` carried an
  `esbuild.tsconfigRaw` override of that dialect, and Vite 8 ignores it
  in favour of Oxc — with a warning, and with nothing broken, because
  Oxc reads `tsconfig.app.json` for both the test transform and the
  build. So the override is gone rather than translated, and the
  tsconfig is the only place the dialect is written. Both halves of that
  are checked rather than believed: flipping `useDefineForClassFields`
  there turns 45 component tests red and changes the emitted bundle.

## What is not here yet

- **Schema-aware autocomplete and inline lint while typing** — needs the
  registry and JSON-Schema export from the firmware repository. The
  editor has the autocompletion extension mounted with no schema source;
  the schema may not be reimplemented here, because the firmware
  repository owns it.
- **Flashing** — the flash ladder (ADR 0010). Building and its log are
  here: the device page starts a build, follows its output live and
  offers what came out for download.
- **Translations** — structure is in place, English only.

Each is marked `TODO(block-0):` where the code would change.
