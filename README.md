# TokenPulse

[![CI](https://github.com/DimazzzZ/token-pulse/actions/workflows/ci.yml/badge.svg)](https://github.com/DimazzzZ/token-pulse/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

**TokenPulse** is an IntelliJ IDEA plugin that aggregates token balances and credit usage across multiple AI providers directly in your IDE status bar.

## Features

- **📊 Live Aggregate Balance** — See your total remaining credits/tokens at a glance in the status bar.
- **🤖 Multi-Provider Support** — Supports **OpenRouter** (Provisioning Key), **Cline** (API Key), **Nebius AI Studio** (Billing Session), and **OpenAI** (OAuth Token for usage data).
- **🔄 Smart Refresh** — Configurable auto-refresh with TTL caching and single-flight coalescing to avoid rate limits.
- **🔐 Secure Storage** — API keys are stored securely using IntelliJ's built-in `PasswordSafe`.
- **📈 Dashboard Overview** — Detailed table view showing per-account provider, key preview, status, last refresh time, and credits.
- **🚀 Onboarding & Updates** — Friendly welcome notification for new users and "What's New" highlights after updates.

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
2. Click **+** to add a provider account:
   - Select the **Provider** (Cline, OpenRouter, Nebius, or OpenAI).
   - **For Cline / OpenRouter:** Click **"Get API Key →"** to open the provider's key management page, then paste the key into the **API Key** field.
   - **For Nebius:** Click **"Connect Billing Session →"**, follow the 3-step guide in the dialog (open billing page → run a one-line console script → paste the output), then click **Connect**.
   - **For OpenAI:** Click **"Connect OAuth Token →"**, paste your OpenAI API key or OAuth token, then click **Connect**.
3. Configure the **Refresh Interval** (default: 15 minutes).
4. The aggregate balance appears in your status bar automatically.

### Where to get your key

| Provider | Auth type | How to connect |
|---|---|---|
| Cline | API Key | https://app.cline.bot/dashboard/account?tab=api-keys |
| OpenRouter | **Provisioning Key** | https://openrouter.ai/settings/provisioning-keys |
| Nebius AI Studio | **Billing Session** | Click "Connect Billing Session →" in the account dialog |
| OpenAI | **OAuth Token** | Click "Connect OAuth Token →" in the account dialog |

> **Tip:** If you add multiple accounts for the same provider, each entry shows a partial key preview
> (e.g. `sk-or-…91bc`) so you can tell them apart at a glance. Nebius accounts show "Session" instead,
> and OpenAI accounts show "OAuth".

## FAQ

### Why does TokenPulse require a manually generated key?

TokenPulse reads your **credit/balance** from each provider's API. OAuth-issued tokens do not
have access to the balance/credits endpoints on either Cline or OpenRouter — only manually
generated keys do. This is a provider-side limitation, not a plugin choice.

Generating a key takes about 30 seconds and the **"Get API Key →"** button in the dialog takes
you directly to the right page.

### How does Nebius authentication work?

Nebius AI Studio does **not** expose a billing API accessible via API key. TokenPulse reads your
trial balance from the same internal billing gateway used by the Token Factory web UI.

The **"Connect Billing Session →"** dialog walks you through a 3-step process:
1. Open the Nebius billing page in your browser and log in.
2. Open the browser console (F12 → Console) and run the one-line script shown in the dialog.
3. Copy the output (a small JSON blob) and paste it into the dialog.

The session is stored securely in IntelliJ's `PasswordSafe`. Sessions are short-lived — when
yours expires, the plugin will show an **Auth Error** and you can reconnect in under a minute.

### Why does OpenRouter require a Provisioning Key specifically?

OpenRouter's regular API keys do **not** expose the `/api/v1/credits` endpoint. Only
**Provisioning Keys** return credit balance information. If you use a regular API key, TokenPulse
will not be able to fetch your balance and will show an Auth Error.

You can create a Provisioning Key at: https://openrouter.ai/settings/provisioning-keys

### How does OpenAI usage tracking work?

OpenAI's **Usage API** and **Cost API** provide access to your personal account usage data.
TokenPulse uses a personal API key or OAuth token to fetch:

- **Credits used** — Total cost from the Cost API
- **Tokens used** — Sum of input, output, cached input, and reasoning tokens from the Usage API

> **Note:** OpenAI accounts report **usage** (what you've spent), not **remaining balance**.
> The status bar will show `$X.XX used` for OpenAI accounts instead of a remaining balance.

To get an OpenAI API key:
1. Go to https://platform.openai.com/account/api-keys
2. Create a new secret key
3. Copy the key and paste it into the TokenPulse dialog

### Is my key stored securely?

Yes. Keys are stored in IntelliJ's `PasswordSafe` (OS keychain on macOS/Windows, encrypted file
on Linux). They are never written to plain-text settings files.

### The status bar shows "—" or "Error"

- **Auth Error** — the key is invalid or has been revoked. Re-generate it from the provider's dashboard.
- **Rate Limited** — too many requests. Increase the refresh interval in Settings → TokenPulse.
- **Error** — a network or API error. Check your internet connection and try "Refresh All" from the dashboard.
- **$X.XX used** — OpenAI account showing usage data (not a balance).

## Development

See [DEVELOPMENT.md](DEVELOPMENT.md) for detailed instructions on building and testing the plugin.

## License

This project is licensed under the MIT License — see the [LICENSE](LICENSE) file for details.
