# 0014 — Ingress is admin-only; login throttling and concurrency limits

- Status: accepted
- Date: 2026-08-11

## Context

ADR 0009 split the dashboard into two sites: a **public** site that
authenticates with one password, and an **ingress** site that trusts the
Supervisor gateway and adds no authentication of its own, because Home
Assistant already authenticated the user. That was written when "the user"
meant "an operator": ADR 0009 decision 1 used `X-Remote-User-*` only to
*display* who was logged in, and said in as many words that it "is used to
show who is logged in, and for nothing else."

But a Home Assistant instance has **non-admin users**, and an ingress
panel can be shown to them. Today every peer that reaches the ingress
site is granted full trust with no per-command authorization, so any
non-admin Home Assistant user who can open the panel can do everything an
operator can: read a device's Matter **commissioning passcode**
(`device/commissioning`), download passcode-bearing **build artifacts**,
edit device configurations, and start builds — which on the default
method runs a toolchain on the host. An authenticated dashboard session
is, in SECURITY.md's own words, "equivalent to shell access on the build
server and holds the firmware signing key." That is not a capability a
normal household member should get by virtue of having a Home Assistant
login.

**Product-owner decision:** in the Home Assistant context, dashboard
access is **admin-only**. A normal non-admin user must not create or edit
devices, must not start builds, and must not be handed commissioning
codes.

Two smaller, related weaknesses were found in the same review and are
folded in here because they share the security model:

1. The public site's password paths (`POST /auth/login` and the bearer
   token) have **no failed-attempt limit** — a script can guess the
   password as fast as the network allows.
2. A single authenticated WebSocket connection can spawn **unbounded
   concurrent commands**, and the CPU-bound `device/validate` runs on the
   process-wide thread pool — so one client firing validations can starve
   every other socket.

### How Home Assistant forwards the user, and where admin status lives

Researched against the Supervisor source and the add-on ingress docs:

- The Supervisor forwards `X-Remote-User-Id`, `X-Remote-User-Name`
  (the login **username**) and `X-Remote-User-Display-Name`. It forwards
  **no admin flag** — none exists in the ingress header set.
- Crucially, the Supervisor **strips any client-supplied** `X-Remote-User-*`
  header and re-injects these from the authenticated ingress session
  (`_init_header` lists all three in its skip tuple). So on a request that
  passed the peer check, `X-Remote-User-Name` is the *authenticated*
  username and a non-admin cannot forge a colleague's.
- Admin status is fetched, not received: the Supervisor exposes
  `GET /auth/list` (authenticated with the add-on's `SUPERVISOR_TOKEN`),
  which returns each user's `username`, `is_owner` and `group_ids`. A user
  is an administrator when `is_owner` is true or `group_ids` contains
  Home Assistant's built-in admin group `system-admin`.

The trustworthy signal is therefore a **two-step** one: the peer check
authenticates the header (identity), and the add-on's own authenticated
call to the Supervisor turns that username into the admin decision. The
header alone is never the decision — a client could set it, but only the
Supervisor's stripped-and-reinjected value survives the peer, and the
authorization comes from the Supervisor's API, not from any header value.

## Decision

### 1. The ingress site derives and enforces admin status

`Identity` carries an `is_admin` flag. On the **public** site it is `true`
by construction: `open` (a loopback deployment) is the machine's owner and
`password` is ADR 0009's operator. On the **ingress** site it is resolved,
in the auth middleware, from the Supervisor:

- the trusted `X-Remote-User-Name` names the user;
- `mcuhome.ui.admin.SupervisorAdminOracle` asks
  `GET /auth/list` (over `SUPERVISOR_TOKEN`, cached ~30 s) and answers
  whether that username is an owner or in `system-admin`.

It **fails closed**: no token configured, the Supervisor unreachable, or
the user not in the roster all resolve to *not an admin*. A deployment
with no `SUPERVISOR_TOKEN` grants no ingress user the admin-only verbs.

### 2. What is gated, and the line drawn

The line: **mutating the tree or handing out a secret is admin-only;
reading the tree is open to any dashboard-reaching user.** The PO intent
is "no non-admin may create/edit devices, and commissioning codes only on
explicit user action" — that reserves *writes and secrets*, not *reads*,
so gating everything would exceed the intent and make the panel useless
to the household members who may legitimately look.

Admin-only (a typed `unauthorized` refusal on ingress, checked first,
before the named resource is even looked up):

- `device/save` — editing a device
- `device/commissioning` — the passcode-bearing QR/manual codes
- `build/start`, `build/cancel` — running a toolchain, producing artifacts
- `GET /api/builds/{build}/artifacts/{path}` — artifacts carry the
  resolved model with its pairing tuple; a non-admin gets `403` before
  the build id is examined, which leaks nothing about which builds exist

Open to any ingress user (read-only, no secrets):

- `server/info`, `ping`
- `device/list`, `device/get` (the raw file; no `!secret` is resolved),
  `device/validate` (diagnostics only)
- `build/status`, `build/log`, `build/subscribe`, `config/subscribe`,
  `subscribe_events`, `unsubscribe_events`

The refusal is a typed error the existing frontend renders as a message,
never a crash; `is_admin` is also reported in `server/info` so the UI can
hide admin-only affordances before the click.

### 3. Failed-login throttling on the public site

Both public password paths feed one `LoginThrottle`, keyed on source
address with a process-wide backstop:

- below a small threshold, honest mistakes are free;
- past it, a temporary lockout with **exponential backoff**, answered with
  `429` and a `Retry-After`;
- a correct password clears the source's count;
- a global failure count within a window covers the distributed guess a
  per-source counter cannot see (and a whole site behind one reverse-proxy
  hop, since `X-Forwarded-For` is client-settable and not trusted).

The ingress site never consults it — its control is the peer check, and
throttling the Supervisor gateway would throttle every user at once.

### 4. Concurrency limits

- A **per-connection in-flight cap**: the WebSocket reader waits for a
  slot before reading the next frame, so one socket applies backpressure
  to itself rather than piling up unbounded concurrent work. It is never
  a refusal — a normal UI never approaches the cap.
- A **process-wide gate** on the CPU-bound builder calls
  (`device/validate`, `device/commissioning`), smaller than the default
  thread pool, so those calls cannot exhaust the pool the rest of the
  app's file I/O shares and stall every socket.

## Consequences

- The App packaging (in the packaging repo, not here) must pass
  `SUPERVISOR_TOKEN` to the container and grant the add-on access to the
  Supervisor auth API (`auth_api: true`, with a token role able to call
  `/auth/list`). Until it does, the ingress site is **read-only for
  everyone** — the safe failure, but the reason a fresh App shows no
  build button is a missing token, not a bug.
- Admin status is fixed for the life of a WebSocket connection (resolved
  at upgrade) and cached ~30 s for REST; revoking a user's admin rights
  takes effect on their next connection or within the cache window.
- No roles beyond admin/non-admin, and none on the public site: ADR 0009's
  "no user database, no roles" still holds. This is one boolean derived
  from Home Assistant's own roles, not a second authorization system.
- Matching on `X-Remote-User-Name` (username) rather than
  `X-Remote-User-Id` is forced by `/auth/list` returning username and not
  id; both headers are equally trustworthy (same Supervisor injection),
  so this is a shape constraint, not a weakening.
- SECURITY.md gains the admin-only statement next to the ingress surface
  it describes.
- Related: ADR 0007 (credential exposure), ADR 0008 (signing key), ADR
  0009 (the two sites and the password), ADR 0013 (builds and artifacts).
