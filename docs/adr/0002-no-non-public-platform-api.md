# 2. Forbid non-public IntelliJ Platform API and enforce it in the verifier

- **Status:** Accepted
- **Date:** 2026-08-16

## Context

JetBrains penalizes plugins that reach into non-public platform API, and
Marketplace review flags it. The risk is not hypothetical for this project: the
platform baseline raise in [ADR-0001](0001-raise-minimum-platform-to-2025-3.md)
was forced by a scheduled-for-removal API that shipped in 0.4.0 unnoticed.

Three separate gaps let that happen:

1. In the SDK the plugin compiled against, the offending method carried **no
   deprecation annotation at all**, so the Kotlin compiler could not warn. Only
   a verifier run against a *newer* IDE reveals such problems.
2. The Plugin Verifier's `failureLevel` defaults to `COMPATIBILITY_PROBLEMS`
   only. It printed the finding and still exited successfully.
3. `verifyPlugin` ran only in the release workflow, so the report appeared at
   publish time rather than on the pull request that introduced it.

An audit at the time this ADR was written found the plugin **clean**: the
verifier verdict is `Compatible` and it emits no internal, experimental, or
deprecated usage reports. So this is a guard against regression, not a
migration.

## Decision

Fail the build on the whole non-public-API surface, not just compatibility
breakage. `intellijPlatform.pluginVerification.failureLevel` enumerates:

- `COMPATIBILITY_PROBLEMS`
- `DEPRECATED_API_USAGES`
- `SCHEDULED_FOR_REMOVAL_API_USAGES`
- `INTERNAL_API_USAGES`
- `EXPERIMENTAL_API_USAGES`
- `OVERRIDE_ONLY_API_USAGES`
- `NON_EXTENDABLE_API_USAGES`

`MISSING_DEPENDENCIES` and `NOT_DYNAMIC` are **deliberately excluded**: the
verifier report already lists unavailable *optional* dependencies (for example
`com.intellij.jetbrains.client`) that this project does not control, so
including them would fail spuriously.

`verifyPlugin` also runs on pull requests, not only on release.

## Consequences

- Any future use of `@ApiStatus.Internal` / `@ApiStatus.Experimental` API, or of
  anything deprecated or scheduled for removal, **fails CI**. Treat this as a
  constraint when choosing platform APIs: prefer a public alternative, and if
  none exists, raise the platform baseline (new ADR) rather than reaching for
  internal API.
- The gate was verified to actually fire, not merely configured: temporarily
  calling `groupedTextListCellRenderer` (which *is* `@ApiStatus.Internal`,
  unlike the `textListCellRenderer` this plugin uses) turns the build red with
  `1 usage of internal API`.
- **Reflection is outside this gate.** `OpenRouterPluginBridgeClient` reflects
  into another *plugin's* service class; that is not platform API, so the
  verifier cannot see it in either direction. It is guarded and degrades to
  "plugin not installed" on any failure, and must stay that way.
- Deprecations surface only against *newer* IDEs — the call that forced
  ADR-0001 carries no annotation at all in the oldest build we support. The gate
  is therefore only meaningful when verification runs against a recent IDE.
  Splitting that (pull requests verifying the newest supported build, releases
  sweeping every supported line) is the agreed follow-up and is implemented
  separately; at the time this ADR was accepted, CI still ran the unpinned
  `recommended()` set everywhere.

## Related

- [ADR-0001](0001-raise-minimum-platform-to-2025-3.md) — the platform baseline
  raise whose root cause this gate is meant to catch earlier next time.
