When publishing a new version (tagging a release), always ensure:

1. **README.md** is up to date with any new or changed features
2. **plugin.xml `<description>`** reflects the current feature set (this controls the JetBrains Marketplace listing)
3. **gradle.properties `pluginVersion`** is bumped appropriately

Note: `<change-notes>` in plugin.xml is auto-generated at release time by `.github/workflows/release.yml` (from git log between tags) and injected via the `CHANGELOG` env var in `build.gradle.kts`. Do not edit the static `<change-notes>` block — it's only a fallback for local builds.
