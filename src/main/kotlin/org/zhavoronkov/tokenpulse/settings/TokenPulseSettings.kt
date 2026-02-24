package org.zhavoronkov.tokenpulse.settings

data class TokenPulseSettings(
    var accounts: List<Account> = emptyList(),
    var refreshIntervalMinutes: Int = 15
)
