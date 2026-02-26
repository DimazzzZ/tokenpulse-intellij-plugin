# TokenPulse

[![CI](https://github.com/DimazzzZ/token-pulse/actions/workflows/ci.yml/badge.svg)](https://github.com/DimazzzZ/token-pulse/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

**TokenPulse** is an IntelliJ IDEA plugin that aggregates token balances and credit usage across multiple AI providers directly in your IDE status bar.

## Features

- **📊 Live Aggregate Balance** - See your total remaining credits/tokens at a glance in the status bar.
- **🤖 Multi-Provider Support** - Supports **OpenRouter** (API Key & Provisioning Key) and **Cline** (Personal & Organization).
- **🔄 Smart Refresh** - Configurable auto-refresh with TTL caching and single-flight coalescing to avoid rate limits.
- **🔐 Secure Storage** - API keys are stored securely using IntelliJ's built-in `PasswordSafe`.
- **📈 Dashboard Overview** - Detailed table view showing per-account status, balance, and last refresh time.
- **🚀 Onboarding & Updates** - Friendly welcome notification for new users and "What's New" highlights after updates.

## Installation

### From Marketplace (Coming Soon)
1. Open IntelliJ IDEA → `Settings` → `Plugins`
2. Search for "TokenPulse" in Marketplace
3. Click `Install`

### From GitHub Releases
1. Download the latest `token-pulse-*.zip` from [Releases](https://github.com/DimazzzZ/token-pulse/releases)
2. Open IntelliJ IDEA → `Settings` → `Plugins`
3. Click the ⚙️ icon → `Install Plugin from Disk...`
4. Select the downloaded ZIP file

## Setup

1. Open **Settings** → **Tools** → **TokenPulse**.
2. Click **Add Account** to configure your providers:
   - **OpenRouter**: Use a regular API key or a Provisioning Key (for full credit tracking).
   - **Cline**: Use your personal or organization API key.
3. Configure the **Refresh Interval** (default is 15 minutes).
4. The aggregate balance will appear in your status bar automatically.

## Development

See [DEVELOPMENT.md](DEVELOPMENT.md) for detailed instructions on building and testing the plugin.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
