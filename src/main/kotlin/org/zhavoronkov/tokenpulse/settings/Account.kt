package org.zhavoronkov.tokenpulse.settings

import org.zhavoronkov.tokenpulse.model.ConnectionType
import java.util.UUID

/**
 * Supported authentication types per provider.
 *
 * OpenRouter: only Provisioning Keys expose the `/api/v1/credits` endpoint required for
 * balance tracking. Regular API keys do not expose credits info and are therefore not supported.
 *
 * Cline: personal API key only.
 */
enum class AuthType(val displayName: String) {
    CLAUDE_CODE_LOCAL("Local Config"),
    OPENROUTER_PROVISIONING_KEY("Provisioning Key"),
    OPENROUTER_PLUGIN_BRIDGE("Plugin Integration"),
    CLINE_API_KEY("API Key"),

    /**
     * Nebius AI Studio billing session.
     *
     * Nebius does not expose a billing API accessible via API key. Instead, the plugin
     * captures a browser session (app_session cookie + CSRF token + contract parentId)
     * from an embedded browser login flow and stores it securely in PasswordSafe.
     *
     * The stored secret is a JSON blob: {"appSession":"...","csrfCookie":"...","csrfToken":"...","parentId":"..."}
     */
    NEBIUS_BILLING_SESSION("Billing Session"),

    /**
     * OpenAI personal OAuth token for accessing usage/cost data.
     *
     * Uses Authorization Code with PKCE flow. The stored secret is a JSON blob:
     * {"accessToken":"...","refreshToken":"...","expiresAt":1234567890}
     */
    OPENAI_OAUTH("OAuth Token"),

    /**
     * OpenAI personal API key for accessing usage/cost data.
     *
     * The stored secret is a raw API key string (e.g., "sk-...").
     * This is the recommended method for new accounts.
     */
    OPENAI_API_KEY("API Key"),

    /**
     * ChatGPT subscription billing session.
     *
     * ChatGPT Plus/Pro/Team subscriptions don't expose usage via API.
     * Instead, the plugin captures a browser session (similar to Nebius)
     * from an embedded browser login flow and stores it securely in PasswordSafe.
     *
     * The stored secret is a JSON blob: {"accessToken":"...","sessionToken":"...","userId":"..."}
     */
    CHATGPT_BILLING_SESSION("Billing Session")
}

/**
 * Represents a configured account for a provider connection.
 *
 * @property id Unique identifier for the account.
 * @property name Optional user-defined name for the account.
 * @property connectionType The type of connection/authentication method.
 * @property authType The specific authentication type (usually derived from connectionType).
 * @property isEnabled Whether this account is active for balance tracking.
 * @property keyPreview Masked preview of the API key for display purposes.
 * @property chatGptUseCodex For ChatGPT accounts: whether to use local Codex CLI for detailed rate limits.
 */
data class Account(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var connectionType: ConnectionType = ConnectionType.CLINE_API,
    var authType: AuthType = AuthType.CLINE_API_KEY,
    var isEnabled: Boolean = true,
    /** Masked preview of the API key, e.g. "sk-or-…91bc". Stored for display only, not sensitive. */
    var keyPreview: String = "",
    /**
     * For ChatGPT accounts: whether to use local Codex CLI for detailed rate limit data.
     * - null = not set yet (user hasn't made a choice)
     * - true = user explicitly enabled Codex integration
     * - false = user explicitly disabled Codex integration
     *
     * When null and a ChatGPT account exists, the system will attempt to detect if Codex
     * is already available. If so, defaults to true; otherwise defaults to false.
     */
    var chatGptUseCodex: Boolean? = null
) {
    /** Human-readable label shown in the accounts table. */
    fun displayLabel(): String {
        val providerName = connectionType.fullDisplayName
        return if (keyPreview.isNotEmpty()) "$providerName • $keyPreview" else providerName
    }
}

/** Generates a short masked preview from a raw secret key. */
fun generateKeyPreview(secret: String): String {
    if (secret.length < KEY_PREVIEW_MIN_LENGTH) return "…"
    val prefix = secret.take(KEY_PREVIEW_PREFIX_LENGTH)
    val suffix = secret.takeLast(KEY_PREVIEW_SUFFIX_LENGTH)
    return "$prefix…$suffix"
}

/** Minimum key length required to generate a preview. */
private const val KEY_PREVIEW_MIN_LENGTH = 10

/** Number of characters to show at the start of the key preview. */
private const val KEY_PREVIEW_PREFIX_LENGTH = 6

/** Number of characters to show at the end of the key preview. */
private const val KEY_PREVIEW_SUFFIX_LENGTH = 4

/**
 * Ensures each account's [Account.authType] is consistent with its [Account.connectionType].
 *
 * This handles cases where XML data has a mismatched combination.
 * The canonical authType for each connection type is determined by [ConnectionType.defaultAuthType].
 * For OpenAI Platform, both API_KEY and OAUTH types are allowed for backward compatibility.
 */
fun List<Account>.normalizeConnectionAuthTypes(): List<Account> = map { account ->
    val expectedAuthType = account.connectionType.defaultAuthType

    // For OpenAI Platform, allow both API_KEY and OAUTH types (backward compatibility)
    val validOpenAiTypes = setOf(AuthType.OPENAI_API_KEY, AuthType.OPENAI_OAUTH)
    val isOpenAiPlatformWithValidAuth = account.connectionType == ConnectionType.OPENAI_PLATFORM &&
        account.authType in validOpenAiTypes
    val authType = if (isOpenAiPlatformWithValidAuth) {
        account.authType
    } else {
        expectedAuthType
    }
    account.copy(authType = authType)
}

/**
 * Sanitizes accounts to ensure valid connectionType and authType values.
 *
 * This is a defensive measure to handle cases where:
 * - connectionType is missing or invalid (defaults to CLINE_API)
 * - authType doesn't match the connection's expected type (corrects it)
 *
 * Returns a sanitized list with all accounts having valid, consistent values.
 */
fun List<Account>.sanitizeAccounts(): List<Account> = map { account ->
    // Validate connectionType - default to CLINE_API if invalid/missing
    val validConnectionType = try {
        ConnectionType.entries.find { it == account.connectionType } ?: ConnectionType.CLINE_API
    } catch (_: Exception) {
        ConnectionType.CLINE_API
    }

    // Determine expected authType for this connection type
    val expectedAuthType = validConnectionType.defaultAuthType

    // Validate authType - use expected type if invalid/missing
    val validAuthType = try {
        AuthType.entries.find { it == account.authType } ?: expectedAuthType
    } catch (_: Exception) {
        expectedAuthType
    }

    // For OpenAI Platform, allow both API_KEY and OAUTH types (backward compatibility)
    val validOpenAiTypes = setOf(AuthType.OPENAI_API_KEY, AuthType.OPENAI_OAUTH)
    val isOpenAiPlatformWithValidAuth = validConnectionType == ConnectionType.OPENAI_PLATFORM &&
        validAuthType in validOpenAiTypes
    val finalAuthType = if (isOpenAiPlatformWithValidAuth) {
        validAuthType
    } else if (validAuthType != expectedAuthType) {
        expectedAuthType
    } else {
        validAuthType
    }

    account.copy(
        connectionType = validConnectionType,
        authType = finalAuthType,
        isEnabled = account.isEnabled
    )
}
