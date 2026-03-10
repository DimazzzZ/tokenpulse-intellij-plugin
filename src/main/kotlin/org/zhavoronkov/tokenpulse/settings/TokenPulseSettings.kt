package org.zhavoronkov.tokenpulse.settings

/**
 * Status bar display mode - determines what data is shown in the status bar.
 */
enum class StatusBarDisplayMode(val displayName: String) {
    /** Adapts based on first enabled provider type (usage % or dollars). */
    AUTO("Auto (adapts to primary provider)"),

    /** Sum of all dollar balances across providers. */
    TOTAL_DOLLARS("Total balance (sum of all)"),

    /** Show data from a specific single provider. */
    SINGLE_PROVIDER("Single provider")
}

/**
 * Status bar format - controls verbosity of status bar text.
 */
enum class StatusBarFormat(val displayName: String, val description: String) {
    /** Compact format: "86% 5h • 72% wk" or "$500" */
    COMPACT("Compact", "86% 5h • 72% wk / \$500"),

    /** Descriptive format: "86% of 5h remaining" or "$500 total remaining" */
    DESCRIPTIVE("Descriptive", "86% of 5h remaining / \$500 remaining")
}

/**
 * Status bar dollar format - controls how dollar amounts are displayed.
 */
enum class StatusBarDollarFormat(val displayName: String, val description: String) {
    /** Show only remaining balance: "$200" */
    REMAINING_ONLY("Remaining only", "\$200 (OR)"),

    /** Show used/remaining: "$193/$200" (used of current remaining balance) */
    USED_OF_REMAINING("Used / Remaining", "\$193/\$200 (OR)"),

    /** Show percentage remaining: "51% remaining" */
    PERCENTAGE_REMAINING("Percentage remaining", "51% remaining (OR)")
}

data class TokenPulseSettings(
    var accounts: List<Account> = emptyList(),
    var refreshIntervalMinutes: Int = 15,
    var autoRefreshEnabled: Boolean = true,
    var showCredits: Boolean = true,
    var showTokens: Boolean = true,
    var hasSeenWelcome: Boolean = false,
    var lastSeenVersion: String = "",

    // Status bar display settings
    /** Determines what data is shown in the status bar. */
    var statusBarDisplayMode: StatusBarDisplayMode = StatusBarDisplayMode.AUTO,

    /** For SINGLE_PROVIDER mode: which account to show (null = first enabled). */
    var statusBarPrimaryAccountId: String? = null,

    /** Controls verbosity of status bar text. */
    var statusBarFormat: StatusBarFormat = StatusBarFormat.COMPACT,

    /** Controls how dollar amounts are displayed in status bar. */
    var statusBarDollarFormat: StatusBarDollarFormat = StatusBarDollarFormat.USED_OF_REMAINING
)
