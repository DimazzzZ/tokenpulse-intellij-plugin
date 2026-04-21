package org.zhavoronkov.tokenpulse.model

import org.zhavoronkov.tokenpulse.settings.AuthType
import org.zhavoronkov.tokenpulse.settings.StatusBarDollarFormat

/**
 * Represents a specific connection/authentication method for an AI provider.
 *
 * Each [Provider] may support multiple connection methods. For example, OpenAI supports
 * both Codex CLI and Platform API Key authentication.
 *
 * @property provider The parent provider (company) this connection belongs to.
 * @property displayName Human-readable name for UI display.
 * @property description Brief description of this connection type.
 * @property defaultAuthType The default [AuthType] for this connection.
 */
enum class ConnectionType(
    val provider: Provider,
    val displayName: String,
    val description: String,
    val defaultAuthType: AuthType
) {
    /**
     * Claude Code CLI - uses local Claude CLI for authentication.
     * No API key required; CLI handles authentication via browser.
     */
    CLAUDE_CODE(
        provider = Provider.ANTHROPIC,
        displayName = "Claude Code CLI",
        description = "Uses the Claude CLI for local development. Requires npm installation.",
        defaultAuthType = AuthType.CLAUDE_CODE_LOCAL
    ),

    /**
     * Codex CLI - CLI-based access for ChatGPT Plus/Pro/Team users.
     * Provides rate limit tracking for subscription plans via local Codex CLI.
     */
    CODEX_CLI(
        provider = Provider.OPENAI,
        displayName = "Codex CLI",
        description = "For ChatGPT Plus, Pro, or Team subscribers. Uses local Codex CLI installation.",
        defaultAuthType = AuthType.CODEX_CLI_LOCAL
    ),

    /**
     * OpenAI Platform - Admin API key for organization usage tracking.
     * Provides detailed token usage and cost data.
     */
    OPENAI_PLATFORM(
        provider = Provider.OPENAI,
        displayName = "Platform API Key",
        description = "Organization Admin API Key for usage tracking. Requires sk-admin-... key.",
        defaultAuthType = AuthType.OPENAI_API_KEY
    ),

    /**
     * Cline API - personal API key for Cline service.
     */
    CLINE_API(
        provider = Provider.CLINE,
        displayName = "API Key",
        description = "Personal API key from Cline dashboard.",
        defaultAuthType = AuthType.CLINE_API_KEY
    ),

    /**
     * OpenRouter Provisioning - provisioning key for credit tracking.
     * Note: Regular API keys do not expose credit information.
     */
    OPENROUTER_PROVISIONING(
        provider = Provider.OPENROUTER,
        displayName = "Provisioning Key",
        description = "Provisioning Key for credit tracking. Regular API keys not supported.",
        defaultAuthType = AuthType.OPENROUTER_PROVISIONING_KEY
    ),

    /**
     * OpenRouter Plugin Bridge - uses the OpenRouter IntelliJ plugin's credentials.
     * No separate API key required; integrates with the installed OpenRouter plugin.
     */
    OPENROUTER_PLUGIN(
        provider = Provider.OPENROUTER,
        displayName = "Plugin Integration",
        description = "Uses OpenRouter plugin credentials. Requires OpenRouter plugin to be installed.",
        defaultAuthType = AuthType.OPENROUTER_PLUGIN_BRIDGE
    ),

    /**
     * Nebius Billing - browser session for Token Factory billing access.
     * Uses captured session tokens since no public API is available.
     */
    NEBIUS_BILLING(
        provider = Provider.NEBIUS,
        displayName = "Billing Session",
        description = "Browser session for Token Factory billing. Captured automatically.",
        defaultAuthType = AuthType.NEBIUS_BILLING_SESSION
    );

    /**
     * Full display name including provider prefix.
     * Format: "Provider: Connection Type" (e.g., "OpenAI: Platform API Key")
     */
    val fullDisplayName: String
        get() = "${provider.displayName}: $displayName"

    /**
     * Returns the balance formats supported by this connection type.
     * Used to filter the balance format dropdown in settings based on selected provider.
     */
    val supportedBalanceFormats: Set<StatusBarDollarFormat>
        get() = when (this) {
            // OpenRouter has remaining + used, supports all formats
            OPENROUTER_PROVISIONING, OPENROUTER_PLUGIN -> setOf(
                StatusBarDollarFormat.REMAINING_ONLY,
                StatusBarDollarFormat.USED_OF_REMAINING,
                StatusBarDollarFormat.PERCENTAGE_REMAINING
            )
            // Nebius has remaining + total, supports all formats
            NEBIUS_BILLING -> setOf(
                StatusBarDollarFormat.REMAINING_ONLY,
                StatusBarDollarFormat.USED_OF_REMAINING,
                StatusBarDollarFormat.PERCENTAGE_REMAINING
            )
            // Cline has only remaining, other formats will fallback
            CLINE_API -> setOf(StatusBarDollarFormat.REMAINING_ONLY)
            // OpenAI Platform has only used, no remaining
            OPENAI_PLATFORM -> setOf(StatusBarDollarFormat.REMAINING_ONLY) // Will show "used" as fallback
            // Claude Code uses percentage from metadata (not Credits)
            CLAUDE_CODE -> emptySet() // Uses metadata percentage, not dollar formats
            // Codex CLI uses percentage from metadata (not Credits)
            CODEX_CLI -> emptySet() // Uses metadata percentage, not dollar formats
        }

    /**
     * Returns true if this connection type uses percentage-based display (not dollar formats).
     */
    val usesPercentageDisplay: Boolean
        get() = this == CLAUDE_CODE || this == CODEX_CLI

    /**
     * Whether this connection type is currently available for use.
     * Some connection types may be temporarily disabled while features are in development.
     */
    val isAvailable: Boolean
        get() = when (this) {
            OPENROUTER_PLUGIN -> false // Coming soon - requires OpenRouter plugin API exposure
            else -> true
        }

    companion object {
        /**
         * Returns all connection types for a given provider.
         *
         * @param provider The provider to filter by.
         * @return List of connection types available for this provider.
         */
        fun forProvider(provider: Provider): List<ConnectionType> =
            entries.filter { it.provider == provider }

        /**
         * Returns all available connection types.
         */
        fun availableEntries(): List<ConnectionType> =
            entries.filter { it.isAvailable }

        /**
         * Returns all connection types grouped by provider.
         */
        fun groupedByProvider(): Map<Provider, List<ConnectionType>> =
            entries.groupBy { it.provider }

        /**
         * Returns all connection types sorted alphabetically by full display name.
         */
        fun sortedByFullDisplayName(): List<ConnectionType> =
            entries.sortedBy { it.fullDisplayName }
    }
}
