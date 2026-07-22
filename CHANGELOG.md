# Changelog

All notable changes to the TokenPulse plugin will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **Rich status-bar tooltip** — hovering the status-bar widget now shows a native Swing popup
  with real progress bars for each provider/account, replacing the old HTML tooltip; theme-aware
  colors and screen-clamped positioning so it always fits on screen.
- **Humanized reset times** — quota reset timestamps render as `Today 14:30`, `Tomorrow 09:00`,
  `Wed 09:00`, or `Aug 3, 14:30` instead of raw ISO strings.
- **Claude Code multi-account support** — discovers and tracks every Claude account on disk
  (default `~/.claude` plus any `CLAUDE_CONFIG_DIR` locations, including suffixed macOS Keychain
  entries), adding one row per account with its own config dir.
- **Per-account identity labels** — Claude account display names are enriched from the account's
  `oauthAccount` (email • organization) on first successful refresh.

### Changed
- **Progress-bar rendering** — the status-bar tooltip is now assembled as a Swing panel
  (`TokenPulseTooltipPanel` + `TooltipModel`) instead of generated HTML, and `ProgressBarRenderer`
  is now a theme-aware color helper (returns `JBColor`s rather than HTML strings).
- **Claude account labels** — auto-named personal organizations (`"<email>'s Organization"`) are
  collapsed so the label shows just the email; real organization names still display as
  `email • Organization`. Existing persisted names are migrated on load.
- **Refresh-failure notifications are auth-type aware** — API-key providers (Cline, OpenAI API key,
  Xiaomi, OpenRouter provisioning) get a "re-enter your API key" hint on an auth error, while
  CLI/OAuth/session providers (Claude Code, Codex CLI, OpenAI OAuth, Nebius session, OpenRouter
  bridge) show only the provider's own (already actionable) message.
- **Claude Code usage now uses the OAuth API exclusively** — reads the credentials `claude` already stored
  (macOS Keychain / `~/.claude/.credentials.json`) and calls the usage endpoint directly, with
  automatic token refresh on expiry, instead of scraping `claude` CLI output.
  The legacy CLI-output parser (`ClaudeCliOutputParser` / `ClaudeCliUsageExtractor`) has been removed.
- **Codex/ChatGPT usage now uses the OAuth API directly** — reads the credentials `codex` already
  stored in `~/.codex/auth.json` (or `$CODEX_HOME/auth.json`) and calls ChatGPT's usage endpoint
  directly for the 5-hour, weekly, and code-review quotas, instead of spawning a `codex app-server`
  JSON-RPC subprocess. Expired access tokens are refreshed and the rotated tokens are written back
  to `auth.json` so the real `codex` login stays in sync. Also fixes a display bug where reset
  timestamps were mis-scaled (treated as milliseconds instead of seconds). The app-server client
  (`CodexAppServerClient`) and its CLI-output parser (`CodexCliOutputParser`) have been removed.
- **Accounts table** — the "API Key" column shows the Claude config dir (`~/.claude`,
  `~/.claude-work`) for Claude Code rows instead of a key preview.
- **Claude helper renamed and split** — `ClaudeCliExecutor` is now `ClaudeCliDetector`
  (its public surface is just `isInstalled()` + `verifyVersion()`), and OS detection moved to
  a provider-agnostic `HostOs` enum + `detectHostOs()` in `utils`.

### Fixed
- **Logged-in Claude users no longer shown as "session expired"** — a missing or unparseable token
  expiry is treated as usable (with a small clock-skew buffer); only a genuine `invalid_grant`
  refresh failure is treated as an auth error, and a usage `403` is reported as an
  access/permission problem instead of a login prompt (now also sends `anthropic-version`).

### Removed
- **`ClaudeConnectDialog`** — replaced by inline account discovery in the Add-Account dialog.
- **Legacy Claude CLI usage-extraction** — `ClaudeCliOutputParser`, `ClaudeCliUsageExtractor`,
  and their tests (`ClaudeCliOutputParserTest`, `ClaudeCliFunctionalTest`); pruned the unused
  `getEnvironment()` and `getWorkingDirectory()` helpers on the CLI helper.

## [0.3.1] - 2026-07-08

### Added
- **ClinePass usage limits** for Cline API key accounts — optional 5-hour, weekly, and monthly
  usage (percent + reset time) displayed in the tooltip when available
- **TokenPulsePluginService** — centralized version retrieval and update-notification plumbing

### Changed
- **Claude CLI detection reliability** — version check now uses a bounded timeout (8 s) instead of
  unbounded `waitFor`, preventing UI hangs when the Claude binary doesn't respond
- **CLI connect dialog hardening** — `performDetection()` is wrapped in a `try/catch` so any
  exception resolves the UI state instead of freezing on "Detecting…"
- **Modal dialog UI fix** — `invokeLater` now uses `ModalityState.any()` so detection results
  update the dialog's status label immediately while the modal is still open
- **Invalid HTML sanitized** — `Row.comment()` calls no longer pass `html`-wrapped strings,
  eliminating the `UiDslException` from `idea.log`
