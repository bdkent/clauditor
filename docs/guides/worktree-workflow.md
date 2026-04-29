# Worktree Workflow

Run Claude on a feature without disturbing your main checkout — and run several at once. This guide walks through the worktree workflow Clauditor enables, from "I want to try a refactor" to a merged PR, and shows what the plugin handles for you so you don't have to leave the IDE or memorize `git worktree` commands.

## Why bother with worktrees?

A single Claude session in your main checkout has two problems:

1. **It blocks you.** Claude is editing the same files you have open. You can't review another change, run a test on a different branch, or kick off a second exploration without stepping on each other.
2. **It commits you.** A speculative refactor mixed into your working tree is hard to throw away cleanly. `git stash` is fragile; a half-applied `git reset` is worse.

Git worktrees solve both: each worktree is a separate directory checked out to its own branch, sharing the same `.git`. Claude can rip apart `feature-x` in `worktrees/feature-x/` while you keep working on `main`. Throw away the experiment? Delete the directory. Keep it? Merge or open a PR.

The catch: managing worktrees by hand is fiddly. You have to remember `git worktree add`, set the right cwd when launching `claude`, track which branch is ahead of which, hand-write the merge command, and clean up when done. **That's what Clauditor handles.**

## The workflow at a glance

1. **Create** a worktree from the Sessions panel — Clauditor invokes `git worktree add` and launches `claude` in it.
2. **Work** in the session tab. Claude edits files in the worktree directory, isolated from your main tree.
3. **Track** state from the worktree toolbar: branch name, ahead/behind vs. your project branch, uncommitted changes.
4. **Promote** when you're happy: ask Claude to commit (one click), then either fast-forward merge into `main` or open a PR — both one click each.
5. **Update** if `main` has moved: one click rebases the worktree branch onto `main`.
6. **Escalate** if the work outgrows a single Claude session: open the worktree as a separate IDE project window with one button.

Every step is in the session editor's toolbar. You never type a `git worktree` command.

## Step 1 — Create a worktree

Open the **Sessions** tool window and switch to the **Worktrees** tab. Click **+ New Worktree**, name it (e.g. `auth-refactor`), and Clauditor will:

- Run `git worktree add` under `<project>/.claude/worktrees/auth-refactor/` against a new branch named `auth-refactor`.
- Launch `claude --worktree auth-refactor --name auth-refactor` so the session is registered with that label.
- Open the session as a tab in the editor area, just like a file.

The new tab is pinned to the worktree directory. Anything Claude reads, writes, or runs happens there, not in your main checkout.

> **Tip:** Pick a name that's also a fine branch name. Clauditor uses it as both the worktree directory and the branch.

## Step 2 — Work in the session

The session tab is a fully interactive Claude terminal — same as a normal session, but with the **Worktree toolbar** along the top. The toolbar always reflects current branch state:

- **Branch label** with a tree icon shows the worktree directory name.
- **Status text** shows ahead/behind counts: `auth-refactor  ↑3 ↓1 vs main`.
- The numbers refresh automatically whenever Claude finishes a tool use, so you don't need to ask "did anything actually commit?"

Run multiple worktrees in parallel? Open more tabs. Each one is independent — different branch, different cwd, different conversation. They all show up in the **Worktrees** tab of the Sessions panel so you can find them again after restarting the IDE.

## Step 3 — Commit from the toolbar

When Claude has made changes you want to keep, hit the **Commit** button in the worktree toolbar. Clauditor sends Claude an instruction to commit all changes with a descriptive message.

The button's enabled state mirrors the actual git state:

- Disabled when the worktree is clean (`No uncommitted changes`).
- Enabled when there's anything to commit.

You're not approving a hand-rolled commit message — you're asking Claude to write one based on the conversation. Edit it in the terminal if you want to tweak.

## Step 4 — Update from `main` (rebase)

If `main` has moved while you were working, the toolbar shows `↓N` and the **↓ Update** button enables. Click it; Clauditor runs `git rebase <main>` directly (no Claude involvement — this is a deterministic git operation).

Outcomes are surfaced as IDE notifications:

- **Success:** "Rebased auth-refactor onto main."
- **Conflict:** "Rebase conflicts — resolve in the terminal with `git rebase --continue` or `--abort`."
- **Other failure:** the first 200 chars of git's stderr.

The button stays disabled if you have uncommitted changes (the rebase would refuse anyway), with a tooltip explaining why.

## Step 5 — Merge or PR

You have two ways to land the work, depending on how lightweight your team's process is.

