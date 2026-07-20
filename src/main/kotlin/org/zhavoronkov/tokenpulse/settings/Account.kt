package org.zhavoronkov.tokenpulse.settings

import org.zhavoronkov.tokenpulse.model.ConnectionType
import java.io.File
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
    CODEX_CLI_LOCAL("Codex CLI"),
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
     * Xiaomi MiMo API key (pay-as-you-go).
     *
     * The stored secret is a raw API key string (e.g., "sk-...").
     * Balance is tracked via Xiaomi platform session capture.
     */
    XIAOMI_API_KEY("API Key"),

    /**
     * Xiaomi MiMo Token Plan API key.
     *
     * The stored secret is a raw API key string (e.g., "tp-...").
     * Credits usage is tracked via Xiaomi platform session capture.
     */
    XIAOMI_TOKEN_PLAN_KEY("Token Plan Key")
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
 * @property claudeConfigDir Claude Code only: the CLAUDE_CONFIG_DIR this
 *   account tracks. null/blank means the default dir (~/.claude, unsuffixed
 *   keychain service). Ignored by all non-Claude connection types.
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
     * Claude Code only: the CLAUDE_CONFIG_DIR this account tracks.
     * null/blank => default (~/.claude). Persisted to tokenpulse.xml; absent
     * in older state files, which deserialize to null (seamless migration).
     */
    var claudeConfigDir: String? = null
) {
    /** Human-readable label shown in the accounts table. */
    fun displayLabel(): String {
        val providerName = connectionType.fullDisplayName
        val detail = when {
            name.isNotBlank() -> name
            keyPreview.isNotEmpty() -> keyPreview
            else -> null
        }
        return if (detail != null) "$providerName • $detail" else providerName
    }
}

/**
 * Display label for a Claude Code account's credential source: the config dir,
 * shown as `~/.claude` for the default dir or `~/<basename>` (e.g.
 * `~/.claude-work`) for a custom `CLAUDE_CONFIG_DIR`. Used in the accounts
 * table's "API Key" column and stored as [Account.keyPreview].
 */
fun claudeConfigDirLabel(configDir: String?): String =
    if (configDir.isNullOrBlank()) "~/.claude" else "~/${File(configDir).name}"

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

    // Claude Code has only CLAUDE_CODE_LOCAL now; the previous OAuth_TOKEN
    // value is removed. Unknown values persisted in XML fall through to
    // `expectedAuthType` (i.e., CLAUDE_CODE_LOCAL) via sanitizeAccounts.
    val authType = when {
        isOpenAiPlatformWithValidAuth -> account.authType
        else -> expectedAuthType
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

    // Claude Code has only CLAUDE_CODE_LOCAL now; the previous OAuth_TOKEN
    // value is removed. The `AuthType.entries.find { ... } ?: expectedAuthType`
    // guard above already coerces the old string.
    val finalAuthType = when {
        isOpenAiPlatformWithValidAuth -> validAuthType
        validAuthType != expectedAuthType -> expectedAuthType
        else -> validAuthType
    }

    // Backfill keyPreview for Claude Code accounts migrated from older versions
    // (they were stored with an empty or "CLI" preview). Show the config dir so
    // the accounts table has a meaningful, per-account value.
    val finalKeyPreview = if (validConnectionType == ConnectionType.CLAUDE_CODE &&
        (account.keyPreview.isBlank() || account.keyPreview == "CLI")
    ) {
        claudeConfigDirLabel(account.claudeConfigDir)
    } else {
        account.keyPreview
    }

    account.copy(
        connectionType = validConnectionType,
        authType = finalAuthType,
        isEnabled = account.isEnabled,
        keyPreview = finalKeyPreview
    )
}
