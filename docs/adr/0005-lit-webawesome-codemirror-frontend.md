# 0005 — Frontend: Lit 3, webawesome, CodeMirror 6, TypeScript, Vite

- Status: accepted
- Date: 2026-08-07

## Context

ADR 0002 fixed a TypeScript SPA and deferred the framework and build
tooling. This closes both.

**The dominant deployment is an iframe inside Home Assistant.** A
dashboard that looks foreign there is not merely unpolished — it reads
as a third-party bolt-on, which is the opposite of what MCUHome is
trying to be. Matching the HA look is therefore a product requirement,
and normally an expensive one.

**It is available for free.** `@home-assistant/webawesome` is a
**public MIT npm package** — Home Assistant's fork of Web Awesome
(Shoelace), the component library their own frontend is built from, and
it ships `qr-creator`, which is exactly what a commissioning QR code
needs. Consuming it gives the native look without reimplementing it and
without tracking it by hand.

**What the reference projects run**, verified from live package files:
Home Assistant frontend is Lit 3.3.3 + CodeMirror 6 + Rspack; ESPHome's
new Device Builder frontend is the same stack plus `@home-assistant/
webawesome` and TypeScript 7. Two independent projects, one of them
having chosen it in 2026 from scratch.

**Editors.** Monaco was evaluated and rejected by both projects — it
needs web workers and weighs roughly ten times CodeMirror. CodeMirror 6
with `@lezer/yaml` is the ecosystem's answer for YAML.

**Themes do not cross the ingress iframe boundary.** No mechanism
exists — this was checked and the result is negative, not unknown.

## Decision

### 1. Lit 3 + TypeScript + Vite

Lit 3 web components; TypeScript throughout. **Vite** as the build tool,
a deliberate divergence from Home Assistant's and ESPHome's Rspack:
their bundler choice carries their history and their bundle sizes, and
there is no reason to inherit either into a greenfield SPA. Vite is the
smaller setup for what we are building.

Web components also make the base-path problem cheap (decision 5) —
there are no framework-generated absolute asset URLs to rewrite.

### 2. `@home-assistant/webawesome` as the component library

The HA look, the HA interaction patterns, and the QR renderer, from one
MIT dependency.

### 3. CodeMirror 6 as the YAML editor — product-owner decision

Monaco and any form of embedded VS Code are **explicitly rejected**.
A browser tab is not where a full IDE belongs, and the weight buys
nothing the dashboard's editing session needs.

The power-user path is not a heavier in-page editor: it is a future
**official MCUHome VS Code extension** talking to the dashboard's API
(ADR 0004) — real IDE on one side, real dashboard on the other, each
doing what it is good at. That is post-v1.0, and it is a standing reason
to keep the WebSocket vocabulary a documented product surface rather
than an internal detail of this SPA.

### 4. Base path from `X-Ingress-Path`, per request

The application never assumes it is served from `/`. The server reads
`X-Ingress-Path` per request and injects the resulting base into the
served document; every client-side URL — API, WebSocket, artifact
download, router link — is built relative to it. Nothing in the bundle
hard-codes a path, so the same build serves ingress, a reverse proxy
sub-path and a bare root.

### 5. Theme by self-detection

The frontend follows `prefers-color-scheme` and offers an explicit
override that it persists. There is no ingress theme bridge to hook
into, so an iframe on a dark HA with a light system theme will start
light — the override exists for exactly that case. Revisit if Home
Assistant ever ships a propagation mechanism.

## Consequences

- The native HA look comes for free, at the price of a dependency on a
  fork Home Assistant controls. Pin it; treat a breaking bump as an
  ordinary dependency event, not a crisis.
- The contributor pool for Lit is smaller than for React or Vue. It is
  also exactly the pool that already contributes to Home Assistant and
  ESPHome — the people most likely to show up here.
- Frontend scaffolding — `package.json`, lockfile, eslint/prettier,
  the prettier/eslint pre-commit hooks and the CI job — lands with the
  first frontend commit. ADR 0002's "`frontend/` stays a placeholder"
  and the deferral notes in `.pre-commit-config.yaml` and `CLAUDE.md`
  are discharged by this ADR.
- CodeMirror with `@lezer/yaml` gives syntax highlighting, folding and
  bracket handling immediately. **Schema-aware autocomplete and inline
  lint need the registry and JSON-Schema export from the firmware repo**
  (ADR 0011) — without it the editor is a good text editor and not a
  configuration editor.
- Commissioning QR codes render from webawesome's `qr-creator`; no
  extra dependency, and the payload never leaves the browser.
- Related standing decisions: ADR 0002 (whose frontend deferral this
  closes), ADR 0003, ADR 0004, ADR 0011.
