# Changelog

All notable changes to the TokenPulse β plugin will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
