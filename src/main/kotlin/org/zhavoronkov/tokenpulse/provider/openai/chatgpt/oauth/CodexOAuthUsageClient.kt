package org.zhavoronkov.tokenpulse.provider.openai.chatgpt.oauth

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName
import org.zhavoronkov.tokenpulse.provider.oauth.AbstractOAuthUsageClient
import org.zhavoronkov.tokenpulse.provider.oauth.OAUTH_BODY_PREVIEW
import org.zhavoronkov.tokenpulse.provider.oauth.OAUTH_USER_AGENT
import org.zhavoronkov.tokenpulse.utils.TokenPulseLogger
import java.net.URI
import java.net.http.HttpRequest
import java.time.Duration

/**
 * Calls Codex's undocumented ChatGPT usage endpoint directly, using the OAuth
 * credentials the `codex` CLI already stores on disk.
 *
 * - GET https://chatgpt.com/backend-api/wham/usage
 * - Headers: Authorization: Bearer <access_token>, ChatGPT-Account-Id: <account_id>,
 *   and X-OpenAI-Fedramp: true for FedRAMP accounts.
 *
 * The endpoint is undocumented and may change without notice.
 */
class CodexOAuthUsageClient(
    private val usageUrl: String = USAGE_URL,
) : AbstractOAuthUsageClient<CodexOAuthUsageClient.UsageResult>(
    connectSeconds = TIMEOUT_SECONDS,
    logTag = "CodexOAuthUsageClient",
) {

    private val gson: Gson = Gson()

    fun fetch(accessToken: String, accountId: String, fedramp: Boolean = false): UsageResult {
        if (accessToken.isBlank()) return UsageResult.AuthError
        return execute(buildRequest(accessToken, accountId, fedramp))
    }

    override fun transient(message: String): UsageResult = UsageResult.Transient(message)

    override fun mapStatus(status: Int, body: String): UsageResult = when (status) {
        200 -> parseSuccess(body)
        401 -> UsageResult.AuthError
        // 403 is not an auth error: org/geo policy, WAF, etc. The token
        // is still valid; surfacing "session expired" would be wrong.
        403 -> UsageResult.Forbidden(
            "Codex API access forbidden (403). Check your account/organization access."
        )
        429 -> UsageResult.RateLimited
        else -> UsageResult.Transient(
            "Usage API returned $status: ${body.take(OAUTH_BODY_PREVIEW)}"
        )
    }

    private fun buildRequest(accessToken: String, accountId: String, fedramp: Boolean): HttpRequest {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(usageUrl))
            .header("Authorization", "Bearer $accessToken")
            .header("ChatGPT-Account-Id", accountId)
            .header("User-Agent", OAUTH_USER_AGENT)
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .GET()
        if (fedramp) {
            builder.header("X-OpenAI-Fedramp", "true")
        }
        return builder.build()
    }

    private fun parseSuccess(body: String): UsageResult {
        return try {
            val payload = gson.fromJson(body, UsagePayload::class.java)
                ?: return UsageResult.Transient("Empty response from usage API")
            // The endpoint no longer wraps windows under `rate_limits`; the
            // real shape is a top-level `rate_limit` (singular) object with
            // positional primary/secondary windows, plus top-level plan_type
            // and email. A missing `rate_limit` is a real (unexpected) shape
            // change, not merely "no usage".
            val rateLimit = payload.rateLimit
                ?: return UsageResult.Transient("Usage response missing rate_limit")

            // Classify each present window into the 5-hour / weekly slot by its
            // limit_window_seconds (positional order is NOT reliable — a
            // weekly-only account carries its weekly window in primary_window).
            val (fiveHour, weekly) = classifyWindows(
                listOfNotNull(rateLimit.primaryWindow, rateLimit.secondaryWindow)
            )

            val codeReview = payload.additionalRateLimits
                ?.firstOrNull { it.isCodeReview() }
                ?.rateLimit
                ?.primaryWindow
                ?.toSnapshot()

            val usage = CodexUsage(
                fiveHour = fiveHour,
                weekly = weekly,
                codeReview = codeReview,
                planType = payload.planType,
                email = payload.email?.takeIf { it.isNotBlank() },
            )
            TokenPulseLogger.Provider.debug(
                "[CodexOAuthUsageClient] Parsed usage: 5h=${usage.fiveHour?.usedPercent}%, " +
                    "weekly=${usage.weekly?.usedPercent}%, codeReview=${usage.codeReview?.usedPercent}%",
            )
            UsageResult.Success(usage)
        } catch (e: JsonSyntaxException) {
            UsageResult.Transient("Invalid response format from usage API: ${e.message}")
        }
    }

    /**
     * Drop each window into the first slot whose canonical duration its
     * `limit_window_seconds` matches (±5%): ~18000s → 5-hour, ~604800s →
     * weekly. Windows that match neither (or a slot already filled) are
     * dropped. Both slots are optional — OpenAI has removed the short window
     * for many plans, so a weekly-only result is normal.
     */
    private fun classifyWindows(windows: List<WindowJson>): Pair<WindowSnapshot?, WindowSnapshot?> {
        var fiveHour: WindowSnapshot? = null
        var weekly: WindowSnapshot? = null
        for (w in windows) {
            val seconds = w.limitWindowSeconds ?: continue
            when {
                fiveHour == null && matchesDuration(seconds, FIVE_HOUR_SECONDS) -> fiveHour = w.toSnapshot()
                weekly == null && matchesDuration(seconds, WEEKLY_SECONDS) -> weekly = w.toSnapshot()
            }
        }
        return fiveHour to weekly
    }

    private fun matchesDuration(seconds: Int, target: Int): Boolean =
        kotlin.math.abs(seconds - target) <= target * DURATION_TOLERANCE

    /** Parsed 5-hour / weekly / code-review windows plus the plan type. */
    data class CodexUsage(
        val fiveHour: WindowSnapshot?,
        val weekly: WindowSnapshot?,
        val codeReview: WindowSnapshot?,
        val planType: String?,
        val email: String?,
    )

    /** A single rate-limit window. [resetAtEpochSeconds] is Unix seconds. */
    data class WindowSnapshot(
        val usedPercent: Int,
        val windowSeconds: Int,
        val resetAtEpochSeconds: Long?,
    )

    sealed class UsageResult {
        data class Success(val usage: CodexUsage) : UsageResult()
        object AuthError : UsageResult()
        data class Forbidden(val message: String) : UsageResult()
        object RateLimited : UsageResult()
        data class Transient(val message: String) : UsageResult()
    }

    private data class UsagePayload(
        @SerializedName("rate_limit") val rateLimit: RateLimitDetailsJson?,
        @SerializedName("plan_type") val planType: String?,
        @SerializedName("email") val email: String?,
        @SerializedName("additional_rate_limits") val additionalRateLimits: List<AdditionalLimitJson>?,
    )

    private data class RateLimitDetailsJson(
        @SerializedName("primary_window") val primaryWindow: WindowJson?,
        @SerializedName("secondary_window") val secondaryWindow: WindowJson?,
    )

    private data class AdditionalLimitJson(
        @SerializedName("limit_name") val limitName: String?,
        @SerializedName("metered_feature") val meteredFeature: String?,
        @SerializedName("rate_limit") val rateLimit: RateLimitDetailsJson?,
    ) {
        fun isCodeReview(): Boolean =
            limitName?.contains("review", ignoreCase = true) == true ||
                meteredFeature?.contains("review", ignoreCase = true) == true
    }

    private data class WindowJson(
        @SerializedName("used_percent") val usedPercent: Int?,
        @SerializedName("limit_window_seconds") val limitWindowSeconds: Int?,
        @SerializedName("reset_at") val resetAt: Long?,
    ) {
        fun toSnapshot(): WindowSnapshot = WindowSnapshot(
            usedPercent = usedPercent ?: 0,
            windowSeconds = limitWindowSeconds ?: 0,
            resetAtEpochSeconds = normalizeResetAt(resetAt),
        )
    }

    companion object {
        private const val USAGE_URL = "https://chatgpt.com/backend-api/wham/usage"
        private const val TIMEOUT_SECONDS = 5L
        private const val FIVE_HOUR_SECONDS = 18_000
        private const val WEEKLY_SECONDS = 604_800
        private const val DURATION_TOLERANCE = 0.05
    }
}

/**
 * Interpret a raw `reset_at` as epoch seconds, defensively handling a value
 * that looks like epoch milliseconds.
 *
 * Codex treats `reset_at` as epoch seconds. To stay robust to a future
 * backend change (or a stale value), we clamp: values that look like
 * milliseconds (>= [MILLIS_THRESHOLD]) are divided by 1000; non-positive
 * values become null.
 */
internal fun normalizeResetAt(raw: Long?): Long? {
    if (raw == null || raw <= 0) return null
    return if (raw >= MILLIS_THRESHOLD) raw / 1000 else raw
}

/**
 * Epoch-seconds values are ~1.7e9 in this era; anything at/above ~1e12 is
 * almost certainly epoch milliseconds.
 */
private const val MILLIS_THRESHOLD = 1_000_000_000_000L
