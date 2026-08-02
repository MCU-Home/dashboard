# CLAUDE.md

@AGENTS.md

## Claude Code specifics

- Project subagents live in `.claude/agents/` (`dashboard-code-reviewer`,
  `dashboard-test-runner`).
- Shared project settings: `.claude/settings.json`. Personal overrides go
  to `.claude/settings.local.json` (gitignored — never commit).
- Formatting hooks are added together with the frontend framework decision
  (ADR 0002); until then run `ruff format` via pre-commit.
