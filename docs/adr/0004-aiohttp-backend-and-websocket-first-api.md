# 0004 — aiohttp backend, Python ≥ 3.13, WebSocket-first API

- Status: accepted
- Date: 2026-08-07

## Context

ADR 0002 fixed a Python backend and deferred the web framework and the
backend↔frontend API shape. This closes both.

**What this application actually is.** Its state is a tree of YAML files
and a queue of long-running jobs on another machine (ADR 0003). Its
dominant interaction is a stream: build output arriving line by line,
job state changing, a device list that must not go stale while an editor
is open. It has no database, no ORM, no multi-tenant request/response
CRUD surface, and one client — our own SPA (ADR 0005).

That matters because it makes FastAPI's strengths irrelevant here.
Pydantic request/response models, automatic OpenAPI generation and the
dependency-injection system pay off on a REST API with many endpoints,
many consumers and typed JSON bodies. A WebSocket-first application with
one endpoint gets none of that value and still carries the dependency.

**Ecosystem convergence, verified from live package metadata.** Home
Assistant core is aiohttp. ESPHome's 2026 rewrite — the new Device
Builder (2026.5/2026.6) — retired Tornado and chose **aiohttp**, WS-first,
single endpoint, with REST kept only for compatibility. Both of the
projects whose problem is closest to ours, one of them having just
re-decided this question from scratch, landed on the same answer.

**Ingress base paths are manual work in every framework.** The
`X-Ingress-Path` header is not `root_path`, not a mount prefix, and no
framework handles it: whatever we pick, the prefix is read per request
and applied by us (ADR 0005 decision 5). There is no framework that wins
on this axis.

**Python floor.** `backend/pyproject.toml` inherited `>=3.11` from the
scaffold, matching the builder package. The builder needs a low floor —
contributors `pip install -e` it onto whatever Python their distribution
ships. The dashboard does not: it ships as a container with an
interpreter we choose, or as an app whose base image we choose.

## Decision

### 1. aiohttp

`aiohttp` serves HTTP and WebSocket. It is the smallest sufficient
choice, it is what our two reference projects run, and its WebSocket
support is a first-class part of the server rather than an add-on.

### 2. Python ≥ 3.13

`requires-python = ">=3.13"` and `target-version = "py313"` for ruff.
The dashboard's floor may sit above the builder package's and never
below it — the dashboard imports the builder (ADR 0011), so a superset
is always safe and the reverse never is.

### 3. One WebSocket endpoint, `/ws`, carrying two frame kinds

- **Command / response.** The client assigns an id; every response and
  every error carries it back.
- **Event subscription.** The client subscribes to a topic; the server
  pushes over an in-process event bus that build progress, file changes
  and job-state transitions all publish into.

Everything that changes state and everything that streams goes through
this endpoint. Lists follow **snapshot-then-events**: the subscription
answer is the current state, and every change after it arrives as an
event. ESPHome documented this explicitly as the fix for the
read-once-and-refetch races in their legacy dashboard; we start where
they finished.

### 4. REST only where a browser primitive needs a URL

Artifact download (`GET`, so the browser's own download machinery and a
stable link work — ADR 0010 depends on this) and a health/version
endpoint. No parallel REST CRUD surface: two ways to change the same
state is two ways to be inconsistent.

### 5. The builder is imported in-process, and its errors are the API

The `mcuhome` package is imported, not spawned (ADR 0011). A
`ConfigError` already carries file, line, column, dotted key and a fix
hint (`mcuhome/errors.py`); serialized into a WS frame it lands on the
editor's gutter as a marker with a tooltip. Nothing is scraped from
stdout, no exit code is interpreted, and a validation error never
degrades into "the build failed, see log".

## Consequences

- One connection carries the entire UI, so reconnect-and-resubscribe is
  the single failure mode worth designing carefully. Ingress supports
  WebSockets, so no deployment loses this.
- No OpenAPI document falls out for free. The frame vocabulary is
  documented by hand — it is small, and ADR 0006's build-service
  vocabulary already sets the pattern.
- aiohttp brings no validation or dependency-injection batteries.
  Incoming frames are validated by hand against the vocabulary. With one
  first-party client and an authenticated session (ADR 0009) that is a
  bounded cost, not a lurking one.
- Anything CPU-bound blocks the event loop. By ADR 0003 the dashboard
  has nothing CPU-bound left: compiling is remote, and signing (ADR
  0007) is a single P-256 operation.
- The API becomes a product surface, not an implementation detail — the
  future VS Code extension (ADR 0005) is its second consumer, and that
  is an argument for keeping the vocabulary honest from the first frame.
- Related standing decisions: ADR 0002 (whose backend deferral this
  closes), ADR 0003, ADR 0005, ADR 0006, ADR 0009, ADR 0011.
