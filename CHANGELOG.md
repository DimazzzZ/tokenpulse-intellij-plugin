# Changelog

All notable changes to the TokenPulse plugin will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed
- **Add Provider dialog redesigned** — no name field required; provider identity is derived
  automatically from the provider name + a partial key preview (first 6 + last 4 characters).
- **Manual API keys only** — OAuth-based sign-in has been removed. OAuth-issued tokens do not
  expose the balance/credits endpoints on Cline or OpenRouter, making them incompatible with
  TokenPulse's core purpose. Only manually generated API keys are supported.
- **OpenRouter: Provisioning Key only** — Regular OpenRouter API keys do not expose the
  `/api/v1/credits` endpoint required for balance tracking. Only Provisioning Keys are supported.
  Existing accounts configured with a regular API key are automatically migrated to the
  Provisioning Key auth type on settings load.
- **No auth-type selector** — each provider now maps to exactly one key type automatically:
  Cline → API Key, OpenRouter → Provisioning Key. No user choice required.
- **"Get API Key →" button** — opens the exact provider key-management page in your browser:
  Cline dashboard or OpenRouter Provisioning Keys page.
- **Dashboard columns** — now show: Provider | API Key (preview) | Status | Last Updated | Credits.

### Removed
- OAuth callback server and OAuth flow services (`ClineOAuthService`, `OpenRouterOAuthService`,
  `OAuthCallbackServer`) — superseded by the manual-key-only approach.
- OpenRouter regular API key support — only Provisioning Keys expose the credits endpoint.

## [0.1.0] - 2026-02-26

### Added
- OpenRouter and Cline provider support.
- Status bar widget with live aggregate balance.
- Settings page with CRUD account management.
- Dashboard dialog with per-account details.
- TTL caching and single-flight refresh coalescing.
- Secure credential storage via IntelliJ PasswordSafe.
- **New scaffolding from OpenRouter**:
  - Detekt static analysis with strict quality gate.
  - CI improvements with SARIF reporting and GitHub Code Scanning.
  - Welcome notification for first-time users.
  - "What's New" notification for upgrades.
  - Centralized metadata in `gradle.properties`.
  - Automated GitHub Release workflow.
  - Comprehensive documentation and utility scripts.
