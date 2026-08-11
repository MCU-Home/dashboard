# Security Policy

## Supported versions

The MCUHome Dashboard is pre-alpha and has no released versions yet. This
policy takes full effect with the first release; until then, reports about
the scaffold are still welcome.

## Reporting a vulnerability

**Do not open public issues for security vulnerabilities.**

Use GitHub's private vulnerability reporting:
**Security → Report a vulnerability** on this repository
([direct link](https://github.com/mcu-home/dashboard/security/advisories/new)).

We aim to acknowledge reports within **3 business days**.

## Scope

Particularly relevant attack surfaces for this project:

- Stored device configurations (may contain WiFi credentials, Thread
  network keys, Matter setup codes).
- The build orchestration path (the dashboard drives the firmware
  builder — a supply chain for every device it flashes).
- Authentication/session handling of the web interface, especially when
  exposed as a Home Assistant App (ingress).

**An authenticated dashboard session is equivalent to shell access on
the build server and holds the firmware signing key.** Treat access to
the dashboard, and to any build server it is paired with, accordingly.

**In a Home Assistant deployment the dashboard is admin-only** (ADR
0014). The ingress site derives the Home Assistant user's admin status
from the Supervisor — the peer check authenticates
`X-Remote-User-Name`, and the Supervisor's authenticated `/auth/list`
turns that username into the admin decision (never a client-settable
header). Device edits, `device/commissioning` (which returns the Matter
passcode), the build verbs and the artifact download route are refused
for non-admin users; read-only views stay open. The check **fails
closed**: an ingress user whose admin status cannot be resolved is
treated as non-admin. The public (password) site is unchanged — its one
password already implies an operator. The public password paths are
rate-limited with per-source lockout and a global backstop.

Since ADR 0013 a session can also **start a build**, which on the
default build method means starting a container on the dashboard's own
host. That is not a new trust boundary — it is the boundary ADR 0009
already draws around `/ws` — but it is a sharper consequence of crossing
it, and it is one more reason the artifact endpoint requires an identity
by the same rule as `/ws`: the files behind it are firmware signed with
that installation's key. That endpoint serves **only the artifacts a
build record declares**, never the build directory: a build method
leaves its build context there, and that context holds the resolved
device model with its commissioning credentials in it.

Firmware and builder vulnerabilities belong to
[mcu-home/mcuhome](https://github.com/mcu-home/mcuhome/security).
