package org.zhavoronkov.tokenpulse.settings

import org.zhavoronkov.tokenpulse.model.ProviderId
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
    OPENROUTER_PROVISIONING_KEY("Provisioning Key"),
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
     * Legacy value kept for backward-compatibility with persisted settings.
     * Accounts with this type are automatically migrated to [OPENROUTER_PROVISIONING_KEY]
     * on settings load. Do not use for new accounts.
     */
    @Deprecated("Use OPENROUTER_PROVISIONING_KEY instead")
    OPENROUTER_API_KEY("API Key (legacy)")
}

data class Account(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    /**
     * Defaults to [ProviderId.CLINE] so that legacy XML entries without this field
     * can be deserialized without crashing. Migrated on load in [TokenPulseSettingsService].
     */
    var providerId: ProviderId = ProviderId.CLINE,
    /**
     * Defaults to [AuthType.CLINE_API_KEY] for the same backward-compatibility reason.
     * Migrated on load in [TokenPulseSettingsService].
     */
    var authType: AuthType = AuthType.CLINE_API_KEY,
    var isEnabled: Boolean = true,
    /** Masked preview of the API key, e.g. "sk-or-…91bc". Stored for display only, not sensitive. */
    var keyPreview: String = ""
) {
    /** Human-readable label shown in the accounts table. */
    fun displayLabel(): String {
        val providerName = providerId.displayName
        return if (keyPreview.isNotEmpty()) "$providerName • $keyPreview" else providerName
    }
}

/** Generates a short masked preview from a raw secret key. */
fun generateKeyPreview(secret: String): String {
    if (secret.length < 10) return "…"
    val prefix = secret.take(6)
    val suffix = secret.takeLast(4)
    return "$prefix…$suffix"
}

/**
 * Migrates any legacy [AuthType.OPENROUTER_API_KEY] accounts to
 * [AuthType.OPENROUTER_PROVISIONING_KEY] so existing users are not broken.
 */
@Suppress("DEPRECATION")
fun List<Account>.migrateAuthTypes(): List<Account> = map { account ->
    if (account.authType == AuthType.OPENROUTER_API_KEY) {
        account.copy(authType = AuthType.OPENROUTER_PROVISIONING_KEY)
    } else {
        account
    }
}

/**
 * Ensures each account's [Account.authType] is consistent with its [Account.providerId].
 *
 * This handles cases where old XML data has a mismatched or default-filled combination
 * (e.g. an OpenRouter account that was deserialized with the default CLINE_API_KEY authType).
 * The canonical authType for each provider is:
 *  - OPENROUTER → OPENROUTER_PROVISIONING_KEY
 *  - CLINE      → CLINE_API_KEY
 *  - NEBIUS     → NEBIUS_BILLING_SESSION
 *  - OPENAI     → OPENAI_OAUTH
 */
fun List<Account>.normalizeProviderAuthTypes(): List<Account> = map { account ->
    val expectedAuthType = when (account.providerId) {
        ProviderId.OPENROUTER -> AuthType.OPENROUTER_PROVISIONING_KEY
        ProviderId.CLINE -> AuthType.CLINE_API_KEY
        ProviderId.NEBIUS -> AuthType.NEBIUS_BILLING_SESSION
        ProviderId.OPENAI -> AuthType.OPENAI_OAUTH
    }
    if (account.authType != expectedAuthType) {
        account.copy(authType = expectedAuthType)
    } else {
        account
    }
}

/**
 * Sanitizes accounts to ensure valid providerId and authType values.
 *
 * This is a defensive measure to handle cases where:
 * - providerId is missing or invalid (defaults to CLINE)
 * - authType doesn't match the provider's expected type (corrects it)
 * - account has other malformed fields
 *
 * Returns a sanitized list with all accounts having valid, consistent values.
 */
fun List<Account>.sanitizeAccounts(): List<Account> = map { account ->
    // Validate providerId - default to CLINE if invalid/missing
    val validProviderId = try {
        ProviderId.entries.find { it == account.providerId } ?: ProviderId.CLINE
    } catch (e: Exception) {
        ProviderId.CLINE
    }

    // Determine expected authType for this provider
    val expectedAuthType = when (validProviderId) {
        ProviderId.OPENROUTER -> AuthType.OPENROUTER_PROVISIONING_KEY
        ProviderId.CLINE -> AuthType.CLINE_API_KEY
        ProviderId.NEBIUS -> AuthType.NEBIUS_BILLING_SESSION
        ProviderId.OPENAI -> AuthType.OPENAI_OAUTH
    }

    // Validate authType - use expected type if invalid/missing
    val validAuthType = try {
        AuthType.entries.find { it == account.authType } ?: expectedAuthType
    } catch (e: Exception) {
        expectedAuthType
    }

    // Ensure isEnabled is valid (default to true)
    val validEnabled = account.isEnabled

    // Preserve other fields
    account.copy(
        providerId = validProviderId,
        authType = validAuthType,
        isEnabled = validEnabled
    )
}
