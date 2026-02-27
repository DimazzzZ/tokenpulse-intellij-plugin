package org.zhavoronkov.tokenpulse.provider

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
import org.zhavoronkov.tokenpulse.model.Credits
import org.zhavoronkov.tokenpulse.model.ProviderId
import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.model.Tokens
import org.zhavoronkov.tokenpulse.settings.Account
import java.math.BigDecimal

/**
 * Provider client for OpenAI personal OAuth access to usage/cost data.
 *
 * This client uses the OpenAI Usage API and Cost API to fetch personal account usage data.
 * The stored secret is a JSON blob produced by [OpenAiConnectDialog]:
 * ```json
 * {
 *   "accessToken": "Bearer eyJ...",
 *   "refreshToken": "refresh_...",
 *   "expiresAt": 1234567890
 * }
 * ```
 *
 * ## Endpoints
 * - `GET /v1/organization/usage/completions` → token usage data
 * - `GET /v1/organization/costs` → cost data
 *
 * ## Data mapping
 * - `credits.used` = sum of `amount.value` from costs API
 * - `tokens.used` = sum of input + output tokens from usage API
 * - `remaining` is null when not available (usage-only provider)
 */
class OpenAiCodexUsageProviderClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val gson: Gson = Gson(),
    private val baseUrl: String = "https://api.openai.com"
) : ProviderClient {

    companion object {
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val DEFAULT_DAYS_BACK = 30
    }

    override fun fetchBalance(account: Account, secret: String): ProviderResult {
        return try {
            val tokenData = parseTokenData(secret)
            if (tokenData == null) {
                return ProviderResult.Failure.AuthError(
                    "OpenAI OAuth token is missing or invalid. Please reconnect via Settings → Accounts → Edit."
                )
            }

            // Check if token is expired and try to refresh
            val currentToken = if (tokenData.expiresAt < System.currentTimeMillis() / 1000) {
                val refreshed = refreshToken(tokenData.refreshToken)
                if (refreshed == null) {
                    return ProviderResult.Failure.AuthError(
                        "OpenAI OAuth token expired and refresh failed. Please reconnect."
                    )
                }
                refreshed
            } else {
                tokenData
            }

            // Fetch usage and cost data
            val usageData = fetchUsageData(currentToken.accessToken)
            val costData = fetchCostData(currentToken.accessToken)

            val creditsUsed = costData?.sumOf { it.amount } ?: BigDecimal.ZERO
            val tokensUsed = usageData?.sumOf { it.totalTokens } ?: 0L

            ProviderResult.Success(
                BalanceSnapshot(
                    accountId = account.id,
                    providerId = ProviderId.OPENAI,
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

    private fun parseTokenData(secret: String): TokenData? {
        return try {
            val data = gson.fromJson(secret, TokenData::class.java)
            if (data.accessToken.isNullOrBlank() || data.refreshToken.isNullOrBlank()) {
                null
            } else {
                data
            }
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
            if (!response.isSuccessful) {
                return null
            }
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
        val url = baseUrl + "/v1/organization/usage/completions"
        val params = buildMap {
            put("bucket_width", "1d")
            put("start_time", calculateStartTime())
            put("end_time", calculateEndTime())
        }

        return fetchPaginatedData(url, params, accessToken, ::parseUsageEntry)
    }

    private fun fetchCostData(accessToken: String): List<CostEntry>? {
        val url = baseUrl + "/v1/organization/costs"
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

    private fun calculateEndTime(): String {
        return (System.currentTimeMillis() / 1000).toString()
    }

    companion object {
        private const val SECONDS_PER_DAY = 86400
    }

    private inline fun <T> fetchPaginatedData(
        url: String,
        params: Map<String, String>,
        accessToken: String,
        parseItem: (JsonElement) -> T?
    ): List<T>? {
        val allItems = mutableListOf<T>()
        var pageCursor: String? = null

        while (true) {
            val requestParams = params.toMutableMap()
            if (pageCursor != null) {
                requestParams["page"] = pageCursor
            }

            val requestUrl = buildRequestUrl(url, requestParams)
            val request = buildRequest(requestUrl, accessToken)

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    handleErrorResponse(response)
                }

                val responseBody = response.body?.string() ?: return@use
                val json = gson.fromJson(responseBody, JsonObject::class.java)

                parseDataArray(json, parseItem, allItems)

                pageCursor = getNextPageCursor(json)
                if (pageCursor == null) return@use
            }
        }

        return allItems.ifEmpty { null }
    }

    private fun buildRequestUrl(url: String, params: Map<String, String>): String {
        return buildString {
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
    }

    private fun buildRequest(requestUrl: String, accessToken: String): Request {
        return Request.Builder()
            .url(requestUrl)
            .get()
            .header("Authorization", "Bearer $accessToken")
            .header("OpenAI-Beta", "usage=v1")
            .build()
    }

    private fun handleErrorResponse(response: okhttp3.Response) {
        when (response.code) {
            HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> throw AuthException("HTTP ${response.code}")
            HTTP_TOO_MANY_REQUESTS -> throw RateLimitException()
            else -> throw ServerException("HTTP ${response.code}")
        }
    }

    private fun <T> parseDataArray(
        json: JsonObject,
        parseItem: (JsonElement) -> T?,
        allItems: MutableList<T>
    ) {
        val dataArray = json.getAsJsonArray("data")
        if (dataArray != null) {
            for (element in dataArray) {
                val item = parseItem(element)
                if (item != null) {
                    allItems.add(item)
                }
            }
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
            val obj = element.asJsonObject
            UsageEntry(
                inputTokens = obj.getAsLong("inputTokens"),
                outputTokens = obj.getAsLong("outputTokens"),
                cachedInputTokens = obj.getAsLong("cachedInputTokens"),
                reasoningTokens = obj.getAsLong("reasoningTokens")
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun JsonObject.getAsLong(key: String): Long? {
        return get(key)?.let { if (it.isJsonPrimitive) it.asLong else null }
    }

    private fun parseCostEntry(element: JsonElement): CostEntry? {
        return try {
            val obj = element.asJsonObject
            val amountElement = obj.get("amount")
            if (amountElement != null) {
                CostEntry(
                    amount = parseAmount(amountElement)
                )
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseAmount(amountElement: JsonElement): BigDecimal {
        return if (amountElement.isJsonPrimitive && amountElement.asJsonPrimitive.isNumber) {
            amountElement.asBigDecimal
        } else {
            BigDecimal.ZERO
        }
    }

    private fun String.encodeUrl(): String = java.net.URLEncoder.encode(
        this,
        java.nio.charset.StandardCharsets.UTF_8.toString()
    )

    // ── JSON models ────────────────────────────────────────────────────────

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

    data class CostEntry(
        val amount: BigDecimal
    )

    // ── Custom exceptions for error propagation ─────────────────────────────

    class AuthException(message: String) : Exception(message)
    class ServerException(message: String) : Exception(message)
    class RateLimitException : Exception("Rate limit exceeded")
}
