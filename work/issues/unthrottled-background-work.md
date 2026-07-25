# Inventory: unthrottled / un-gated background work

> **Status (2026-07-25):** all seven items are **fixed** (see the notes inline). A further
> EDT-blocking finding surfaced while fixing #2 and is recorded there. Remaining known gap:
> the first `getSessions()` of a session still parses every transcript once, and it can be
> called from the EDT during tab restore — see "Residual" at the end.

All costs below are **measured on this machine** (2026-07-25), against this project:
transcripts in `~/.claude/projects/-Users-me-code-clauditor/` total **43 MB**, the largest
single session being **42 MB / 15,564 lines**.

The systemic problem: **three separate places full-parse the entire session transcript on a
timer.** None checks mtime, none caches, none gates on visibility. Plugin cost therefore scales
with `transcript size × open tabs × time` — long-running sessions get progressively slower,
which is exactly backwards.

---

## Tier 1 — transcript re-parsing (severe)

### 1. `MessageHistoryPanel.loadMessages` — every 3 s, per open tab, no gates
`toolwindow/MessageHistoryPanel.kt:83-89` (alarm, 3000 ms) → `loadMessages()` at `:91`
reads and Gson-parses **every line of the whole transcript**.

- No mtime check, no visibility gate, no single-flight.
- `lastMessageCount` (`:118`) only suppresses the *UI update* — the full read happens regardless.
- Measured: read + per-line JSON parse of the 42 MB transcript ≈ **0.24 s in Python**; Gson is
  typically slower still. That is ~0.3-0.8 s of CPU **every 3 s, per open tab, forever**.
- **Worst offender in the codebase.** One open tab on a long session pegs 10-25% of a core
  doing nothing but re-reading a file that appended one line.

**FIXED.** Now tail-reads via `util/JsonlTailReader`: only bytes appended since the last pass
are parsed, and the tick is skipped entirely while the sidebar is collapsed (with an immediate
catch-up when it reopens). An idle pass is now one `Files.size()` call. Verified against the
real 42 MB transcript: identical message count to a full parse at chunk sizes that force
mid-line splits, correct handling of a line cut mid-write, zero work on an idle pass, and a
clean resync when the file is truncated/rewritten.

### 2. `ClaudeSessionService.loadSessions` — on every `.jsonl` VFS event + 5 s mtime poll
`services/ClaudeSessionService.kt:201`, parsing at `:240` (`parseJsonlSession`).

- Full-parses **every** transcript in the project dir **and every worktree project dir** — 43 MB
  here — to produce a list of names/counts/timestamps.
- Triggered by the VFS listener (`:120-135`) on *any* `.jsonl` change, i.e. **every message
  Claude writes**, plus the 5 s poll whenever mtime moved. No debounce, no throttle.
- The file that triggers the re-parse is the same file that makes it expensive.

**FIXED.** Each transcript now has a `SessionAccumulator` (tail reader + running totals);
every field the list needs folds cleanly over appended lines, so a pass parses only new bytes.
Verified differentially against 6 real transcripts (including the 44 MB one) split into 3
incremental passes: message count, first prompt, custom title, git branch and last timestamp
all identical to a full parse. Accumulators are pruned when their file disappears.

**Also fixed — EDT blocking (found while fixing this).** `BulkFileListener.after` is delivered
in a write action **on the EDT**, and `refresh()` did the whole scan *plus* the `ps`-based
external-session sweep inline — so every transcript write blocked the UI thread on both. It now
runs on `ClauditorExecutor`, with overlapping requests coalescing to exactly one trailing pass
instead of one pass per event.

**Also removed:** `readCustomTitle` was dead code (never called) and the last whole-file scan
in the service.

### 3. `getSessionFileOperations()` — every 10 s, per open tab
`editor/ClaudeSessionEditor.kt:1050`, reached from the git toolbar's `refresh()` (`:532`) on the
`wireToolbarRefresh` tick. Full transcript parse again, same file, same cost — **and it ran
twice per tick**, once for `getSessionTouchedFiles()` and once inside `getPureSessionFiles()`.

**FIXED.** Now tail-reads into a cached op list. Retaining the ops costs ~2% of transcript
bytes (measured: 0.86 MB of op text for a 44 MB transcript, 718 ops), so caching them is far
cheaper than re-reading the file. The tick now parses once and passes the result to both
consumers; `getPureSessionFiles` takes the ops as a parameter instead of re-reading.

---

## Tier 2 — process spawns on a timer

### 4. `ClaudeProcessDetector.detectExternalSessions` — every 5 s + every `refresh()`
`util/ClaudeProcessDetector.kt:19`, called from `ClaudeSessionService.refreshExternalSessions`
(`:194`) — which runs both on the 5 s poll (`:181`) and inside every `refresh()` (`:109`).

- Spawns `ps -A -o pid,args -ww` — the **entire process table** (measured **47 ms**, 438 procs).
- Then spawns **one `kill -0` process per candidate session file** (`:88-97`) to test liveness.
- So ≥1 and realistically 1+N processes every 5 s, forever, per open project — plus again on
  every VFS-triggered refresh, unthrottled.

