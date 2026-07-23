package org.zhavoronkov.tokenpulse.provider.anthropic.claudecode

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import org.zhavoronkov.tokenpulse.provider.oauth.AbstractOAuthUsageClient
import org.zhavoronkov.tokenpulse.provider.oauth.OAUTH_BODY_PREVIEW
import org.zhavoronkov.tokenpulse.provider.oauth.OAUTH_USER_AGENT
import org.zhavoronkov.tokenpulse.utils.TokenPulseLogger
import java.net.URI
import java.net.http.HttpRequest
import java.time.Duration

/**
 * HTTP client for Claude Code's undocumented OAuth usage API.
 *
 * This client calls the internal API endpoint that Claude Code uses to fetch
 * subscription usage data. The API is undocumented and may change without notice.
 *
 * ## API Details
 * - Endpoint: GET https://api.anthropic.com/api/oauth/usage
 * - Auth: Bearer token (OAuth)
 * - Beta header: oauth-2025-04-20
 * - Timeout: 5 seconds (matching Claude Code's implementation)
 *
 * ## Token Generation
 * Users generate a long-lived token via: `claude setup-token`
 * This is a documented Anthropic workflow for CI/scripts.
 */
class ClaudeOAuthUsageClient(
    /**
     * Usage endpoint URL. Defaults to the production Anthropic endpoint;
     * tests override it to point at a local HTTP stub server.
     */
    private val usageUrl: String = USAGE_API_URL,
) : AbstractOAuthUsageClient<ClaudeOAuthUsageClient.OAuthUsageResult>(
    connectSeconds = CONNECT_TIMEOUT_SECONDS,
    logTag = "ClaudeOAuthUsageClient",
) {

    private val gson: Gson = GsonBuilder().create()

    /**
     * Fetch usage data from the OAuth API.
     *
     * @param oauthToken The OAuth token from `claude setup-token`
     * @return Usage data or null if the request fails
     */
    fun fetchUsage(oauthToken: String): OAuthUsageResult {
        if (oauthToken.isBlank()) return OAuthUsageResult.Error("OAuth token is empty")
        return execute(buildRequest(oauthToken))
    }

    override fun transient(message: String): OAuthUsageResult = OAuthUsageResult.Error(message)

    override fun mapStatus(status: Int, body: String): OAuthUsageResult = when (status) {
        200 -> parseSuccessResponse(body)
        401 -> OAuthUsageResult.Error("OAuth token expired or invalid", isAuthError = true)
        // 403 is NOT an auth/session error: it typically means org/geo
        // policy, a rejected beta header, or a WAF block — the OAuth
        // token itself is still valid. Surfacing it as "session
        // expired" would wrongly tell a logged-in user to re-auth.
        403 -> OAuthUsageResult.Error(
            "Claude API access forbidden (403). Check your account/organization access."
        )
        429 -> OAuthUsageResult.Error("Rate limited by Anthropic API", isRateLimited = true)
        else -> OAuthUsageResult.Error("API returned $status: ${body.take(OAUTH_BODY_PREVIEW)}")
    }

    private fun buildRequest(oauthToken: String): HttpRequest {
        return HttpRequest.newBuilder()
            .uri(URI.create(usageUrl))
            .header("Authorization", "Bearer $oauthToken")
            .header("anthropic-beta", BETA_HEADER)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .header("Content-Type", "application/json")
            .header("User-Agent", OAUTH_USER_AGENT)
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .GET()
            .build()
    }

    private fun parseSuccessResponse(body: String): OAuthUsageResult {
        return try {
            val response = gson.fromJson(body, ClaudeOAuthUsageResponse::class.java)

            if (response == null) {
                return OAuthUsageResult.Error("Empty response from API")
            }

            // Diagnostic: log RAW utilization Doubles (pre-normalization) for every window,
            // including the seven_day sub-windows. Lets us verify unit assumptions per account
            // when things look off (e.g. seven_day=1.0 arriving as 1% vs 100% ambiguity).
            TokenPulseLogger.Provider.debug(
                "Raw OAuth utilization: five_hour=${response.fiveHour?.utilization}, " +
                    "seven_day=${response.sevenDay?.utilization}, " +
                    "seven_day_opus=${response.sevenDayOpus?.utilization}, " +
                    "seven_day_sonnet=${response.sevenDaySonnet?.utilization}, " +
                    "seven_day_oauth_apps=${response.sevenDayOauthApps?.utilization}"
            )

            // Convert API response to internal usage-data format
            val usageData = ClaudeUsageData(
                sessionUsedPercent = response.fiveHour?.utilization?.let { normalizeUtilization(it) },
                sessionResetsAt = response.fiveHour?.resetsAt,
                weekUsedPercent = response.sevenDay?.utilization?.let { normalizeUtilization(it) },
                weekResetsAt = response.sevenDay?.resetsAt
            )

            TokenPulseLogger.Provider.debug(
                "Parsed OAuth usage: session=${usageData.sessionUsedPercent}%, week=${usageData.weekUsedPercent}%"
            )

            OAuthUsageResult.Success(usageData)
        } catch (e: JsonSyntaxException) {
            TokenPulseLogger.Provider.warn("Failed to parse OAuth API response: ${e.message}")
            OAuthUsageResult.Error("Invalid response format from API")
        }
    }

    /**
     * Normalize an API utilization value to an integer percent 0..100.
     *
     * The undocumented Anthropic usage API mixes units across windows even within a single
     * response (see `ClaudeOAuthUsageClientTest`: `0.25` → 25 and `10` → 10 in the same payload).
     * We discriminate per-value at the 1.0 boundary:
     *   - value STRICTLY `< 1.0` → treat as fraction (`0.08` → 8, `0.84` → 84).
     *   - value `>= 1.0`        → treat as already-percentage (`1.0` → 1, `10` → 10, `100.0` → 100).
     *
     * The exact value `1.0` is genuinely ambiguous ("100% as fraction" vs "1% as percentage").
     * Observed data (see raw-utilization log above) shows 100% arrives as `100.0`, not `1.0`, so
     * mapping `1.0 → 1` is the correct choice for real accounts. If a future account ever ships
     * a fraction-mode 5-hour at exactly `1.0` (=100%), the raw-utilization log will show it and
     * we can revisit this rule with evidence instead of guesses.
     *
     * Uses `Math.round` (not truncating `toInt()`) to avoid off-by-one floors, then clamps to
     * `0..100` so any out-of-range API values render safely.
     */
    private fun normalizeUtilization(value: Double): Int {
        val pct = if (value < 1.0) value * 100 else value
        return Math.round(pct).toInt().coerceIn(0, 100)
    }

    /**
     * Result of an OAuth API call.
     */
    sealed class OAuthUsageResult {
        data class Success(val usageData: ClaudeUsageData) : OAuthUsageResult()
        data class Error(
            val message: String,
            val isAuthError: Boolean = false,
            val isRateLimited: Boolean = false,
        ) : OAuthUsageResult()
    }

    companion object {
        private const val USAGE_API_URL = "https://api.anthropic.com/api/oauth/usage"
        private const val BETA_HEADER = "oauth-2025-04-20"
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private const val CONNECT_TIMEOUT_SECONDS = 5L
        private const val REQUEST_TIMEOUT_SECONDS = 5L
    }
}
