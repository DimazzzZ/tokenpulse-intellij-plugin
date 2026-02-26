package org.zhavoronkov.tokenpulse.settings

data class TokenPulseSettings(
    var accounts: List<Account> = emptyList(),
    var refreshIntervalMinutes: Int = 15,
    var autoRefreshEnabled: Boolean = true,
    var showCredits: Boolean = true,
    var showTokens: Boolean = true,
    var hasSeenWelcome: Boolean = false,
    var lastSeenVersion: String = ""
)
