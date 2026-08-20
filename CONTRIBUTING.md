# Contributing to the MCUHome Dashboard

Thanks for considering a contribution! The dashboard is in its design
phase — the most valuable contributions right now are discussion and review
of the [architecture decision records](docs/adr/) and participation in
[GitHub Discussions](https://github.com/mcu-home/mcuhome-ui/discussions).

## Development environment

Backend (Python ≥ 3.13):

```sh
cd backend
python3 -m venv .venv && . .venv/bin/activate
pip install -e .
```

Frontend: TypeScript SPA — Lit 3, `@home-assistant/webawesome`,
CodeMirror 6 and Vite (see
[ADR 0005](docs/adr/0005-lit-webawesome-codemirror-frontend.md));
`frontend/` is a placeholder until the scaffolding lands.

Install the lint hooks once per clone:

```sh
pre-commit install --install-hooks
pre-commit install --hook-type commit-msg
```

## Coding standards

- **Python:** `ruff` (lint + format), settings in `backend/pyproject.toml`.
- **TypeScript:** eslint/prettier, configured with the frontend
  scaffolding (ADR 0005).
- **Licensing:** every new file needs SPDX headers (a
  `SPDX-FileCopyrightText` line and an `Apache-2.0` license identifier —
  copy them from any existing file).

## Commit and PR rules

Identical to the firmware repository:

- **Conventional Commits** (`feat:`, `fix:`, `docs:`, `chore:`, …).
- **DCO sign-off** on every commit: `git commit -s`.
- Focused PRs; non-trivial design decisions get an ADR draft in
  [docs/adr/draft/](docs/adr/draft/) — the final ADR is written from
  the real result once the component is done
  ([docs/adr/README.md](docs/adr/README.md)).

## Reporting issues

Use the [issue forms](https://github.com/mcu-home/mcuhome-ui/issues/new/choose).
Security vulnerabilities go through [SECURITY.md](SECURITY.md), never
public issues.

## Code of Conduct

This project follows the [Contributor Covenant 3.0](CODE_OF_CONDUCT.md).
