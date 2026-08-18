package org.zhavoronkov.tokenpulse.provider.github

import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.zhavoronkov.tokenpulse.model.Balance
import org.zhavoronkov.tokenpulse.model.BalanceSnapshot
import org.zhavoronkov.tokenpulse.model.ConnectionType
import org.zhavoronkov.tokenpulse.model.Credits
import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.provider.ProviderClient
import org.zhavoronkov.tokenpulse.settings.Account
import java.math.BigDecimal
import java.time.Instant
import java.time.YearMonth

/**
 * Provider client for GitHub Copilot **personal** billing (per-user).
 *
 * ## Endpoints
 * - `GET https://api.github.com/users/{username}/settings/billing/premium_request/usage`
 * - `GET https://api.github.com/users/{username}/settings/billing/ai_credit/usage`
 *
 * ## Auth
 * Personal Access Token with billing read permission. Sent as:
 * `Authorization: Bearer <pat>`
 *
 * ## Balance Representation
 * GitHub's per-user endpoints report **spend/usage**, not a remaining balance
 * (only org-scoped Budgets carry `budget_amount`). The headline metric is
 * premium_request net spend for the current period; AI-credit net spend is
 * exposed via metadata for the tooltip.
 *
 * ## Error Handling
 * - 401/403 → AuthError (invalid PAT or missing scope)
 * - 429     → RateLimited (includes any `Retry-After` seconds when present)
 * - 5xx     → NetworkError
 * - other   → NetworkError
 */
class GitHubCopilotProviderClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val gson: Gson = Gson(),
    private val baseUrl: String = GITHUB_API_BASE_URL
) : ProviderClient {

    override fun fetchBalance(account: Account, secret: String): ProviderResult {
        val parsed = GitHubSecrets.parseCopilot(secret)
            ?: return ProviderResult.Failure.AuthError(
                "GitHub Copilot secret is missing or malformed. Reconnect the account."
            )
        return try {
            val premium = fetchUsage(parsed, PRODUCT_PREMIUM_REQUEST)
            if (premium is UsageResponse.Failure) return premium.toFailure()
            val aiCredit = fetchUsage(parsed, PRODUCT_AI_CREDIT)
            // AI-credit failure is not fatal: the headline is premium_request.
            // If it fails we still return the primary snapshot but omit the
            // ai-credit metadata so the tooltip shows "n/a" rather than a lie.
            val premiumSuccess = premium as UsageResponse.Success
            val aiCreditSuccess = (aiCredit as? UsageResponse.Success)

            val metadata = buildMetadata(premiumSuccess, aiCreditSuccess, parsed)

            ProviderResult.Success(
                BalanceSnapshot(
                    accountId = account.id,
                    connectionType = ConnectionType.GITHUB_COPILOT_PAT,
                    balance = Balance(
                        credits = Credits(used = premiumSuccess.netAmount)
                    ),
                    timestamp = Instant.now(),
                    metadata = metadata
                )
            )
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            ProviderResult.Failure.NetworkError("Failed to connect to GitHub", e)
        }
    }

    override fun testCredentials(account: Account, secret: String): ProviderResult {
        val parsed = GitHubSecrets.parseCopilot(secret)
            ?: return ProviderResult.Failure.AuthError(
                "GitHub Copilot secret is missing or malformed."
            )
        return when (val response = fetchUsage(parsed, PRODUCT_PREMIUM_REQUEST)) {
            is UsageResponse.Success -> ProviderResult.Success(
                BalanceSnapshot(
                    accountId = "test",
                    connectionType = ConnectionType.GITHUB_COPILOT_PAT,
                    balance = Balance(),
                    timestamp = Instant.now()
                )
            )
            is UsageResponse.Failure -> response.toFailure()
        }
    }

    private fun buildMetadata(
        premium: UsageResponse.Success,
        aiCredit: UsageResponse.Success?,
        parsed: GitHubCopilotSecret
    ): Map<String, String> {
        val period = YearMonth.now().toString()
        val base = mapOf(
            "currency" to "USD",
            "period" to period,
            "username" to parsed.username,
            "premiumRequestsUsed" to premium.netAmount.toPlainString(),
            "premiumRequestItems" to premium.itemCount.toString()
        )
        return if (aiCredit != null) {
            base + mapOf(
                "aiCreditsUsed" to aiCredit.netAmount.toPlainString(),
                "aiCreditItems" to aiCredit.itemCount.toString()
            )
        } else {
            base + mapOf("aiCreditsUsed" to "unavailable")
        }
    }

    private fun fetchUsage(secret: GitHubCopilotSecret, product: String): UsageResponse {
        val url = "$baseUrl/users/${secret.username}/settings/billing/$product/usage"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${secret.pat}")
            .header("Accept", GITHUB_ACCEPT)
            .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
            .build()

        return httpClient.newCall(request).execute().use { response ->
            val code = response.code
            when {
                response.isSuccessful -> parseUsageBody(response)
                code == HTTP_UNAUTHORIZED || code == HTTP_FORBIDDEN ->
                    UsageResponse.Failure.Auth(
                        "GitHub rejected the PAT (HTTP $code). " +
                            "Ensure the token has billing read permission."
                    )
                code == HTTP_TOO_MANY_REQUESTS ->
                    UsageResponse.Failure.RateLimited(rateLimitMessage(response))
                code >= HTTP_INTERNAL_ERROR ->
                    UsageResponse.Failure.Network("GitHub API server error: $code")
                else ->
                    UsageResponse.Failure.Network("GitHub API error: $code")
            }
        }
    }

    private fun parseUsageBody(response: Response): UsageResponse {
        val body = response.body?.string()
            ?: return UsageResponse.Failure.Network("Empty response body")
        return try {
            val json = gson.fromJson(body, JsonObject::class.java)
                ?: return UsageResponse.Failure.Parse("GitHub returned empty JSON", null)
            val items = json.getAsJsonArray("usageItems")
            if (items == null) {
                // Empty period is a valid state — no items means $0 usage.
                return UsageResponse.Success(BigDecimal.ZERO, 0)
            }
            var netTotal = BigDecimal.ZERO
            var count = 0
            items.forEach { element ->
                if (!element.isJsonObject) return@forEach
                val item = element.asJsonObject
                val net = item.get("netAmount")?.takeIf { it.isJsonPrimitive }
                    ?.asBigDecimal
                    ?: BigDecimal.ZERO
                netTotal = netTotal.add(net)
                count += 1
            }
            UsageResponse.Success(netTotal, count)
        } catch (e: Exception) {
            UsageResponse.Failure.Parse("Failed to parse GitHub usage response", e)
        }
    }

    private fun rateLimitMessage(response: Response): String {
        val retryAfter = response.header("Retry-After")?.trim()
        return if (!retryAfter.isNullOrEmpty()) {
            "GitHub API rate limit exceeded. Retry after ${retryAfter}s."
        } else {
            "GitHub API rate limit exceeded"
        }
    }

    internal sealed class UsageResponse {
        data class Success(val netAmount: BigDecimal, val itemCount: Int) : UsageResponse()
        sealed class Failure : UsageResponse() {
            abstract fun toFailure(): ProviderResult.Failure
            data class Auth(val message: String) : Failure() {
                override fun toFailure() = ProviderResult.Failure.AuthError(message)
            }
            data class RateLimited(val message: String) : Failure() {
                override fun toFailure() = ProviderResult.Failure.RateLimited(message)
            }
            data class Network(val message: String) : Failure() {
                override fun toFailure() = ProviderResult.Failure.NetworkError(message)
            }
            data class Parse(val message: String, val cause: Throwable?) : Failure() {
                override fun toFailure() = ProviderResult.Failure.ParseError(message, cause)
            }
        }
    }

    companion object {
        const val GITHUB_API_BASE_URL = "https://api.github.com"
        const val GITHUB_ACCEPT = "application/vnd.github+json"
        const val GITHUB_API_VERSION = "2026-03-10"
        private const val PRODUCT_PREMIUM_REQUEST = "premium_request"
        private const val PRODUCT_AI_CREDIT = "ai_credit"
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_INTERNAL_ERROR = 500
    }
}
