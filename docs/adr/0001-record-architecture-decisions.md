# 0001 — Record architecture decisions

- Status: accepted
- Date: 2026-08-02

## Context

Same rationale as
[mcu-home/mcuhome-workbench ADR 0001](https://github.com/mcu-home/mcuhome-workbench/blob/main/docs/adr/0001-record-architecture-decisions.md):
decisions and their rationale must survive chat logs, PR threads and AI
session boundaries.

## Decision

Every non-trivial dashboard design decision is recorded as a numbered ADR
in `docs/adr/` (MADR style: Context / Decision / Consequences, with
status). Project-wide decisions stay in the firmware repository.

## Consequences

- Clear split: product-wide rationale in the flagship repo, UI/backend
  specifics here.
- PRs that change design direction must include or update an ADR.
