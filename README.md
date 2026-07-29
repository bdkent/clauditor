# Clauditor

[![Install from JetBrains Marketplace](https://img.shields.io/badge/JetBrains_Marketplace-Clauditor-blue?logo=jetbrains)](https://plugins.jetbrains.com/plugin/30981-clauditor)

A JetBrains IDE plugin for managing [Claude Code](https://docs.anthropic.com/en/docs/claude-code) sessions. Browse, resume, fork, and monitor Claude sessions directly from your editor — with built-in git worktree support.

> Requires IntelliJ 2024.3+ and Claude CLI installed.

![Clauditor overview](docs/screenshots/overview.png)

## Features

### Session Management

Sessions open as virtual editor tabs — side by side with your source files, not hidden in a terminal panel. Each tab is a fully interactive Claude terminal with its own toolbar, git status, and context bar.

<!-- ![Session tabs alongside code](docs/screenshots/session-tabs.png) -->

- **Resume** any previous session with double-click
- **Fork** a session to branch off from a conversation
- **Rename** and **delete** sessions from the UI
- **Purge** old sessions in bulk — enter a number of days and see a live count before deleting
- **Restore** open sessions across IDE restarts
- **Drag and drop** files (images, code, etc.) onto a session tab to insert their paths
- Split, drag, and arrange session tabs just like any editor tab

<!-- ![Session list](docs/screenshots/sessions.png) -->

### Worktree Sessions

Run isolated Claude sessions in git worktrees. Each worktree gets its own branch and working directory, so Claude can make changes without touching your main tree.

- Create worktrees from the Sessions panel — dialog shows base-branch state (Claude bases new worktrees on `origin/<default>`, so divergence and being on a non-default branch is surfaced upfront)
- Dedicated toolbar with **commit**, **create PR**, **rebase**, and **merge** controls
- Open worktree directory in a separate IDE window, in a terminal tab in this IDE, or in a file manager
- Branch status: ahead/behind tracking vs. your project branch
- **Git column** — the Worktrees tab shows each worktree's uncommitted file count (`✎3`) and commits not yet in your project branch (`↑2`), so work you forgot to commit or merge is visible without opening the tab; sort by it to bring the neglected worktrees to the top
- **Orphaned worktrees** — the Worktrees tab lists `.claude/worktrees/` directories that have no session, so you can start a session in one or delete a stale worktree (removes the git worktree, and its branch if already merged) right from the list
- **Show Changes** — review a worktree's diff in a tree + inline-diff browser: its commits vs the base branch (Committed) and its uncommitted working-tree changes (Uncommitted, editable), opened from the Worktrees tab or the session toolbar

<!-- ![Worktree toolbar](docs/screenshots/worktree-toolbar.png) -->

### Git Toolbar

Every session editor shows the git state of its working directory:

- Current branch name and file change count
- **Session-aware diffing** — distinguishes files changed by Claude from files with mixed changes
- One-click commit of session-only changes

<!-- ![Git toolbar](docs/screenshots/git-toolbar.png) -->

### External Session Detection

Clauditor detects Claude sessions running outside the IDE (iTerm, VS Code, other terminals) and shows them in the session list with a distinct indicator.

- Sessions open externally show the **↗** icon and grayed-out text
- If a persisted tab's session is open externally when the IDE restarts, the tab shows an info panel instead of conflicting with the external terminal
- A **Resume** button auto-enables when the external session closes

<!-- ![External session indicator](docs/screenshots/external-session.png) -->

### Live Status Monitoring

Real-time visibility into what Claude is doing:

- **Tab indicators** — see at a glance which sessions are thinking, waiting for permission, idle, or unresponsive (⊘)
- **Unresponsive detection** — automatically detects frozen CLI sessions when input gets no echo, highlights the reconnect button
- **Context usage** — progress bar showing how much of Claude's context window is consumed
- **Model info** — displays which model the session is using
- **Effort & thinking** — tab status bar shows the current `/effort` level and whether extended thinking is enabled (requires CLI 2.1.119+)

<!-- ![Session editor with status](docs/screenshots/editor-status.png) -->

### Rate Limits & Auth

The Status tool window tracks your API usage:

- 5-hour and 7-day rate limit bars (green/yellow/red) on subscription plans (Pro/Max)
- On non-subscription plans (e.g. Enterprise), where 5h/7d windows don't apply, the bars are replaced by a link to your usage & budget on claude.ai
- Logged-in account and subscription type
- Anthropic system status from [status.claude.com](https://status.claude.com)
- Toggleable vertical/horizontal layout

<!-- ![Status panel](docs/screenshots/status-panel.png) -->

### Context Browser

The Context tool window has two tabs.

**Context** — browse and insert Claude's configuration:

- **Rules** — project and personal `.claude/rules/` files
- **Agents** — custom agent definitions
- **Skills** — slash command skills with metadata
- **Memory** — auto-memory files from `~/.claude/projects/`
- Double-click to open in editor, or insert directly into a running session

**Scratchpad** — browse the working files Claude writes during a session.

Claude Code gives each session a private scratchpad directory for temporary files (probes,
logs, captured output) and is told to use it in place of `/tmp`. It lives under the system
temp root, keyed by session id, so finding a file Claude mentioned is otherwise tedious.

- Grouped by session, with the focused session's tab first and expanded
- Files listed newest-first with size and modification time
- Sessions with an empty scratchpad are hidden; a scratchpad whose session was deleted still appears
- Right-click to open, copy the path, insert the path into a running session, or reveal in your file manager
- Only scans while the tab is on screen

<!-- ![Context panel](docs/screenshots/context-panel.png) -->

### Settings Panel

Configure the plugin under **Settings > Tools > Clauditor**:

- **Unresponsive timeout** — tune how long before a session is flagged as frozen
- **Claude binary path** — manual override if auto-detection fails
- **Default session arguments** — extra CLI flags for every new session
- **Status line refresh interval** — re-run the status line command every N seconds (requires CLI 2.1.97+)
- **Branch status refresh** — re-query git for the worktree/git toolbars every N seconds (0 disables; tab focus and Claude status events still trigger refreshes)
- **Background pool max threads** — cap on Clauditor's bounded thread pool (default 16; raise if the IDE log warns the pool is saturated)
- **Environment variables** — toggles for `COLORTERM=truecolor`, telemetry, update check, prompt caching, plus free-form custom vars
- Links to [Claude Code environment variable docs](https://docs.anthropic.com/en/docs/claude-code/settings#environment-variables)

### Message History

A collapsible sidebar in each session editor lists every user message in the conversation. Click a message to scroll the terminal to that point.

<!-- ![Message history](docs/screenshots/message-history.png) -->

## Requirements

- **IntelliJ IDEA** 2024.3 or later (Community or Ultimate)
- **Claude CLI** installed and in your `PATH` ([install guide](https://docs.anthropic.com/en/docs/claude-code/getting-started))
- Authenticated via `claude login`

## Installation

### From source

```bash
git clone https://github.com/bdkent/clauditor.git
cd clauditor
./gradlew buildPlugin
```

The built plugin ZIP will be in `build/distributions/`. Install it via **Settings → Plugins → ⚙ → Install Plugin from Disk**.

### Development

```bash
./gradlew runIde
```

This launches a sandboxed IDE instance with the plugin loaded.

## Usage

1. Open a project that has Claude Code sessions (any project where you've run `claude`)
2. Open the **Sessions** tool window (right sidebar)
3. Double-click a session to resume it, or click **+** to start a new one
4. Use the **Worktrees** tab to run isolated sessions on separate branches

## Architecture

```
src/main/kotlin/com/clauditor/
├── editor/          Session editor, virtual files, tab titles, icons
├── services/        Session loading, terminal PTY, status polling, context scanning
├── settings/        Plugin settings panel and persistent configuration
├── toolwindow/      Sessions list, status bar, context browser, message history
├── terminal/        PTY output filtering, activity detection
├── model/           Data classes (sessions, status, context items)
└── util/            Path encoding, process detection, custom UI components
```

The plugin embeds Claude CLI as a PTY process, injects lightweight hooks to capture status and tool-use events, and polls status files to keep the UI in sync — no modifications to Claude's own configuration.

## License

[MIT](LICENSE)
