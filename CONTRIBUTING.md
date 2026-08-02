# Contributing to the MCUHome Dashboard

Thanks for considering a contribution! The dashboard is in its design
phase — the most valuable contributions right now are discussion and review
of the [architecture decision records](docs/adr/) and participation in
[GitHub Discussions](https://github.com/mcu-home/dashboard/discussions).

## Development environment

Backend (Python ≥ 3.11):

```sh
cd backend
python3 -m venv .venv && . .venv/bin/activate
pip install -e .
```

Frontend: TypeScript SPA — the framework and build tooling are selected in
the design phase (see [ADR 0002](docs/adr/0002-python-backend-typescript-frontend.md));
`frontend/` is a placeholder until then.

Install the lint hooks once per clone:

```sh
pre-commit install --install-hooks
pre-commit install --hook-type commit-msg
```

## Coding standards

- **Python:** `ruff` (lint + format), settings in `backend/pyproject.toml`.
- **TypeScript:** tooling to be defined with the framework decision.
- **Licensing:** every new file needs SPDX headers (a
  `SPDX-FileCopyrightText` line and an `Apache-2.0` license identifier —
  copy them from any existing file).

## Commit and PR rules

Identical to the firmware repository:

- **Conventional Commits** (`feat:`, `fix:`, `docs:`, `chore:`, …).
- **DCO sign-off** on every commit: `git commit -s`.
- Focused PRs; non-trivial design decisions get an ADR in
  [docs/adr/](docs/adr/).

## Reporting issues

Use the [issue forms](https://github.com/mcu-home/dashboard/issues/new/choose).
Security vulnerabilities go through [SECURITY.md](SECURITY.md), never
public issues.

## Code of Conduct

This project follows the [Contributor Covenant 3.0](CODE_OF_CONDUCT.md).
