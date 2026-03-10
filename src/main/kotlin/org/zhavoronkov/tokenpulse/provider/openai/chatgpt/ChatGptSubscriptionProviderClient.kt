package org.zhavoronkov.tokenpulse.provider.openai.chatgpt

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.zhavoronkov.tokenpulse.model.Balance
import org.zhavoronkov.tokenpulse.model.BalanceSnapshot
import org.zhavoronkov.tokenpulse.model.ConnectionType
import org.zhavoronkov.tokenpulse.model.Credits
import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.model.Tokens
import org.zhavoronkov.tokenpulse.provider.ProviderClient
import org.zhavoronkov.tokenpulse.settings.Account
import org.zhavoronkov.tokenpulse.utils.TokenPulseLogger
import java.math.BigDecimal

/**
 * Provider client for ChatGPT subscription billing (Plus/Pro/Team).
 *
 * Uses Codex app-server JSON-RPC to fetch actual rate limits:
 * - 5-hour usage limit (windowDurationMins = 300)
 * - Weekly usage limit (windowDurationMins = 10080)
 * - Code review limit (weekly bucket with review-related limitId)
 *
 * ## Authentication
 * Uses OAuth PKCE flow via [ChatGptOAuthManager]:
 * - Tokens are obtained via browser OAuth flow
 * - Access token is automatically refreshed
 * - No manual cURL capture needed
 *
 * ## Rate Limits via Codex App-Server
 * The app-server provides actual usage percentages:
 * - `rateLimits.primary.usedPercent` - 5-hour window usage
 * - `rateLimits.secondary` - optional second window
 * - `rateLimitsByLimitId` - all limit buckets mapped by ID
 *
 * ## Credits
 * Credits remaining is NOT available via public API (per deep research).
 * We show "unavailable" for credits.
 */
class ChatGptSubscriptionProviderClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val gson: Gson = Gson(),
    private val baseUrl: String = CHATGPT_BASE_URL,
    private val codexClient: CodexAppServerClient = CodexAppServerClient()
) : ProviderClient {

    override fun fetchBalance(account: Account, secret: String): ProviderResult {
        return try {
            val useCodex = account.chatGptUseCodex ?: false

            val msg = "ChatGPT fetchBalance: accountId=${account.id}, " +
                "chatGptUseCodex=${account.chatGptUseCodex}, useCodex=$useCodex"
            TokenPulseLogger.Provider.info(msg)

            if (!useCodex) {
                TokenPulseLogger.Provider.info("ChatGPT: Codex disabled, using OAuth fallback")
                return fetchViaOAuthWithMetadata(account)
            }

            TokenPulseLogger.Provider.info("ChatGPT: Codex enabled, attempting Codex app-server")

            if (!codexClient.isCodexAvailable()) {
                TokenPulseLogger.Provider.info("Codex CLI not installed, using OAuth fallback")
                return fetchViaOAuthWithMetadata(account, codexAvailable = false, codexError = "not_installed")
            }

            val accountInfo = codexClient.readAccount()

            if (accountInfo == null || !accountInfo.isAuthenticated) {
                TokenPulseLogger.Provider.info("Codex not authenticated, using OAuth fallback")
                return fetchViaOAuthWithMetadata(account, codexAvailable = false, codexError = "not_authenticated")
            }

            val rateLimits = codexClient.readRateLimits()

            if (rateLimits == null) {
                TokenPulseLogger.Provider.warn("Codex available but failed to read rate limits")
                return fetchViaOAuthWithMetadata(account, codexAvailable = true, codexError = "rate_limits_unavailable")
            }

            buildCodexSuccessResult(account, accountInfo, rateLimits)
        } catch (e: JsonSyntaxException) {
            ProviderResult.Failure.ParseError("Failed to parse ChatGPT response", e)
        } catch (e: RateLimitException) {
            ProviderResult.Failure.RateLimited("ChatGPT rate limit exceeded")
        } catch (e: AuthException) {
            ProviderResult.Failure.AuthError("ChatGPT authentication failed: ${e.message}. Please reconnect.")
        } catch (e: CloudFlareException) {
            ProviderResult.Failure.AuthError("CloudFlare protection triggered. Please try again later.")
        } catch (e: ServerException) {
            ProviderResult.Failure.UnknownError("ChatGPT server error: ${e.message}")
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            TokenPulseLogger.Provider.error("ChatGPT fetch error", e)
            ProviderResult.Failure.NetworkError("Failed to connect to ChatGPT", e)
        }
    }

    override fun testCredentials(account: Account, secret: String): ProviderResult =
        fetchBalance(account, secret)

    private fun buildCodexSuccessResult(
        account: Account,
        accountInfo: CodexAppServerClient.AccountInfo,
        rateLimits: CodexAppServerClient.RateLimits
    ): ProviderResult {
        val fiveHourUsed = rateLimits.fiveHourBucket?.usedPercent
        val weeklyUsed = rateLimits.weeklyBucket?.usedPercent
        val codeReviewUsed = rateLimits.codeReviewBucket?.usedPercent

        TokenPulseLogger.Provider.debug(
            "ChatGPT rate limits: 5h=$fiveHourUsed%, weekly=$weeklyUsed%, " +
                "codeReview=$codeReviewUsed%, account=${accountInfo.email}"
        )

        val fiveHourTokens = fiveHourUsed?.let { (it * TOKENS_PER_PERCENT).toLong() }
        val totalUsed = fiveHourTokens ?: 0L
        val totalRemaining = fiveHourUsed?.let { ((100 - it) * TOKENS_PER_PERCENT).toLong() } ?: 0L

        return ProviderResult.Success(
            BalanceSnapshot(
                accountId = account.id,
                connectionType = ConnectionType.CHATGPT_SUBSCRIPTION,
                balance = Balance(
                    credits = Credits(used = null, total = null, remaining = null),
                    tokens = Tokens(used = totalUsed, total = TOTAL_TOKENS, remaining = totalRemaining)
                ),
                metadata = mapOf(
                    "email" to (accountInfo.email ?: "unknown"),
                    "planType" to (accountInfo.planType ?: "unknown"),
                    "fiveHourUsed" to (fiveHourUsed?.toString() ?: "N/A"),
                    "weeklyUsed" to (weeklyUsed?.toString() ?: "N/A"),
                    "codeReviewUsed" to (codeReviewUsed?.toString() ?: "N/A"),
                    "codexAvailable" to "true",
                    "codexEnabled" to "true"
                )
            )
        )
    }

    /**
     * Fallback: Fetch subscription info via OAuth (backend-api/me).
     * Used when Codex app-server is not available or not enabled.
     */
    private fun fetchViaOAuthWithMetadata(
        account: Account,
        codexAvailable: Boolean = false,
        codexError: String? = null
    ): ProviderResult {
        val oauthManager = ChatGptOAuthManager.getInstance()

        if (!oauthManager.isAuthenticated()) {
            return ProviderResult.Failure.AuthError(
                "ChatGPT not connected. Please connect via Settings → Accounts → Edit."
            )
        }

        var accessToken = oauthManager.getAccessToken()

        if (accessToken.isNullOrBlank()) {
            TokenPulseLogger.Provider.info("Initial token retrieval failed, attempting force refresh")
            accessToken = oauthManager.forceRefreshAccessToken()

            if (accessToken.isNullOrBlank()) {
                return if (oauthManager.isAuthenticated()) {
                    ProviderResult.Failure.NetworkError(
                        "ChatGPT authentication temporarily unavailable. Will retry.",
                        null
                    )
                } else {
                    ProviderResult.Failure.AuthError(
                        "ChatGPT session expired. Please reconnect via Settings → Accounts → Edit."
                    )
                }
            }
        }

        val subscriptionInfo = fetchSubscriptionInfoWithRetry(accessToken, oauthManager)

        TokenPulseLogger.Provider.debug(
            "ChatGPT subscription (fallback): plan=${subscriptionInfo?.planType}"
        )

        val monthlyCost = subscriptionInfo?.monthlyCost ?: BigDecimal.ZERO

        val metadata = buildMetadata(oauthManager, account, codexAvailable, codexError, subscriptionInfo)

        return ProviderResult.Success(
            BalanceSnapshot(
                accountId = account.id,
                connectionType = ConnectionType.CHATGPT_SUBSCRIPTION,
                balance = Balance(
                    credits = Credits(used = monthlyCost, total = monthlyCost, remaining = BigDecimal.ZERO),
                    tokens = null
                ),
                metadata = metadata
            )
        )
    }

    private fun fetchSubscriptionInfoWithRetry(
        initialToken: String,
        oauthManager: ChatGptOAuthManager
    ): SubscriptionInfo? {
        var currentToken = initialToken
        var subscriptionInfo: SubscriptionInfo? = null

        for (attempt in 0..1) {
            try {
                subscriptionInfo = fetchSubscriptionInfo(currentToken)
                break
            } catch (e: AuthException) {
                val errorMessage = e.message?.lowercase() ?: ""
                val isAuthFailure = errorMessage.contains("401") ||
                    errorMessage.contains("unauthorized") ||
                    errorMessage.contains("invalid token") ||
                    errorMessage.contains("authentication")

                if (attempt == 0 && isAuthFailure) {
                    TokenPulseLogger.Provider.info(
                        "ChatGPT auth error on attempt $attempt, forcing token refresh for retry"
                    )
                    val refreshed = oauthManager.forceRefreshAccessToken()
                    if (refreshed != null) {
                        currentToken = refreshed
                        continue
                    }
                }

                if (errorMessage.contains("403")) {
                    TokenPulseLogger.Provider.warn(
                        "ChatGPT backend-api/me blocked with 403. Using basic info from token."
                    )
                    break
                }

                if (oauthManager.isAuthenticated()) {
                    TokenPulseLogger.Provider.warn(
                        "ChatGPT API error but credentials still valid: ${e.message}"
                    )
                }
            } catch (e: Exception) {
                TokenPulseLogger.Provider.warn("Unexpected error fetching ChatGPT info: ${e.message}")
            }
        }

        return subscriptionInfo
    }

    private fun buildMetadata(
        oauthManager: ChatGptOAuthManager,
        account: Account,
        codexAvailable: Boolean,
        codexError: String?,
        subscriptionInfo: SubscriptionInfo?
    ): Map<String, String> {
        val metadata = mutableMapOf(
            "email" to (oauthManager.getEmail() ?: "unknown"),
            "planType" to (subscriptionInfo?.planType ?: "unknown"),
            "codexAvailable" to codexAvailable.toString(),
            "codexEnabled" to (account.chatGptUseCodex ?: false).toString()
        )
        codexError?.let { metadata["codexError"] = it }
        return metadata
    }

    private fun fetchSubscriptionInfo(accessToken: String): SubscriptionInfo? {
        val url = "$baseUrl/backend-api/me"

        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", "https://chatgpt.com/")
            .build()

        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                handleErrorResponse(response)
            }

            val responseBody = response.body?.string() ?: return null

            if (responseBody.contains("Just a moment...") ||
                responseBody.contains("cf-browser-verification")
            ) {
                throw CloudFlareException("CloudFlare challenge detected")
            }

            parseSubscriptionResponse(responseBody)
        }
    }

    private fun parseSubscriptionResponse(responseBody: String): SubscriptionInfo? {
        return try {
            val json = gson.fromJson(responseBody, JsonObject::class.java)
            val planType = extractPlanType(json)
            val monthlyCost = PLAN_PRICES[planType?.lowercase()] ?: BigDecimal.ZERO

            SubscriptionInfo(
                planType = planType,
                monthlyCost = monthlyCost,
                isActive = true
            )
        } catch (e: Exception) {
            TokenPulseLogger.Provider.warn("Failed to parse ChatGPT subscription response", e)
            null
        }
    }

    private fun extractPlanType(json: JsonObject): String? {
        // Try direct "plan" field
        json.get("plan")?.let { plan ->
            when {
                plan.isJsonObject -> {
                    plan.asJsonObject.get("type")?.asString?.let { return it }
                    plan.asJsonObject.get("name")?.asString?.let { return it }
                }
                plan.isJsonPrimitive -> return plan.asString
                else -> { /* Not a recognized plan format */ }
            }
        }

        // Try "accounts" array
        json.getAsJsonArray("accounts")?.takeIf { it.size() > 0 }?.let { accounts ->
            val firstAccount = accounts[0].asJsonObject
            firstAccount.get("plan_type")?.asString?.let { return it }
            firstAccount.getAsJsonObject("entitlement")?.get("subscription_plan")?.asString?.let { return it }
        }

        // Try nested "entitlements"
        json.getAsJsonObject("entitlement")?.let { entitlement ->
            entitlement.get("subscription_plan")?.asString?.let { return it }
            entitlement.get("plan_type")?.asString?.let { return it }
        }

        // Check for paid features flag
        val hasPaidFeatures = json.get("has_paid_features")?.asBoolean ?: false
        return if (hasPaidFeatures) "plus" else "free"
    }

    @Suppress("ThrowsCount")
    private fun handleErrorResponse(response: okhttp3.Response): Nothing {
        val body = response.body?.string() ?: ""

        if (body.contains("Just a moment...") || body.contains("cf-browser-verification")) {
            throw CloudFlareException("CloudFlare challenge detected")
        }

        when (response.code) {
            HTTP_UNAUTHORIZED, HTTP_FORBIDDEN ->
                throw AuthException("Session expired or invalid (HTTP ${response.code})")
            HTTP_TOO_MANY_REQUESTS -> throw RateLimitException()
            else -> throw ServerException("HTTP ${response.code}")
        }
    }

    data class SubscriptionInfo(
        val planType: String?,
        val monthlyCost: BigDecimal,
        val isActive: Boolean
    )

    class AuthException(message: String) : Exception(message)
    class ServerException(message: String) : Exception(message)
    class RateLimitException : Exception("Rate limit exceeded")
    class CloudFlareException(message: String) : Exception(message)

    companion object {
        private const val CHATGPT_BASE_URL = "https://chatgpt.com"
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_TOO_MANY_REQUESTS = 429

        private const val TOKENS_PER_PERCENT = 1000
        private const val TOTAL_TOKENS = 100_000L

        private const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private val PLAN_PRICES = mapOf(
            "chatgptplusplan" to BigDecimal("20.00"),
            "plus" to BigDecimal("20.00"),
            "pro" to BigDecimal("200.00"),
            "team" to BigDecimal("30.00"),
            "enterprise" to BigDecimal("0.00"),
            "free" to BigDecimal("0.00")
        )
    }
}
