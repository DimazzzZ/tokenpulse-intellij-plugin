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
     * Legacy value kept for backward-compatibility with persisted settings.
     * Accounts with this type are automatically migrated to [OPENROUTER_PROVISIONING_KEY]
     * on settings load. Do not use for new accounts.
     */
    @Deprecated("Use OPENROUTER_PROVISIONING_KEY instead")
    OPENROUTER_API_KEY("API Key (legacy)")
}

data class Account(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val providerId: ProviderId,
    val authType: AuthType,
    val isEnabled: Boolean = true,
    /** Masked preview of the API key, e.g. "sk-or-…91bc". Stored for display only, not sensitive. */
    val keyPreview: String = ""
) {
    /** Human-readable label shown in the accounts table. */
    fun displayLabel(): String {
        val providerName = providerId.displayName
        return if (keyPreview.isNotEmpty()) "$providerName • $keyPreview" else providerName
    }
}

/** Generates a short masked preview from a raw secret key. */
fun generateKeyPreview(secret: String): String {
    if (secret.length < 8) return "…"
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
