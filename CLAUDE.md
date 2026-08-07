# CLAUDE.md

@AGENTS.md

## Claude Code specifics

- Project subagents live in `.claude/agents/` (`dashboard-code-reviewer`,
  `dashboard-test-runner`).
- Shared project settings: `.claude/settings.json`. Personal overrides go
  to `.claude/settings.local.json` (gitignored — never commit).
- `pre-commit` runs ruff over the backend and prettier/eslint over
  `frontend/`. The frontend hooks call the repository's own tools through
  `pnpm exec`, so `pnpm install` must have run in `frontend/` before they
  work.