**FIXED.** `isProcessAlive` now uses `ProcessHandle.of(pid)` — no child process at all
(verified: live pid true, bogus pid false, killed pid false). The `ps` sweep is rate-limited to
one per 4 s (`EXTERNAL_SWEEP_MIN_MS`), so the 5 s poll and list refreshes can no longer stack
sweeps on top of each other.

### 5. `wireToolbarRefresh` — every 10 s, per open tab, **per toolbar**, no visibility gate
`editor/ClaudeSessionEditor.kt:960`; interval `branchStatusRefreshSeconds = 10`
(`settings/ClauditorSettings.kt:39`).

- A worktree tab wires it **twice**: `:583` (git toolbar) and `:909` (worktree toolbar).
- Per tick: git toolbar = 2 git processes + a **full transcript parse** (see #3); worktree
  toolbar = up to **7 git processes** (`WorktreeInspector.status`).
- Runs for every **open** tab, not just the visible one. Five open worktree tabs ≈ **45 git
  processes + 5 full transcript parses every 10 s**.

**FIXED.** The editor tracks `tabShowing` from a HierarchyListener, and the periodic tick is
skipped unless the tab is on screen. Only the visible tab polls; a background tab is refreshed
by the existing `selectionChanged` listener the moment it comes to the front, so nothing is
stale by the time it can be seen. Five open worktree tabs now cost what one does.

Not done: sharing a single worktree-status sweep across tabs. After the visibility gate only
one tab polls at a time, so the remaining duplication isn't worth a project-level service.

### 6. `ClaudeStatusBarPanel.schedulePoll` — every 60 s, no visibility gate
`toolwindow/ClaudeStatusBarPanel.kt:345-353`.

- Spawns `claude auth status` — a full Node CLI startup, measured **0.78 s** — and issues an
  **outbound HTTPS request to status.claude.com** (`:289`), every minute.
- Runs forever once the Status tool window has been opened, **even while hidden or collapsed**,
  per project window.
- Auth state and incident feeds change on the order of hours; a 60 s poll is ~60× more often
  than the data justifies.

**FIXED.** Split by cost: `updateRateLimits` (pure re-render of data the status service
already holds) stays on the 60 s tick, while `claude auth status` and the status.claude.com
fetch moved to a 15-minute floor (`EXPENSIVE_REFRESH_MS`) and only run while the panel is on
screen. Opening the tool window refreshes immediately if the data has aged out.

### 7. `refreshOrphans` — every `reloadData()`
`toolwindow/SessionListPanel.kt:460`. One `git worktree list --porcelain` (~20 ms) + a dir scan,
single-flight but unthrottled and un-gated; `reloadData` fires on every transcript write.
**The mildest item here** — two orders of magnitude below #1-#3, but the same missing gates.

**FIXED.** Now gated on the panel being visible plus a 15 s floor, with an immediate sweep when
the tab is reopened. Every sweep that follows a mutation (delete worktree, delete directory,
explicit Refresh) passes `force = true`, so the list still updates instantly after an action —
the throttle applies only to the reloadData-driven sweeps.

---

## Tier 3 — acceptable as-is

- **`ClaudeStatusService.poll`** (`services/ClaudeStatusService.kt:285-287`) — 500 ms, but only
  reads two small status files per monitored session, and stops itself when nothing is
  monitored. Frequent yet genuinely cheap.
- **`wireNewWorktreeDirPoll`** (`editor/ClaudeSessionEditor.kt:996`) — 750 ms, but **bounded to
  40 attempts (~30 s)** and self-terminating on success.
- **`WorktreeStatusCache`** (`toolwindow/WorktreeStatusCache.kt`) — visibility gate, 15 s
  throttle, single-flight, empty short-circuit.
- One-shot user-triggered work (fork, delete, Show Changes, dialogs).

---

## Rough current cost, one open tab on the 42 MB session

| Source | Rate | Parsed per minute |
|---|---|---|
| MessageHistoryPanel | 20 × / min | ~840 MB |
| `loadSessions` (VFS-driven, ~10 msg/min) | ~10 × / min | ~430 MB |
| git toolbar `getSessionFileOperations` | 6 × / min | ~250 MB |

**≈1.5 GB of JSON parsed per minute, per open tab**, to display data that changed by one line.
Process spawns on top: ~12 `ps`/`kill` + ~54 git + 1 `claude auth status` per minute.

## Residual (known, not fixed)

- **First load is still a full parse.** `getSessions()` lazily calls `loadSessions()` when the
  cache is cold, and `restoreOpenSessions` can reach it from the EDT during startup. That is one
  unavoidable full read per transcript (the accumulators have nothing yet), but it happens on
  the UI thread. Worth making the restore path async.
- **`ClaudeStatusService.poll`** still wakes every 500 ms per monitored session. Cheap (two small
  file reads) but not gated on anything.

## Original fix order (all done)

1. **#1 MessageHistoryPanel** — mtime guard + visibility gate. Smallest change, largest win.
2. **#2 `loadSessions`** — per-file mtime cache. Removes the "every message re-parses everything" loop.
3. **#4 `kill -0` → `ProcessHandle`** — one-line change, deletes a whole class of spawns.
4. **#5 `wireToolbarRefresh`** — visibility gate + share the worktree sweep.
5. **#6 status bar** — visibility gate + longer interval.
6. **#7 refreshOrphans** — fold into the same gating helper.
