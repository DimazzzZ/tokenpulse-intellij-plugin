package org.zhavoronkov.tokenpulse.settings

import org.zhavoronkov.tokenpulse.model.ProviderId
import java.util.UUID

enum class AuthType(val displayName: String) {
    OPENROUTER_API_KEY("API Key"),
    OPENROUTER_PROVISIONING_KEY("Provisioning Key"),
    CLINE_API_KEY("API Key")
}

data class Account(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val providerId: ProviderId,
    val authType: AuthType,
    val isEnabled: Boolean = true
)
