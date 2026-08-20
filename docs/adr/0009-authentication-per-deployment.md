# 0009 — Authentication per deployment

- Status: accepted
- Date: 2026-08-07
- Finalized: 2026-08-14

## Context

The dashboard ships into three situations with three different amounts
of authentication already present, and treating them identically would
mean either annoying every Home Assistant user with a second login or
exposing every standalone user with none.

**Ingress.** The Supervisor gateway (`172.30.32.2`) is the only source
permitted to reach an ingress site, and Home Assistant has already
authenticated the user before the request arrives. Requests carry the
identity headers `X-Remote-User-Id` and `X-Remote-User-Name`; what they
may be used for is fixed in decision 1.

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
tools. The dashboard holds the firmware signing key (ADR 0008) and
starts builds — on a build server, or since ADR 0013 on its own host
with the default build method. ESPHome documents their own threat model
as "a compromised authenticated session is equivalent to shell access",
and ours is that plus a signing key.

## Decision

### 1. In the Home Assistant app: two sites

The **ingress site** performs no password authentication of its own —
Home Assistant already did, and a second login inside an authenticated
iframe is friction with no security value. Its control is *who is
talking to it*: it binds loopback plus this container's own address on
the Supervisor network (`172.30.32.0/23`), and refuses any peer that is
neither loopback nor the gateway
(`mcuhome.ui.security.is_trusted_peer`). The first phrasing of
this decision — "binds loopback plus the Supervisor gateway" — could not
be implemented literally: a bound address is local and the gateway's is
a peer's. The binding and the peer check together are the trust boundary
the sentence meant, and `server.supervisor_interface` documents the
translation.

The identity headers were first admitted for display only, on the
assumption that they are best-effort. ADR 0014 grounded them properly
against the Supervisor source — the gateway strips any client-supplied
`X-Remote-User-*` and re-injects the values of the authenticated ingress
session, so behind the peer check the header names the authenticated
user — and built authorization on top: dashboard access under ingress is
**admin-only**, with the username as the lookup key and the admin
decision coming from the Supervisor's authenticated `/auth/list`,
fail-closed. What survives from the original caution unchanged: the
header *value* alone is never authorization.

A **public site** (for users who expose the dashboard outside ingress)
follows the standalone rules below.

### 2. Standalone and Docker: localhost by default, never open unauthenticated

- Default binding is `127.0.0.1`.
- Binding any non-loopback address **requires** a password. That
  password means the operator: it is presented as
  `Authorization: Bearer` (a script), or exchanged once at
  `POST /auth/login` for a session cookie (a browser).
- If no password is configured, one is generated and printed to the
  log — the code-server pattern. It is generated per run and held in
  memory only; `MCUHOME_DASHBOARD_PASSWORD` keeps one across restarts.
- A loopback-only bind may run without a password: nothing but this
  machine can reach it, so the caller is the operator.

There is no configuration in which the dashboard listens on a network
interface without authentication, not even behind a warning. The
generated-password path is what keeps a fresh container usable in one
step while keeping it closed. (ADR 0014 later added failed-login
throttling with lockout and backoff to both password paths.)

### 3. WebSocket origin check, CSRF tokens on state-changing REST

Every WebSocket upgrade is origin-checked, before the socket exists. The
REST surface is small by ADR 0004 — the login exchange, health, and the
artifact download ADR 0013 added — so CSRF protection is a small and
complete addition rather than a policy applied across a large API. It
binds to cookie-authenticated sessions, because a session cookie is what
a browser attaches on its own and a bearer token is not.

### 4. Secrets are visible in the browser, and we say so

Ingress can be plain HTTP inside a LAN, and the editor legitimately
shows the user their own `secrets.yaml`. Both are accepted: the
dashboard's session is exactly as sensitive as the configuration tree it
edits. Commissioning codes still travel only when a user asked for
them — `device/commissioning`, never a list response. The project's
standing rule on Thread datasets still binds — resolved into a build,
never persisted into dashboard state, never written to a log.

### 5. One sentence goes into SECURITY.md

> An authenticated dashboard session is equivalent to shell access on
> the build server and holds the firmware signing key.

Written in those words, in the scope section, next to the surfaces it
describes — where it stands today.

## Consequences

- No user database, no session store, no password reset, no roles.
  **Multi-user is explicitly out of v0.1** — in the app deployment Home
  Assistant is the authenticator, and in the standalone deployment there
  is one password. Sessions live in memory in one process; a restart
  logs everybody out. ADR 0014's admin flag does not reopen this: it is
  one boolean derived from Home Assistant's own roles, so "no user
  database, no roles" still holds.
- TLS and stronger authentication for an internet-exposed standalone
  deployment come from a reverse proxy in front. The dashboard does not
  terminate TLS and does not pretend to.
- The two-site split has to be real in the code — one binding that
  trusts its peer and one that does not — and a misconfiguration that
  exposes the trusting site is the failure mode worth a test. As built,
  the split is a `TrustMode` carried by the aiohttp application object:
  two applications from two factory calls, the mode read from the
  application and never from the request; `backend/tests/python/test_auth.py`
  is the test, and it is mostly refusals.
- Related standing decisions: ADR 0003, ADR 0004 (the small REST
  surface), ADR 0007, ADR 0008 (the key), ADR 0012 (which carries
  forward ADR 0006's transport and its bearer token, whose blast radius
  is the same as a session's), ADR 0013 (what a session can start),
  ADR 0014 (admin-only ingress, throttling, concurrency limits).
