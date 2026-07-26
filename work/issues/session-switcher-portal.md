# Claude's in-terminal session switcher ("portal") vs. our one-tab-one-session model

**Status:** deferred, not fixed. Behaviour judged acceptable in practice (2026-07-25).
The detection probe has been removed from the polling loop; the parsed field it needed is kept.

## The problem

Claude Code's terminal has a session switcher (left arrow → agents/sessions view). Selecting a
different session re-points **the same terminal** at it, in place — session A's terminal is now
showing session B. Users call this being "portalled" to B.

Clauditor binds a tab to one session ID when it opens. After a portal, the terminal shows B
while the tab still describes A: title and tooltip, the git and worktree toolbars, the context /
status bar, message history, open-session persistence, and the Sessions-list open/external
indicator. If B was renamed, the terminal shows the new name and the tab shows the old one.

There is no supported way to disable the switcher.

## Why it's deferred

The visible symptom is a stale label, not lost work or a broken terminal — the terminal itself
behaves correctly throughout. Weighed against the size of the fix (below), that isn't worth
blocking a release for.

Note the separate, real bug that shared this root cause **is** fixed: left-arrow then Esc used to
make the embedded terminal show up as an "external tab", because Claude's own daemon/background
processes (`daemon`, `bg-pty-host`, `bg-spare`) were being counted as external sessions. See
`ClaudeProcessDetector.getSelfExcludedPids` and the `getStatus` precedence in
`ClaudeSessionsToolWindowFactory` (commit a7c231f).

## What exists today

`ClaudeStatus.reportedSessionId` (`model/ClaudeStatus.kt:23`, populated in
`ClaudeStatusService.parseStatus`) carries the `session_id` the statusline itself reports — i.e.
which session the terminal is *currently* showing. Nothing consumes it yet.

It is deliberately kept: it is the signal any future fix would build on, and parsing it is free.

**Removed:** the `PORTAL DETECTED` probe log that sat in `ClaudeStatusService.poll()`. It fired
on every 500 ms tick rather than once per switch, because the divergence check sat outside the
"status changed" guard — so a single portal produced two log lines per second, indefinitely.
Diagnostic scaffolding, never meant to ship.

## If we pick this up again

The unanswered question is still the gate: **does our status file keep updating after a portal?**
The status file path is baked into the PTY's environment at spawn, and Claude serves sessions via
background PTY-host processes. If a portal hands the terminal to a different backend process,
that process never received our `--settings` wrapper, our file goes stale, and
`reportedSessionId` never flips — which would make the whole approach below unworkable.

Answer that first (log `reportedSessionId` once per change, portal A→B, see whether it flips and
keeps updating). Only if it does is the rebind worth writing:

- Extract the mutation body of `setupNewSessionLinking` (`editor/ClaudeSessionEditor.kt`) into a
  shared `applyRebind(newId, …)`, called by both first-message linking and the portal trigger.
- Migration checklist: `file.sessionId` / `baseName` / `workingDir` / `isWorktreeSession`;
  `ClaudeStatusService` stop+start monitoring **reusing the spawn-time temp files**;
  `OpenSessionsPersistence` remove-old **and** add-new (today's linking only adds, which leaks
  the old id and restores a phantom tab); reset per-session badge state (thinking, notify,
  unresponsive, model, context) so B doesn't inherit A's; `refreshTabTitle(force = true)`; reset
  `MessageHistoryPanel`'s tail state; re-run the git and worktree toolbar refreshes; refresh both
  `SessionListPanel`s so the row glyphs move.
- Accepted limits: the PTY's argv, OS-level cwd and status/notify env are fixed at spawn and
  cannot follow; the VFS `sessionKey` stays A's (harmless — uniqueness holds and everything keys
  on the mutable `sessionId`). Two tabs legitimately driving the same session is allowed.

The fuller version of this plan was at `/Users/me/.claude/plans/giggly-giggling-dahl.md`; treat
anything there as stale relative to this file.