### Merge to project (fast-forward)

The **↑ Merge to project** button is enabled when the worktree is `↑N` ahead and `↓0` behind — i.e. fast-forward is possible. Clicking it runs `git merge --ff-only <worktree-branch>` from your project directory. Notifications surface success or "Fast-forward not possible — update worktree first."

This is the right button for solo work, throwaway experiments, or trunk-based development.

### Create PR

For GitHub repos, Clauditor shows a **Create PR** button (auto-detected from the `origin` remote URL). Clicking it asks Claude to:

1. Push the worktree branch.
2. Run `gh pr create` with a title and summary derived from the conversation.

The button is enabled only when there are commits to push and the worktree is clean.

> **Where this matters:** since the prompt comes from the session, the PR description reflects everything Claude did and why — not just `git diff`. That's a noticeably better PR body than what you'd write from scratch.

## Step 6 — Escalate to a full IDE

Sometimes a feature outgrows a single conversation: you want to run tests, set breakpoints, browse files in the project tree, or hand the worktree to a teammate. The **Open in IDE** button opens the worktree directory as a separate IDE project window.

Crucially, the new window's Sessions panel finds the originating session. Clauditor probes both encoded forms of the project path so the session you spawned the IDE from shows up in the new window's session list — you can pick up exactly where you left off, but now with the full IDE on the worktree.

There's also a small **Reveal** button (folder icon) that opens the worktree directory in your OS file manager.

## Anatomy of the worktree toolbar

```
┌─ Worktree Toolbar ──────────────────────────────────────────────────────────────────────────┐
│  auth-refactor  ↑3 ↓0 vs main   [Commit] [↓ Update] [↑ Merge] [Create PR] [Open in IDE] [*] │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

Every button's enabled state and tooltip reflect the **actual** git state of the worktree, refreshed whenever Claude does anything. There's no "I clicked Merge but git was in some other state" surprise — the button is only clickable when the operation is safe.

## Worktree storage

Clauditor places worktrees at:

```
<project>/.claude/worktrees/<name>/
```

This keeps them out of any IDE indexer noise but reachable for `git`, while colocating them with the Claude project so cleanup is obvious. Add `.claude/worktrees/` to `.gitignore` if you haven't already.

## Cleaning up

When you're done with a worktree:

1. If it has uncommitted work you want to keep — `Commit` first.
2. Merge or push as above.
3. Close the session tab.
4. Remove the worktree from disk: `git worktree remove .claude/worktrees/<name>` (Clauditor doesn't auto-delete — you decide when it's gone).
5. Delete the branch if you don't want it: `git branch -d <name>`.

## Comparison: with vs. without Clauditor

**Create a worktree**
- *Without:* `git worktree add ../foo -b foo`, `cd ../foo`, `claude`
- *With Clauditor:* one click, named tab opens

**See branch state (ahead/behind, dirty)**
- *Without:* `git status`, `git rev-list --left-right --count main...HEAD`
- *With Clauditor:* always visible in the toolbar, refreshes itself

**Commit Claude's changes**
- *Without:* type the message yourself
- *With Clauditor:* one click; Claude writes the message from conversation context

**Rebase onto main**
- *Without:* switch to terminal, `git rebase main`, parse the output
- *With Clauditor:* one click; conflicts surface as IDE notifications

**Merge into main**
- *Without:* `cd <project>`, `git merge --ff-only foo`
- *With Clauditor:* one click; only enabled when fast-forward is actually possible

**Open a PR**
- *Without:* `git push -u origin foo`, then `gh pr create` or the web UI
- *With Clauditor:* one click; the PR body is written from the conversation, not just the diff

**Open the worktree as a separate project**
- *Without:* new window, **File → Open**, navigate to path
- *With Clauditor:* one click; the new window's session list auto-finds the originating session

**Run two Claude tasks at once**
- *Without:* two terminals, two cwds, two mental contexts
- *With Clauditor:* two tabs in the same IDE, each pinned to its own worktree

The unifying theme: **Clauditor turns worktree state into clickable buttons that mirror the actual git state, and lets Claude do the parts that need a human-readable message.**

## When *not* to use a worktree

- **One-line fixes.** A worktree is overkill for a typo.
- **Cross-cutting changes that touch the whole repo.** Worktrees shine when the work is locally scoped.
- **You actively need both branches' files merged in your editor.** A worktree separates them by design.

For everything else — speculative refactors, multi-file features, parallel exploration, "let me just try something" — worktrees plus Clauditor is the path of least resistance.