- **Debug logging added** — info-level `TokenPulseLogger.UI` entries in `detectCli()` and
  `performDetection()` for easier troubleshooting

### Fixed
- **CliConnectDialog NPE** (PR #16) — constructor-time access to subclass properties before
  initialization completed, causing `NullPointerException` in `RowImpl.label(text)`
- **Xiaomi token-plan `JsonNull` handling** (PR #14) — `ClassCastException` when the API returns
  `null` for `data`, `monthUsage`, or `items` fields
- **Tooltip loading state** — status-bar tooltip now shows "Refreshing balances…" during initial
  refresh (e.g. Claude cold start) instead of the misleading "No accounts configured"

## [0.3.0] - 2026-06-22

### Added
- **Xiaomi MiMo provider** — Two connection types: API (pay-as-you-go) and Token Plan (subscription Credits)
- **Session capture flow** — XiaomiConnectDialog for capturing platform session via cURL
- **Shared infrastructure** — SessionParser, HttpErrorHandler, CurlCookieExtractor utilities for session-based providers
- **Status bar format options** — Percentage, Used/Remaining, Remaining only with Compact/Descriptive variants
- **Short number formatting** — Credits displayed as 3.3B, 7.7M, 1.5K for readability

### Changed
- **Account fields changed to `var`** — Required for XStream XML serialization (was breaking account persistence on restart)
- **Status bar format improvements** — formatAutoMode now iterates all accounts until finding usable data
- **Widget refresh on settings change** — Status bar updates immediately when format dropdown changes
- **Code quality overhaul** — Extracted utilities, improved test coverage, fixed detekt issues

### Fixed
- **Account serialization** — All Account fields must be `var` for XStream deserialization
- **Status bar showing "--"** — Fixed multiple issues causing status bar to show no data
- **Format order** — "Used / Remaining" now shows "Remaining / Total" as intended
- **PERCENTAGE_REMAINING** — Returns "--" instead of raw dollar amount when percentage cannot be calculated
- **Xiaomi API accountId** — Fixed empty accountId in BalanceSnapshot

### Removed
- **Unused constants** — Removed XIAOMI_API_URL, XIAOMI_TOKEN_PLAN_SGP_URL, duplicate formatCredits

## [0.2.0] - 2026-04-22

### Added
- **Codex CLI integration** — ChatGPT now uses Codex CLI for simpler, more reliable setup without OAuth complexity
- **Credential failure cooldowns** — Smart throttling reduces notification spam for repeated credential errors
- **Improved Nebius handling** — Better balance extraction and connection reliability

### Changed
- **ChatGPT provider refactored** — Removed OAuth/subscription flow, now CLI-only matching Claude Code architecture
- **Notification improvements** — Better error handling and reduced duplicate notifications
- **Code quality** — Removed dead code, fixed warnings, and improved exception handling across providers

### Fixed
- Various stability improvements across provider implementations
- UI and settings cleanup for consistent CLI-detection pattern

### Removed
- **ChatGPT OAuth flow** — Legacy OAuth PKCE flow removed in favor of Codex CLI
- Deprecated ChatGPT subscription tracking code

---

## [0.1.0] - 2026-03-10 (Initial β Release)

### Added
- **Multi-provider balance tracking** — Monitor token balances and credit usage across multiple AI providers directly in your IDE status bar.
- **Provider support**:
  - **OpenRouter** — Provisioning Key support with credits tracking
  - **Cline** — API Key support for personal and organization accounts
  - **OpenAI Platform** — Admin API Key (`sk-admin-...`) with usage/costs tracking via Organization APIs
  - **ChatGPT Subscription** — OAuth PKCE flow for ChatGPT Pro/Plus subscription tracking
  - **Nebius AI Studio** — Cookie-based authentication with trial/paid balance tracking
  - **Claude Code** — CLI-based usage extraction via `claude` command
- **Status bar widget** — Live aggregate balance displayed in the IDE status bar with quick-access menu.
- **Dashboard dialog** — Per-account details view showing status, credits, tokens, and last update time.
- **Settings page** — Full CRUD account management under Tools → TokenPulse.
- **TTL caching** — Configurable auto-refresh with smart caching to avoid rate limits.
- **Single-flight refresh coalescing** — Prevents duplicate requests when multiple refresh triggers occur.
- **Secure credential storage** — All API keys and tokens stored via IntelliJ PasswordSafe.
- **Welcome notification** — First-time user onboarding with quick links to settings.
- **"What's New" notification** — Upgrade notifications highlighting new features.
- **Static analysis** — Detekt integration with strict quality gate.
- **CI/CD pipeline** — GitHub Actions with automated testing, code scanning, and marketplace publishing.
- **Comprehensive documentation** — README, DEVELOPMENT.md, TESTING.md, DEBUGGING.md, and MARKETPLACE.md guides.

### Technical Details
- **IntelliJ Platform** — Supports 2024.2+ (build 242+) through 2025.1.x
- **Java 21** — LTS version for development and runtime
- **Kotlin 2.0** — Modern Kotlin with strict null safety
- **Test coverage** — Kover integration with 50%+ line coverage requirement
