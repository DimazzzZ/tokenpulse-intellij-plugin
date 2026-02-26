# Changelog

All notable changes to the TokenPulse plugin will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
