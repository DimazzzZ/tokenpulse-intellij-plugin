# 1. Raise the minimum supported IntelliJ platform to 2025.3 (build 253)

- **Status:** Accepted
- **Date:** 2026-08-16

## Context

JetBrains' Plugin Verifier reported that TokenPulse 0.4.0 used API scheduled for
removal: two calls to `SimpleListCellRenderer.create(String, Function)` in
`TokenPulseConfigurable` (the "Display mode" and "Format" combo boxes).

The obvious fix — switching to the sibling `create(Customizer)` overload — is a
dead end: inspecting the 262 bytecode shows **both** `create` overloads are
annotated `@Deprecated(forRemoval = true)`. Swapping one for the other would
only defer the same report to the next release.

The actual replacement is `textListCellRenderer` from
`com.intellij.ui.dsl.listCellRenderer`. That package **does not exist in the
2024.2 SDK** the plugin previously compiled against, so the deprecation could
not be resolved while remaining on that baseline.

Two further facts shaped the decision:

- In the 2024.2 SDK the deprecated method carries **no deprecation annotation at
  all**, so the Kotlin compiler could never have warned about it. Only a
  verifier run against a newer IDE reveals the problem.
- The plugin declares no `untilBuild`, so it already ran on IDEs where the
  method is deprecated.

## Decision

Raise the baseline: `platformVersion` 2024.2 → **2025.3.6** and
`pluginSinceBuild` 242 → **253** (`gradle.properties`), and migrate both call
sites to `textListCellRenderer`.

`textListCellRenderer` was verified to be public API — the only
`@ApiStatus.Internal` members of that package are `groupedTextListCellRenderer`
and `comboBoxEditorRenderer`, neither of which is used.

## Consequences

- **Users on 2024.2 through 2025.2 no longer receive updates.** This is the
  hard-to-reverse part: once a release ships with `sinceBuild = 253`, those
  installs are pinned to the last compatible version. Accepted deliberately, as
  there is no way to use the replacement API otherwise.
- `buildSearchableOptions` is disabled. On the 2025.3.6 SDK its bundled JBR
  aborts at JVM startup on macOS (`SIGABRT` in `Threads::create_vm` →
  `JvmtiExport::post_vm_initialized`, ~0.18s in, before any plugin code runs).
  It only indexes individual settings labels for Settings search — the
  TokenPulse settings *page* remains findable — and disabling it also removes
  ~23s from release builds.
- Compatibility documentation in `README.md` and `DEVELOPMENT.md` was updated to
  state 2025.3+.
- Future platform-baseline raises should be recorded as new ADRs rather than
  edited into this one.
