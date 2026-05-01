# No IntelliJ internal API

Never reference IntelliJ Platform classes from internal packages. The JetBrains Marketplace verifier rejects plugins that do, and the constructor/method signatures for these classes change between IDE versions, causing `NoSuchMethodError` at runtime.

## What's off-limits

- Anything in a `.impl.` package (e.g. `com.intellij.openapi.fileEditor.impl.FileEditorOpenOptions`, `com.intellij.openapi.fileEditor.impl.EditorWindow`).
- Anything explicitly annotated `@ApiStatus.Internal`.
- Methods with `$intellij_platform_ide_impl` in the JVM name (these are module-internal Kotlin members exposed by accident).

If a public API surfaces an internal type as a parameter or return value, that's still off-limits — the marketplace verifier flags use of the internal type even when reached through a public method.

## What's allowed

- Anything in `com.intellij.openapi.<x>.ex.*` — the `.ex.` packages are the official extension points for plugin developers (e.g. `FileEditorManagerEx`).
- Plain public APIs in non-`impl` packages (e.g. `com.intellij.ui.components`, `com.intellij.openapi.fileEditor.FileEditorManager`).
- Deprecated public APIs are still preferable to internal alternatives.

## When you can't find a public path

If a feature genuinely requires functionality that's only in `.impl.`, do not reach for the internal class. Either:

1. Refactor the feature to avoid needing it (often the right answer — see how the tab-position fix was reworked from `FileEditorOpenOptions` into in-place widget refresh).
2. Drop the feature.

Reflection into internal classes is **not** an acceptable workaround — the marketplace verifier catches that too, and the runtime breakage risk is the same.

## Verification

Before publishing, grep the codebase for internal references:

```
grep -rn "\.impl\." src/main/kotlin --include="*.kt"
```

Any hit is suspect — confirm it's not an internal-package import.
