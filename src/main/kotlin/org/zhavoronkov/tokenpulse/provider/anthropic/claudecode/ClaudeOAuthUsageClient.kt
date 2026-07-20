package org.zhavoronkov.tokenpulse.provider.anthropic.claudecode

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import org.zhavoronkov.tokenpulse.utils.TokenPulseLogger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
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
class ClaudeOAuthUsageClient {

    private val gson: Gson = GsonBuilder().create()
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
        .build()

    /**
     * Fetch usage data from the OAuth API.
     *
     * @param oauthToken The OAuth token from `claude setup-token`
     * @return Usage data or null if the request fails
     */
    fun fetchUsage(oauthToken: String): OAuthUsageResult {
        if (oauthToken.isBlank()) {
            return OAuthUsageResult.Error("OAuth token is empty")
        }

        TokenPulseLogger.Provider.debug("Fetching usage from OAuth API")

        return try {
            val request = buildRequest(oauthToken)
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            TokenPulseLogger.Provider.debug("OAuth API response: ${response.statusCode()}")

            when (response.statusCode()) {
                200 -> parseSuccessResponse(response.body())
                401 -> OAuthUsageResult.Error("OAuth token expired or invalid", isAuthError = true)
                403 -> OAuthUsageResult.Error("OAuth token lacks required scopes", isAuthError = true)
                429 -> OAuthUsageResult.Error("Rate limited by Anthropic API", isRateLimited = true)
                else -> OAuthUsageResult.Error("API returned ${response.statusCode()}: ${response.body().take(200)}")
            }
        } catch (_: java.net.http.HttpTimeoutException) {
            TokenPulseLogger.Provider.warn("OAuth API request timed out")
            OAuthUsageResult.Error("API request timed out after $REQUEST_TIMEOUT_SECONDS seconds")
        } catch (e: java.net.ConnectException) {
            TokenPulseLogger.Provider.warn("OAuth API connection failed: ${e.message}")
            OAuthUsageResult.Error("Cannot connect to Anthropic API: ${e.message}")
        } catch (e: Exception) {
            TokenPulseLogger.Provider.error("OAuth API request failed", e)
            OAuthUsageResult.Error("API request failed: ${e.message}")
        }
    }

    private fun buildRequest(oauthToken: String): HttpRequest {
        return HttpRequest.newBuilder()
            .uri(URI.create(USAGE_API_URL))
            .header("Authorization", "Bearer $oauthToken")
            .header("anthropic-beta", BETA_HEADER)
            .header("Content-Type", "application/json")
            .header("User-Agent", USER_AGENT)
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
     * Normalize utilization value to 0-100 range.
     * API may return 0.0-1.0 (fraction) or 0-100 (percentage).
     */
    private fun normalizeUtilization(value: Double): Int {
        return if (value <= 1.0) {
            // Value is a fraction (0.0-1.0), convert to percentage
            (value * 100).toInt()
        } else {
            // Value is already a percentage
            value.toInt()
        }
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
        private const val USER_AGENT = "TokenPulse/1.0"
        private const val CONNECT_TIMEOUT_SECONDS = 5L
        private const val REQUEST_TIMEOUT_SECONDS = 5L
    }
}
