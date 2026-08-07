# 0009 — Authentication per deployment

- Status: accepted
- Date: 2026-08-07

## Context

The dashboard ships into three situations with three different amounts
of authentication already present, and treating them identically would
mean either annoying every Home Assistant user with a second login or
exposing every standalone user with none.

**Ingress.** The Supervisor gateway (`172.30.32.2`) is the only source
permitted to reach an ingress site, and Home Assistant has already
authenticated the user before the request arrives. The identity headers
that come with it — `X-Remote-User-Id`, `X-Remote-User-Name` — are
best-effort and **display-only**; they are not an authorization input
and must never become one.

**Two-site pattern, verified in the field.** ESPHome's app runs two
sites: a public one on 6052 that is auth-gated, and a trusted ingress
site bound to loopback plus the gateway with no authentication of its
own. Same process, same application, two bindings with two trust
assumptions.

**Standalone, surveyed.** ESPHome binds `0.0.0.0` with no
authentication and prints a warning. code-server binds `127.0.0.1` and
generates a password by default. OWASP's guidance for local web tools is
the stricter of the two: bind to localhost, and use CSRF tokens on
state-changing requests.

**What a session is worth here** is higher than for either of those
tools. The dashboard holds the firmware signing key (ADR 0008) and can
submit jobs to a build server (ADR 0006) — ESPHome documents their own
threat model as "a compromised authenticated session is equivalent to
shell access", and ours is that plus a signing key.

## Decision

### 1. In the Home Assistant app: two sites

The **ingress site** binds loopback plus the Supervisor gateway and
performs no authentication of its own — Home Assistant already did, and
a second login inside an authenticated iframe is friction with no
security value. `X-Remote-User-*` is used to show who is logged in, and
for nothing else.

A **public site** (for users who expose the dashboard outside ingress)
follows the standalone rules below.

### 2. Standalone and Docker: localhost by default, never open unauthenticated

- Default binding is `127.0.0.1`.
- Binding `0.0.0.0` **requires** a password.
- If no password is configured, one is generated on first run and
  printed to the log — the code-server pattern.

There is no configuration in which the dashboard listens on a network
interface without authentication, not even behind a warning. The
generated-password path is what keeps a fresh container usable in one
step while keeping it closed.

### 3. WebSocket origin check, CSRF tokens on state-changing REST

Every WebSocket upgrade is origin-checked. The REST surface is small by
ADR 0004 — artifact download and health — so CSRF protection is a small
and complete addition rather than a policy applied across a large API.

### 4. Secrets are visible in the browser, and we say so

Ingress can be plain HTTP inside a LAN, and the editor legitimately
shows the user their own `secrets.yaml`. Both are accepted: the
dashboard's session is exactly as sensitive as the configuration tree it
edits. The project's standing rule on Thread datasets still binds —
resolved into a build, never persisted into dashboard state, never
written to a log.

### 5. One sentence goes into SECURITY.md

> An authenticated dashboard session is equivalent to shell access on
> the build server and holds the firmware signing key.

Written in those words, in the scope section, next to the surfaces it
describes.

## Consequences

- No user database, no session store, no password reset, no roles.
  **Multi-user is explicitly out of v0.1** — in the app deployment Home
  Assistant is the authenticator, and in the standalone deployment there
  is one password.
- TLS and stronger authentication for an internet-exposed standalone
  deployment come from a reverse proxy in front. The dashboard does not
  terminate TLS and does not pretend to.
- The two-site split has to be real in the code — one binding that
  trusts its peer and one that does not — and a misconfiguration that
  exposes the trusting site is the failure mode worth a test.
- Related standing decisions: ADR 0003, ADR 0004, ADR 0006 (whose
  bearer token has the same blast radius), ADR 0007, ADR 0008.
