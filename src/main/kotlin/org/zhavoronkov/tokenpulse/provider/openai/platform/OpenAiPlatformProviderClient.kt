package org.zhavoronkov.tokenpulse.provider.openai.platform

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.zhavoronkov.tokenpulse.model.Balance
import org.zhavoronkov.tokenpulse.model.BalanceSnapshot
import org.zhavoronkov.tokenpulse.model.ConnectionType
import org.zhavoronkov.tokenpulse.model.Credits
import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.model.Tokens
import org.zhavoronkov.tokenpulse.provider.ProviderClient
import org.zhavoronkov.tokenpulse.settings.Account
import org.zhavoronkov.tokenpulse.utils.Constants.OPENAI_ADMIN_KEY_PREFIX
import org.zhavoronkov.tokenpulse.utils.TokenPulseLogger
import java.math.BigDecimal
import java.util.concurrent.ThreadLocalRandom

/**
 * Provider client for OpenAI organization-level usage/cost data.
 *
 * ## Authentication
 * **Only Admin API Keys (`sk-admin-...`) are accepted.**
 * Regular project/personal keys (`sk-proj-...`, `sk-...`) do NOT have access to the
 * Organization Usage API or Organization Costs API and will be rejected with an [AuthException].
 *
 * Admin API Keys are created at:
 *   https://platform.openai.com/settings/organization/admin-keys
 * The key must have **Usage API Scope = read** enabled.
 *
 * Legacy OAuth token JSON blobs (backward compatibility) are still auto-detected and handled
 * transparently, but the embedded access token must also be an Admin key.
 *
 * ## Official Endpoints (per OpenAI API reference)
 * - `GET /v1/organization/usage/completions` → token usage data (Admin key required)
 * - `GET /v1/organization/costs` → cost data (Admin key required)
 *
 * ## Data mapping
 * - `credits.used` = sum of `amount.value` from costs API (currency from `amount.currency`)
 * - `tokens.used` = sum of input + output + cached input tokens from usage API
 * - `remaining` is null (OpenAI does not expose remaining balance via public API)
 *
 * ## Resilience
 * - Retries on 429 (rate limit), 5xx (server errors), and transient network errors
 * - Exponential backoff with jitter; no blind retries for 401/403/400
 * - Respects rate-limit headers when available
 */
