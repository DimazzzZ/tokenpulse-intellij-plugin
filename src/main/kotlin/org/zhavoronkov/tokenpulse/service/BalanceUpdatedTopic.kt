package org.zhavoronkov.tokenpulse.service

import com.intellij.util.messages.Topic
import org.zhavoronkov.tokenpulse.model.ProviderResult

interface BalanceUpdatedListener {
    fun balanceUpdated(accountId: String, result: ProviderResult)
}

object BalanceUpdatedTopic {
    val TOPIC = Topic.create("TokenPulseBalanceUpdated", BalanceUpdatedListener::class.java)
}
