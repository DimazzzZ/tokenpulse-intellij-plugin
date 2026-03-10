package org.zhavoronkov.tokenpulse.provider.openrouter

import com.google.gson.Gson
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
import java.math.BigDecimal

/**
 * Provider client for OpenRouter unified API gateway.
 *
 * Only **Provisioning Keys** are supported. Regular API keys do not expose the
 * `/api/v1/credits` endpoint required for balance/credits tracking.
 *
 * ## Endpoints
 * - `GET /api/v1/credits` → Total credits for the provisioning key
 * - `GET /api/v1/activity` → Token usage (prompt + completion tokens)
 *
 * ## Authentication
 * Uses Bearer token authentication with a provisioning key.
 * Provisioning keys can be created at: https://openrouter.ai/settings/provisioning-keys
 */
class OpenRouterProviderClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val gson: Gson = Gson(),
    private val baseUrl: String = OPENROUTER_BASE_URL
) : ProviderClient {

    override fun fetchBalance(account: Account, secret: String): ProviderResult {
        return try {
            val credits = fetchCredits(secret)
            // Tokens are optional - if activity endpoint fails, we still return credits
            val tokens = fetchTokensSafe(secret)

            ProviderResult.Success(
                BalanceSnapshot(
                    accountId = account.id,
                    connectionType = ConnectionType.OPENROUTER_PROVISIONING,
                    balance = Balance(credits = credits, tokens = tokens)
                )
            )
        } catch (e: JsonSyntaxException) {
            ProviderResult.Failure.ParseError("Failed to parse OpenRouter response", e)
        } catch (e: AuthException) {
            ProviderResult.Failure.AuthError("Invalid OpenRouter provisioning key")
        } catch (e: RateLimitException) {
            ProviderResult.Failure.RateLimited("OpenRouter rate limit exceeded")
        } catch (e: ServerException) {
            ProviderResult.Failure.UnknownError("OpenRouter error: ${e.message}")
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            ProviderResult.Failure.NetworkError("Failed to connect to OpenRouter", e)
        }
    }

    override fun testCredentials(account: Account, secret: String): ProviderResult =
        fetchBalance(account, secret)

    private fun fetchCredits(secret: String): Credits? {
        val request = Request.Builder()
            .url("$baseUrl/api/v1/credits")
            .header("Authorization", "Bearer $secret")
            .build()

        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                handleErrorResponse(response.code)
            }
            val body = response.body?.string() ?: return null
            val creditsData = gson.fromJson(body, CreditsResponse::class.java).data
            // totalCredits is the TOTAL amount purchased/limit
            // totalUsage is how much has been spent
            // remaining = totalCredits - totalUsage
            val total = creditsData.totalCredits
            val used = creditsData.totalUsage ?: BigDecimal.ZERO
            val remaining = total.subtract(used)
            Credits(
                total = total,
                used = used,
                remaining = remaining
            )
        }
    }

    /**
     * Fetches token usage, returning null if any error occurs.
     * This allows partial success when credits are available but activity is not.
     */
    private fun fetchTokensSafe(secret: String): Tokens? {
        return try {
            fetchTokens(secret)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            null
        }
    }

    private fun fetchTokens(secret: String): Tokens? {
        val request = Request.Builder()
            .url("$baseUrl/api/v1/activity")
            .header("Authorization", "Bearer $secret")
            .build()

        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                handleErrorResponse(response.code)
            }
            val body = response.body?.string() ?: return null
            val activityData = gson.fromJson(body, ActivityResponse::class.java).data
            val used = activityData.sumOf { it.promptTokens + it.completionTokens }
            Tokens(used = used)
        }
    }

    @Suppress("ThrowsCount")
    private fun handleErrorResponse(code: Int): Nothing {
        when (code) {
            HTTP_UNAUTHORIZED -> throw AuthException("HTTP $code")
            HTTP_TOO_MANY_REQUESTS -> throw RateLimitException()
            else -> throw ServerException("HTTP $code")
        }
    }

    private class AuthException(message: String) : Exception(message)
    private class RateLimitException : Exception()
    private class ServerException(message: String) : Exception(message)

    private data class CreditsResponse(val data: CreditsData)
    private data class CreditsData(
        @com.google.gson.annotations.SerializedName("total_credits")
        val totalCredits: BigDecimal,
        @com.google.gson.annotations.SerializedName("total_usage")
        val totalUsage: BigDecimal? = null
    )

    private data class ActivityResponse(val data: List<ActivityEntry>)
    private data class ActivityEntry(
        @com.google.gson.annotations.SerializedName("prompt_tokens")
        val promptTokens: Long,
        @com.google.gson.annotations.SerializedName("completion_tokens")
        val completionTokens: Long
    )

    companion object {
        private const val OPENROUTER_BASE_URL = "https://openrouter.ai"
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_TOO_MANY_REQUESTS = 429
    }
}
