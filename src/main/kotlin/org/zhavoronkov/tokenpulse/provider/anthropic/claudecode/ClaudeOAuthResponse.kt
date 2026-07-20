package org.zhavoronkov.tokenpulse.provider.anthropic.claudecode

import com.google.gson.annotations.SerializedName

/**
 * Response from Claude Code's undocumented OAuth usage API.
 *
 * Endpoint: GET https://api.anthropic.com/api/oauth/usage
 *
 * This is an undocumented internal API. Field names and structure may change
 * without notice. Handle gracefully if parsing fails.
 *
 * Usage windows:
 * - five_hour: 5-hour session limit (current session)
 * - seven_day: 7-day weekly limit (all models)
 * - seven_day_opus: 7-day weekly limit (Opus only)
 * - seven_day_sonnet: 7-day weekly limit (Sonnet only)
 * - seven_day_oauth_apps: 7-day weekly limit (OAuth apps)
 * - extra_usage: Additional usage information
 */
data class ClaudeOAuthUsageResponse(
    @SerializedName("five_hour")
    val fiveHour: UsageWindow? = null,

    @SerializedName("seven_day")
    val sevenDay: UsageWindow? = null,

    @SerializedName("seven_day_opus")
    val sevenDayOpus: UsageWindow? = null,

    @SerializedName("seven_day_sonnet")
    val sevenDaySonnet: UsageWindow? = null,

    @SerializedName("seven_day_oauth_apps")
    val sevenDayOauthApps: UsageWindow? = null,

    @SerializedName("extra_usage")
    val extraUsage: UsageWindow? = null,
)

/**
 * A single usage window with utilization percentage and reset time.
 *
 * @param utilization Usage percentage (0.0-1.0 or 0-100, need to verify format)
 * @param resetsAt ISO-8601 timestamp when the window resets
 */
data class UsageWindow(
    @SerializedName("utilization")
    val utilization: Double? = null,

    @SerializedName("resets_at")
    val resetsAt: String? = null,
)
