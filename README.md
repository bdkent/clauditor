# Clauditor

A JetBrains IDE plugin for managing [Claude Code](https://docs.anthropic.com/en/docs/claude-code) sessions. Browse, resume, fork, and monitor Claude sessions directly from your editor — with built-in git worktree support.

> **Status**: Early development (v0.1.0). Requires IntelliJ 2024.2+ and Claude CLI installed.

![Clauditor overview](docs/screenshots/overview.png)

## Features

### Session Management

Sessions open as virtual editor tabs — side by side with your source files, not hidden in a terminal panel. Each tab is a fully interactive Claude terminal with its own toolbar, git status, and context bar.

<!-- ![Session tabs alongside code](docs/screenshots/session-tabs.png) -->

- **Resume** any previous session with double-click
- **Fork** a session to branch off from a conversation
- **Rename** and **delete** sessions from the UI
- **Restore** open sessions across IDE restarts
- Split, drag, and arrange session tabs just like any editor tab

<!-- ![Session list](docs/screenshots/sessions.png) -->

### Worktree Sessions

Run isolated Claude sessions in git worktrees. Each worktree gets its own branch and working directory, so Claude can make changes without touching your main tree.

- Create worktrees from the Sessions panel
- Dedicated toolbar with **commit**, **create PR**, **rebase**, and **merge** controls
- Open worktree directory in a separate IDE window or file manager
- Branch status: ahead/behind tracking vs. your project branch

<!-- ![Worktree toolbar](docs/screenshots/worktree-toolbar.png) -->

### Git Toolbar

Every session editor shows the git state of its working directory:

- Current branch name and file change count
- **Session-aware diffing** — distinguishes files changed by Claude from files with mixed changes
- One-click commit of session-only changes

<!-- ![Git toolbar](docs/screenshots/git-toolbar.png) -->

### Live Status Monitoring

Real-time visibility into what Claude is doing:

- **Tab indicators** — see at a glance which sessions are thinking, waiting for permission, or idle
- **Context usage** — progress bar showing how much of Claude's context window is consumed
- **Model info** — displays which model the session is using

<!-- ![Session editor with status](docs/screenshots/editor-status.png) -->

### Rate Limits & Auth

The Status tool window tracks your API usage:

- 5-hour and 7-day rate limit bars (green/yellow/red)
- Logged-in account and subscription type
- Anthropic system status from [status.claude.com](https://status.claude.com)
- Toggleable vertical/horizontal layout

<!-- ![Status panel](docs/screenshots/status-panel.png) -->

### Context Browser

Browse and insert Claude's configuration from the Context tool window:

- **Rules** — project and personal `.claude/rules/` files
- **Agents** — custom agent definitions
- **Skills** — slash command skills with metadata
- Double-click to open in editor, or insert directly into a running session

<!-- ![Context panel](docs/screenshots/context-panel.png) -->

### Message History

A collapsible sidebar in each session editor lists every user message in the conversation. Click a message to scroll the terminal to that point.

<!-- ![Message history](docs/screenshots/message-history.png) -->

## Requirements

- **IntelliJ IDEA** 2024.2 or later (Community or Ultimate)
- **Claude CLI** installed and in your `PATH` ([install guide](https://docs.anthropic.com/en/docs/claude-code/getting-started))
- Authenticated via `claude login`

## Installation

### From source

```bash
git clone https://github.com/anthropics/clauditor.git
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
├── toolwindow/      Sessions list, status bar, context browser, message history
├── terminal/        PTY output filtering, activity detection
├── model/           Data classes (sessions, status, context items)
└── util/            Path encoding, process detection, custom UI components
```

The plugin embeds Claude CLI as a PTY process, injects lightweight hooks to capture status and tool-use events, and polls status files to keep the UI in sync — no modifications to Claude's own configuration.

## License

[MIT](LICENSE)
