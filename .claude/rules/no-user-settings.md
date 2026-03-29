No solution may read, modify, or write to the user's Claude settings files:
- `~/.claude/settings.json`
- `~/.claude/settings.local.json`
- `<project>/.claude/settings.json`
- `<project>/.claude/settings.local.json`

These files belong to the user. Clauditor must work entirely through its own mechanisms (`--settings` override, env vars, wrapper scripts, PTY monitoring).
