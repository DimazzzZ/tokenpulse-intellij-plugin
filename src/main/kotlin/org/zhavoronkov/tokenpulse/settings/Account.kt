package org.zhavoronkov.tokenpulse.settings

import org.zhavoronkov.tokenpulse.model.ProviderId
import java.util.UUID

data class Account(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val providerId: ProviderId,
    val isEnabled: Boolean = true
)
