# Concurrency rules

These rules exist because unbounded blocking on background threads has frozen the IDE in production. The shared application thread pool is used by VFS, indexing, and git4idea; if we exhaust it with hung work, the entire IDE stops responding.

## All process I/O MUST have a timeout

Never write code that blocks indefinitely on a child process. The following patterns are **banned**:

```kotlin
// BANNED — readText() blocks until EOF; if the process never closes stdout, this hangs forever.
val output = process.inputStream.bufferedReader().readText()
process.waitFor()  // even with a timeout here, readText already hung the thread
```

```kotlin
// BANNED — waitFor() with no argument blocks forever.
val rc = proc.waitFor()
```

Use `ProcessHelper.execWithTimeout(...)` instead. It reads stdout on a daemon thread, enforces a real `process.waitFor(timeout)`, and `destroyForcibly()`s the child if the deadline elapses. Returns `ExecResult(exitCode, output, timedOut)`.

If you genuinely need to invoke a process directly (e.g. you need stdin, or non-merged stderr), the same rules apply: spawn a reader thread, call `process.waitFor(timeoutMs)`, destroy on miss.

Default timeout for git invocations is **15 seconds**. Pick a higher value only when justified (e.g. `claude login` has its own 120 s timeout because it's interactive).

## Background work uses ClauditorExecutor, not the IDE pool

For periodic polling, git invocations, file scans, and any other work that could hang on external state, use `com.clauditor.util.ClauditorExecutor.submit { ... }` rather than `ApplicationManager.getApplication().executeOnPooledThread { ... }`.

`ClauditorExecutor` is bounded (max 8 daemon threads, named `Clauditor-N`). Even if every slot leaks, the IDE-shared pool stays healthy.

It is acceptable to use `executeOnPooledThread` for one-shot user-triggered actions (button click handlers, etc) where the task is short and bounded. In doubt, use `ClauditorExecutor`.

## Periodic refresh MUST be single-flight

If you wire a periodic alarm or focus-listener that re-runs background work, gate it on an `AtomicBoolean inFlight` so ticks/events that fire while a previous run is still in progress are skipped (not queued):

```kotlin
val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)
fun refresh() {
    if (!inFlight.compareAndSet(false, true)) return
    ClauditorExecutor.submit {
        try { /* work */ } finally { inFlight.set(false) }
    }
}
```

Without this, a single hung invocation backs up an unbounded queue of duplicate work behind it.

## Git invocations: pass `GIT_OPTIONAL_LOCKS=0`

`ProcessHelper.execWithTimeout` accepts an `extraEnv` map. For background git polling, always pass `mapOf("GIT_OPTIONAL_LOCKS" to "0")`. This tells git to skip taking any lock (e.g. `index.lock`) that isn't strictly required, so our reads never contend with the user's interactive `git rebase` / `git commit`.

## Verification

Before publishing, grep for banned patterns:

```
grep -rn "process\.inputStream\.bufferedReader().*readText\|proc\.inputStream\.bufferedReader().*readText\|\.waitFor()$" src/main/kotlin --include="*.kt"
```

Any hit needs a real timeout via `ProcessHelper.execWithTimeout`.
