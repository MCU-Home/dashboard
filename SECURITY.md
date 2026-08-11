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
