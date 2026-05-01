# Publishing a release

## 0. Authorization — read this first

**A `publish` instruction authorizes exactly one release: the change that was on the table when the user said it.** It does not carry forward.

Before pushing a `v*` tag, the user MUST have said "publish" (or equivalent) **about the specific change you are about to ship**, *after* the change is built and ready, *after* they have had the chance to test it. If any of those is not true, do not tag.

Concretely, this means:

- **Never** push a release tag in the same turn that you implemented the change. The user has not seen it run yet.
- **Never** reuse a prior "publish" approval after writing new code. Each change requires its own approval.
- **Never** publish "to fix what we just broke." A failed marketplace review is not implicit consent to ship the next attempt — it raises the bar, not lowers it.
- When in doubt, build the plugin, commit, push to `main`, and **stop**. Tell the user the build is ready and ask whether to tag. The cost of asking is a single message; the cost of an unwanted release is a marketplace version that cannot be unpublished.

The marketplace is append-only: every published version is permanent. Treat `git push origin v<version>` like `rm -rf` — irreversible, requires explicit fresh authorization, no exceptions.

## 1. Pre-flight

Before bumping the version, ensure:

1. **README.md** is up to date with any new or changed features
2. **plugin.xml `<description>`** reflects the current feature set (this controls the JetBrains Marketplace listing)
3. **gradle.properties `pluginVersion`** is bumped appropriately

Note: `<change-notes>` in plugin.xml is auto-generated at release time by `.github/workflows/release.yml` (from git log between tags) and injected via the `CHANGELOG` env var in `build.gradle.kts`. Do not edit the static `<change-notes>` block — it's only a fallback for local builds.

## 2. Verify build

```
./gradlew compileKotlin
```

## 3. Commit

Stage explicit paths (not `git add -A` / `git add .` — too easy to sweep in unrelated changes):

```
git add gradle.properties <other modified files>
git commit -m "<message>"
```

## 4. Tag and push

```
git tag v<version>
git push origin main
git push origin v<version>
```

## 5. CI takes over

Pushing a `v*` tag triggers `.github/workflows/release.yml`, which:

1. Generates `<change-notes>` HTML from `git log $PREV_TAG..HEAD` and exposes it as the `CHANGELOG` env var.
2. Runs `./gradlew buildPlugin` with `CHANGELOG` injected (overrides the static `<change-notes>` in plugin.xml).
3. Runs `./gradlew publishPlugin` with `PUBLISH_TOKEN` (uploads to JetBrains Marketplace).
4. Creates a GitHub Release with the built `.zip` attached.

Marketplace listing typically updates within a few minutes after the workflow finishes.

## Pitfalls

- **Tag must be `v*`** — the workflow only fires on the `v` prefix.
- **Never amend a pushed tag** — bump and push a new tag instead. The marketplace rejects re-uploads of the same version.
- **Don't `--no-verify`** — pre-commit hooks exist for a reason. If a hook fails, fix the underlying issue and create a new commit.
- **If CI fails after the tag is pushed**: investigate, fix on `main`, then push a *new* tag (don't reuse the old one — the workflow won't re-run on the same SHA cleanly, and the marketplace won't accept a re-publish at the same version).
