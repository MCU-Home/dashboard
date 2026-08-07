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

Firmware and builder vulnerabilities belong to
[mcu-home/mcuhome](https://github.com/mcu-home/mcuhome/security).
