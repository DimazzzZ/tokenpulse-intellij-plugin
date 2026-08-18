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

/**
 * Provider client for GitHub Copilot **organization** billing (budgets).
 *
 * ## Endpoint
 * - `GET https://api.github.com/organizations/{org}/settings/billing/budgets`
 *
 * ## Auth
 * Personal Access Token belonging to an org admin / billing manager. Sent as
 * `Authorization: Bearer <pat>`.
 *
 * ## Balance Representation
 * Unlike the per-user usage endpoints, org budgets carry both a limit
 * (`budget_amount`) and consumption (`consumed_amount`), so this client can
 * report a real remaining figure. It sums the Copilot-relevant budgets
 * (`ai_credits`, `premium_requests`) and computes
 * `remaining = total - used`.
 *
 * ## Error Handling
 * Mirrors [GitHubCopilotProviderClient]: 401/403 → AuthError, 429 → RateLimited,
 * 5xx → NetworkError, parse failures → ParseError.
 */
class GitHubCopilotBudgetProviderClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val gson: Gson = Gson(),
    private val baseUrl: String = GitHubCopilotProviderClient.GITHUB_API_BASE_URL
) : ProviderClient {

    override fun fetchBalance(account: Account, secret: String): ProviderResult {
        val parsed = GitHubSecrets.parseOrgBudget(secret)
            ?: return ProviderResult.Failure.AuthError(
                "GitHub Copilot org secret is missing or malformed. Reconnect the account."
            )
        return try {
            when (val response = fetchBudgets(parsed)) {
                is BudgetResponse.Success -> ProviderResult.Success(
                    BalanceSnapshot(
                        accountId = account.id,
                        connectionType = ConnectionType.GITHUB_COPILOT_ORG_BUDGET,
                        balance = Balance(
                            credits = Credits(
                                total = response.totalBudget,
                                used = response.totalConsumed,
                                remaining = response.totalBudget.subtract(response.totalConsumed)
                            )
                        ),
                        timestamp = Instant.now(),
                        metadata = mapOf(
                            "currency" to "USD",
                            "org" to parsed.org,
                            "budgetCount" to response.budgetCount.toString(),
                            "skus" to response.skus.joinToString(",")
                        )
                    )
                )
                is BudgetResponse.Failure -> response.toFailure()
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            ProviderResult.Failure.NetworkError("Failed to connect to GitHub", e)
        }
    }

    override fun testCredentials(account: Account, secret: String): ProviderResult {
        val parsed = GitHubSecrets.parseOrgBudget(secret)
            ?: return ProviderResult.Failure.AuthError("GitHub Copilot org secret is missing or malformed.")
        return when (val response = fetchBudgets(parsed)) {
            is BudgetResponse.Success -> ProviderResult.Success(
                BalanceSnapshot(
                    accountId = "test",
                    connectionType = ConnectionType.GITHUB_COPILOT_ORG_BUDGET,
                    balance = Balance(),
                    timestamp = Instant.now()
                )
            )
            is BudgetResponse.Failure -> response.toFailure()
        }
    }

    private fun fetchBudgets(secret: GitHubOrgBudgetSecret): BudgetResponse {
        var totalBudget = BigDecimal.ZERO
        var totalConsumed = BigDecimal.ZERO
        var budgetCount = 0
        val skus = linkedSetOf<String>()
        var url: String? =
            "$baseUrl/organizations/${secret.org}/settings/billing/budgets?per_page=$PER_PAGE"

        while (url != null) {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${secret.pat}")
                .header("Accept", GitHubCopilotProviderClient.GITHUB_ACCEPT)
                .header("X-GitHub-Api-Version", GitHubCopilotProviderClient.GITHUB_API_VERSION)
                .build()

            val pageResult = httpClient.newCall(request).execute().use { response ->
                val code = response.code
                when {
                    response.isSuccessful -> parsePage(response)
                    code == HTTP_UNAUTHORIZED || code == HTTP_FORBIDDEN ->
                        PageResult.Fail(
                            BudgetResponse.Failure.Auth(
                                "GitHub rejected the PAT (HTTP $code). An org admin/billing-manager " +
                                    "token is required for budget access."
                            )
                        )
                    code == HTTP_TOO_MANY_REQUESTS ->
                        PageResult.Fail(BudgetResponse.Failure.RateLimited(rateLimitMessage(response)))
                    code >= HTTP_INTERNAL_ERROR ->
                        PageResult.Fail(BudgetResponse.Failure.Network("GitHub API server error: $code"))
                    else ->
                        PageResult.Fail(BudgetResponse.Failure.Network("GitHub API error: $code"))
                }
            }

            when (pageResult) {
                is PageResult.Fail -> return pageResult.failure
                is PageResult.Ok -> {
                    totalBudget = totalBudget.add(pageResult.budget)
                    totalConsumed = totalConsumed.add(pageResult.consumed)
                    budgetCount += pageResult.count
                    skus.addAll(pageResult.skus)
                    url = pageResult.nextUrl
                }
            }
        }

        return BudgetResponse.Success(totalBudget, totalConsumed, budgetCount, skus.toList())
    }

    private fun parsePage(response: Response): PageResult {
        val body = response.body?.string()
            ?: return PageResult.Fail(BudgetResponse.Failure.Network("Empty response body"))
        return try {
            val json = gson.fromJson(body, JsonObject::class.java)
                ?: return PageResult.Fail(BudgetResponse.Failure.Parse("GitHub returned empty JSON", null))
            val budgets = json.getAsJsonArray("budgets")
            var budget = BigDecimal.ZERO
            var consumed = BigDecimal.ZERO
            var count = 0
            val skus = linkedSetOf<String>()
            budgets?.forEach { element ->
                if (!element.isJsonObject) return@forEach
                val item = element.asJsonObject
                val sku = item.get("budget_product_sku")?.takeIf { it.isJsonPrimitive }?.asString
                if (sku != null && sku !in COPILOT_SKUS) return@forEach
                budget = budget.add(item.bigDecimal("budget_amount"))
                consumed = consumed.add(item.bigDecimal("consumed_amount"))
                if (sku != null) skus.add(sku)
                count += 1
            }
            val nextUrl = nextPageUrl(response)
            PageResult.Ok(budget, consumed, count, skus, nextUrl)
        } catch (e: Exception) {
            PageResult.Fail(BudgetResponse.Failure.Parse("Failed to parse GitHub budgets response", e))
        }
    }

    /**
     * Extracts the `rel="next"` URL from the RFC 5988 `Link` header GitHub
     * uses for pagination. Returns null when there is no next page.
     */
    private fun nextPageUrl(response: Response): String? {
        val link = response.header("Link") ?: return null
        return link.split(",").firstNotNullOfOrNull { part ->
            val segments = part.split(";")
            if (segments.size < 2) return@firstNotNullOfOrNull null
            val rel = segments.drop(1).any { it.trim() == "rel=\"next\"" }
            if (!rel) return@firstNotNullOfOrNull null
            segments.first().trim().removePrefix("<").removeSuffix(">").takeIf { it.isNotEmpty() }
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

    private fun JsonObject.bigDecimal(key: String): BigDecimal =
        get(key)?.takeIf { it.isJsonPrimitive }?.asBigDecimal ?: BigDecimal.ZERO

    private sealed class PageResult {
        data class Ok(
            val budget: BigDecimal,
            val consumed: BigDecimal,
            val count: Int,
            val skus: Set<String>,
            val nextUrl: String?
        ) : PageResult()
        data class Fail(val failure: BudgetResponse.Failure) : PageResult()
    }

    private sealed class BudgetResponse {
        data class Success(
            val totalBudget: BigDecimal,
            val totalConsumed: BigDecimal,
            val budgetCount: Int,
            val skus: List<String>
        ) : BudgetResponse()
        sealed class Failure : BudgetResponse() {
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
        private const val PER_PAGE = 100
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_INTERNAL_ERROR = 500
        private val COPILOT_SKUS = setOf("ai_credits", "premium_requests")
    }
}