@Suppress("TooManyFunctions")
class OpenAiPlatformProviderClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val gson: Gson = Gson(),
    private val baseUrl: String = OPENAI_API_BASE_URL,
    internal val maxRetries: Int = MAX_RETRIES,
    internal val initialRetryDelayMs: Long = INITIAL_RETRY_DELAY_MS,
    internal val maxRetryDelayMs: Long = MAX_RETRY_DELAY_MS,
    internal val retryJitterMs: Long = RETRY_JITTER_MS,
    internal val sleepFn: (Long) -> Unit = { delayMs -> Thread.sleep(delayMs) }
) : ProviderClient {

    override fun fetchBalance(account: Account, secret: String): ProviderResult {
        return try {
            val accessToken = resolveAccessToken(secret)

            if (accessToken.isNullOrBlank()) {
                TokenPulseLogger.Provider.warn("OpenAI credentials resolution failed for account ${account.id}")
                return ProviderResult.Failure.AuthError(
                    "OpenAI credentials are missing or invalid. Please reconnect via Settings → Accounts → Edit."
                )
            }

            val normalizedToken = accessToken.removePrefix("Bearer ").trim()
            if (!normalizedToken.startsWith(OPENAI_ADMIN_KEY_PREFIX)) {
                TokenPulseLogger.Provider.warn("OpenAI non-admin key detected for account ${account.id}")
                return ProviderResult.Failure.AuthError(
                    "OpenAI requires an Admin API Key (starts with \"sk-admin-\"). " +
                        "Regular project/personal keys do not have access to the Organization Usage API. " +
                        "Create an Admin key at: https://platform.openai.com/settings/organization/admin-keys " +
                        "(ensure \"Usage API Scope\" is set to read)."
                )
            }

            val usageData = fetchUsageData(accessToken)
            val costData = fetchCostData(accessToken)

            val creditsUsed = costData?.sumOf { it.amount } ?: BigDecimal.ZERO
            val tokensUsed = usageData?.sumOf { it.totalTokens } ?: 0L

            ProviderResult.Success(
                BalanceSnapshot(
                    accountId = account.id,
                    connectionType = ConnectionType.OPENAI_PLATFORM,
                    balance = Balance(
                        credits = Credits(used = creditsUsed),
                        tokens = Tokens(used = tokensUsed)
                    )
                )
            )
        } catch (e: JsonSyntaxException) {
            ProviderResult.Failure.ParseError("Failed to parse OpenAI response", e)
        } catch (e: RateLimitException) {
            ProviderResult.Failure.RateLimited("OpenAI rate limit exceeded")
        } catch (e: AuthException) {
            ProviderResult.Failure.AuthError("OpenAI authentication failed: ${e.message}")
        } catch (e: ServerException) {
            ProviderResult.Failure.UnknownError("OpenAI server error: ${e.message}")
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            ProviderResult.Failure.NetworkError("Failed to connect to OpenAI", e)
        }
    }

    override fun testCredentials(account: Account, secret: String): ProviderResult =
        fetchBalance(account, secret)

    private fun resolveAccessToken(secret: String): String? {
        val trimmed = secret.trim()
        return if (trimmed.startsWith("{")) {
            val tokenData = parseTokenData(secret)
            tokenData?.let { handleOAuthToken(it) }
        } else {
            normalizeApiKey(secret)
        }
    }

    private fun handleOAuthToken(tokenData: TokenData): String? {
        val currentTimestamp = System.currentTimeMillis() / 1000
        return if (tokenData.expiresAt < currentTimestamp) {
            refreshToken(tokenData.refreshToken)?.accessToken
        } else {
            tokenData.accessToken
        }
    }

    private fun normalizeApiKey(apiKey: String): String =
        apiKey.removePrefix("Bearer ")
            .trim()
            .replace(Regex("[\\p{C}\\p{Z}]"), "")

    private fun parseTokenData(secret: String): TokenData? {
        return try {
            val data = gson.fromJson(secret, TokenData::class.java)
            if (data.accessToken.isNullOrBlank() || data.refreshToken.isNullOrBlank()) null else data
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            null
        }
    }

    private fun refreshToken(refreshToken: String): TokenData? {
        val url = "https://api.openai.com/oauth/token"
        val body = "grant_type=refresh_token&refresh_token=${refreshToken.encodeUrl()}"

        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .header("Authorization", "Bearer ${System.getenv("OPENAI_CLIENT_SECRET") ?: ""}")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .build()

        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val responseBody = response.body?.string() ?: return null
            val json = gson.fromJson(responseBody, RefreshResponse::class.java)
            val newExpiresAt = (System.currentTimeMillis() / 1000) + json.expiresIn
            TokenData(
                accessToken = json.accessToken,
                refreshToken = json.refreshToken ?: refreshToken,
                expiresAt = newExpiresAt
            )
        }
    }

    private fun fetchUsageData(accessToken: String): List<UsageEntry>? {
        val url = "$baseUrl/v1/organization/usage/completions"
        val params = buildMap {
            put("bucket_width", "1d")
            put("start_time", calculateStartTime())
            put("end_time", calculateEndTime())
        }
        return fetchPaginatedData(url, params, accessToken, ::parseUsageEntry)
    }

    private fun fetchCostData(accessToken: String): List<CostEntry>? {
        val url = "$baseUrl/v1/organization/costs"
        val params = buildMap {
            put("bucket_width", "1d")
            put("start_time", calculateStartTime())
            put("end_time", calculateEndTime())
        }
        return fetchPaginatedData(url, params, accessToken, ::parseCostEntry)
    }

    private fun calculateStartTime(): String {
        val secondsSinceEpoch = System.currentTimeMillis() / 1000
        return (secondsSinceEpoch - DEFAULT_DAYS_BACK * SECONDS_PER_DAY).toString()
    }

    private fun calculateEndTime(): String = (System.currentTimeMillis() / 1000).toString()

    private fun <T> fetchPaginatedData(
        url: String,
        params: Map<String, String>,
        accessToken: String,
        parseItem: (JsonElement) -> T?
    ): List<T>? {
        val allItems = mutableListOf<T>()
        var pageCursor: String? = null
        var hasMore: Boolean?

        do {
            val requestParams = params.toMutableMap()
            if (pageCursor != null) {
                requestParams["page"] = pageCursor
            }

            val requestUrl = buildRequestUrl(url, requestParams)
            val request = buildRequest(requestUrl, accessToken)

            val (json, nextPage, more) = fetchWithRetryAndPagination(request)

            if (json != null) {
                parseDataArray(json, parseItem, allItems)
                pageCursor = nextPage
                hasMore = more
            } else {
                pageCursor = null
                hasMore = false
            }
        } while (pageCursor != null && (hasMore == true || pageCursor.isNotEmpty()))

        return allItems.ifEmpty { null }
    }

    @Suppress("ThrowsCount")
    private fun fetchWithRetryAndPagination(request: Request): Triple<JsonObject?, String?, Boolean?> {
        var lastResponse: okhttp3.Response? = null
        var lastError: String? = null
        val totalAttempts = 1 + maxRetries

        for (attempt in 1..totalAttempts) {
            try {
                lastResponse = httpClient.newCall(request).execute()

                if (lastResponse.isSuccessful) {
                    logRateLimitHeaders(lastResponse)

                    val responseBody = lastResponse.body?.string() ?: break
                    val json = gson.fromJson(responseBody, JsonObject::class.java)
                    val nextPage = getNextPageCursor(json)
                    val hasMore = getHasMoreFlag(json)
                    return Triple(json, nextPage, hasMore)
                }

                val code = lastResponse.code

                if (isRetryableError(code)) {
                    lastError = "HTTP $code"
                    if (attempt < totalAttempts) {
                        val delayMs = calculateRetryDelayWithHeaders(attempt, lastResponse)
                        val msg = "OpenAI request failed (attempt $attempt/$totalAttempts): " +
                            "$lastError, retrying in ${delayMs}ms"
                        TokenPulseLogger.Provider.debug(msg)
                        sleepFn(delayMs)
                        continue
                    } else {
                        TokenPulseLogger.Provider.warn(
                            "OpenAI request failed after $totalAttempts attempts: $lastError"
                        )
                        when (code) {
                            HTTP_TOO_MANY_REQUESTS -> throw RateLimitException()
                            in HTTP_INTERNAL_ERROR..HTTP_GATEWAY_TIMEOUT -> throw ServerException("HTTP $code")
                            else -> throw ServerException("HTTP $code")
                        }
                    }
                }

                handleErrorResponse(lastResponse)
            } catch (e: AuthException) {
                throw e
            } catch (e: RateLimitException) {
                throw e
            } catch (e: JsonSyntaxException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message ?: "Unknown error"
                if (attempt < totalAttempts) {
                    val delayMs = calculateRetryDelay(attempt)
                    TokenPulseLogger.Provider.debug(
                        "OpenAI request failed (attempt $attempt/$totalAttempts): $lastError, retrying in ${delayMs}ms"
                    )
                    sleepFn(delayMs)
                } else {
                    TokenPulseLogger.Provider.warn(
                        "OpenAI request failed after $totalAttempts attempts: $lastError"
                    )
                    throw e
                }
            }
        }

        TokenPulseLogger.Provider.warn("OpenAI request failed after $totalAttempts attempts: $lastError")
        return Triple(null, null, null)
    }

    private fun getHasMoreFlag(json: JsonObject): Boolean? {
        val hasMoreElement = json.get("has_more")
        return if (hasMoreElement != null && !hasMoreElement.isJsonNull) {
            hasMoreElement.asBoolean
        } else {
            null
        }
    }

    private fun isRetryableError(code: Int): Boolean =
        code == HTTP_TOO_MANY_REQUESTS || code in HTTP_INTERNAL_ERROR..HTTP_GATEWAY_TIMEOUT

    internal fun calculateRetryDelay(attempt: Int): Long {
        val exponentialDelay = initialRetryDelayMs * (1L shl (attempt - 1))
        val jitter = ThreadLocalRandom.current().nextLong(0, retryJitterMs + 1)
        return (exponentialDelay + jitter).coerceAtMost(maxRetryDelayMs)
    }

    internal fun calculateRetryDelayWithHeaders(attempt: Int, response: okhttp3.Response): Long {
        val baseDelay = calculateRetryDelay(attempt)

        response.header("Retry-After")?.let { retryAfter ->
            try {
                val retryAfterSeconds = retryAfter.toLong()
                val retryAfterMs = retryAfterSeconds * 1000
                if (retryAfterMs > 0) {
                    TokenPulseLogger.Provider.debug("Using Retry-After header: ${retryAfterSeconds}s")
                    return retryAfterMs.coerceAtMost(maxRetryDelayMs)
                }
            } catch (e: NumberFormatException) {
                TokenPulseLogger.Provider.warn("Invalid Retry-After header: $retryAfter")
            }
        }

        response.header("x-ratelimit-reset-requests")?.let { resetTime ->
            try {
                val resetTimestamp = resetTime.toLong()
                val now = System.currentTimeMillis() / 1000
                val delaySeconds = resetTimestamp - now
                if (delaySeconds > 0) {
                    val delayMs = delaySeconds * 1000
                    TokenPulseLogger.Provider.debug(
                        "Using x-ratelimit-reset-requests: ${delaySeconds}s remaining"
                    )
                    return delayMs.coerceAtMost(maxRetryDelayMs)
                }
            } catch (e: NumberFormatException) {
                TokenPulseLogger.Provider.warn("Invalid x-ratelimit-reset-requests header: $resetTime")
            }
        }

        return baseDelay
    }

    private fun buildRequestUrl(url: String, params: Map<String, String>): String = buildString {
        append(url)
        append("?")
        var i = 0
        for ((key, value) in params) {
            if (i > 0) append("&")
            append(key)
            append("=")
            append(value.encodeUrl())
            i++
        }
    }

    private fun buildRequest(requestUrl: String, accessToken: String): Request =
        Request.Builder()
            .url(requestUrl)
            .get()
            .header("Authorization", "Bearer $accessToken")
            .header("OpenAI-Beta", "usage=v1")
            .build()

    private fun logRateLimitHeaders(response: okhttp3.Response) {
        val limit = response.header("x-ratelimit-limit-requests")
        val remaining = response.header("x-ratelimit-remaining-requests")
        val reset = response.header("x-ratelimit-reset-requests")
        if (limit != null || remaining != null || reset != null) {
            TokenPulseLogger.Provider.debug(
                "OpenAI rate limit: limit=$limit, remaining=$remaining, reset=$reset"
            )
        }
    }

    @Suppress("ThrowsCount")
    private fun handleErrorResponse(response: okhttp3.Response) {
        val body = response.body?.string() ?: ""
        val errorMessage = try {
            val json = gson.fromJson(body, JsonObject::class.java)
            json.getAsJsonObject("error")?.get("message")?.asString ?: body
        } catch (e: Exception) {
            body
        }

        when (response.code) {
            HTTP_UNAUTHORIZED, HTTP_FORBIDDEN ->
                throw AuthException(errorMessage.ifBlank { "HTTP ${response.code}" })
            HTTP_TOO_MANY_REQUESTS -> throw RateLimitException()
            else -> throw ServerException(errorMessage.ifBlank { "HTTP ${response.code}" })
        }
    }

    private fun <T> parseDataArray(
        json: JsonObject,
        parseItem: (JsonElement) -> T?,
        allItems: MutableList<T>
    ) {
        json.getAsJsonArray("data")?.forEach { element ->
            parseItem(element)?.let { allItems.add(it) }
        }
    }

    private fun getNextPageCursor(json: JsonObject): String? {
        val nextPageElement = json.get("next_page")
        return if (nextPageElement != null && !nextPageElement.isJsonNull) {
            nextPageElement.asString
        } else {
            null
        }
    }

    private fun parseUsageEntry(element: JsonElement): UsageEntry? {
        return try {
            val bucket = element.asJsonObject
            val resultsArray = bucket.getAsJsonArray("results")

            var totalInput = 0L
            var totalOutput = 0L
            var totalCached = 0L
            var totalReasoning = 0L

            resultsArray?.forEach { resultElement ->
                val result = resultElement.asJsonObject
                totalInput += result.getAsLong("input_tokens") ?: 0L
                totalOutput += result.getAsLong("output_tokens") ?: 0L
                totalCached += result.getAsLong("input_cached_tokens") ?: 0L
                totalReasoning += result.getAsLong("reasoning_tokens") ?: 0L
            }

            UsageEntry(
                inputTokens = totalInput,
                outputTokens = totalOutput,
                cachedInputTokens = totalCached,
                reasoningTokens = totalReasoning
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun JsonObject.getAsLong(key: String): Long? =
        get(key)?.let { if (it.isJsonPrimitive) it.asLong else null }

    private fun parseCostEntry(element: JsonElement): CostEntry? {
        return try {
            val bucket = element.asJsonObject
            val resultsArray = bucket.getAsJsonArray("results")

            var totalAmount = BigDecimal.ZERO

            resultsArray?.forEach { resultElement ->
                val result = resultElement.asJsonObject
                result.getAsJsonObject("amount")?.let { amountObj ->
                    val value = amountObj.get("value")
                    if (value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
                        totalAmount = totalAmount.add(value.asBigDecimal)
                    }
                }
            }

            CostEntry(amount = totalAmount)
        } catch (e: Exception) {
            null
        }
    }

    private fun String.encodeUrl(): String = java.net.URLEncoder.encode(
        this,
        java.nio.charset.StandardCharsets.UTF_8.toString()
    )

    data class TokenData(
        val accessToken: String,
        val refreshToken: String,
        val expiresAt: Long
    )

    data class RefreshResponse(
        val accessToken: String,
        val refreshToken: String?,
        val expiresIn: Int
    )

    data class UsageEntry(
        val inputTokens: Long? = null,
        val outputTokens: Long? = null,
        val cachedInputTokens: Long? = null,
        val reasoningTokens: Long? = null
    ) {
        val totalTokens: Long
            get() = (inputTokens ?: 0) + (outputTokens ?: 0) + (cachedInputTokens ?: 0) + (reasoningTokens ?: 0)
    }

    data class CostEntry(val amount: BigDecimal)

    class AuthException(message: String) : Exception(message)
    class ServerException(message: String) : Exception(message)
    class RateLimitException : Exception("Rate limit exceeded")

    companion object {
        private const val OPENAI_API_BASE_URL = "https://api.openai.com"
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_INTERNAL_ERROR = 500
        private const val HTTP_GATEWAY_TIMEOUT = 504
        private const val DEFAULT_DAYS_BACK = 30
        private const val SECONDS_PER_DAY = 86_400

        internal const val MAX_RETRIES = 3
        internal const val INITIAL_RETRY_DELAY_MS = 500L
        internal const val MAX_RETRY_DELAY_MS = 8000L
        internal const val RETRY_JITTER_MS = 500L
    }
}
