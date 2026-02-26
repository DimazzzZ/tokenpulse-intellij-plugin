package org.zhavoronkov.tokenpulse.provider

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.zhavoronkov.tokenpulse.model.Balance
import org.zhavoronkov.tokenpulse.model.BalanceSnapshot
import org.zhavoronkov.tokenpulse.model.Credits
import org.zhavoronkov.tokenpulse.model.ProviderId
import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.model.Tokens
import org.zhavoronkov.tokenpulse.settings.Account
import java.math.BigDecimal

/**
 * Provider client for OpenRouter.
 *
 * Only **Provisioning Keys** are supported. Regular API keys do not expose the
 * `/api/v1/credits` endpoint required for balance/credits tracking.
 *
 * Endpoints used:
 *  - `GET /api/v1/credits`  → total credits for the provisioning key
 *  - `GET /api/v1/activity` → token usage (prompt + completion tokens)
 */
class OpenRouterProviderClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val gson: Gson = Gson(),
    private val baseUrl: String = "https://openrouter.ai"
) : ProviderClient {

    override fun fetchBalance(account: Account, secret: String): ProviderResult {
        return try {
            val creditsRequest = Request.Builder()
                .url("$baseUrl/api/v1/credits")
                .header("Authorization", "Bearer $secret")
                .build()

            val credits = httpClient.newCall(creditsRequest).execute().use { response ->
                if (!response.isSuccessful) return mapError(response.code, response.message)
                val body = response.body?.string()
                    ?: return ProviderResult.Failure.ParseError("Empty response body")
                val creditsData = gson.fromJson(body, CreditsResponse::class.java).data
                Credits(
                    total = creditsData.total_credits,
                    remaining = creditsData.total_credits
                )
            }

            val activityRequest = Request.Builder()
                .url("$baseUrl/api/v1/activity")
                .header("Authorization", "Bearer $secret")
                .build()

            val tokens = httpClient.newCall(activityRequest).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                val activityData = gson.fromJson(body, ActivityResponse::class.java).data
                val used = activityData.sumOf { it.prompt_tokens + it.completion_tokens }
                Tokens(used = used)
            }

            ProviderResult.Success(
                BalanceSnapshot(
                    accountId = account.id,
                    providerId = ProviderId.OPENROUTER,
                    balance = Balance(credits = credits, tokens = tokens)
                )
            )
        } catch (e: JsonSyntaxException) {
            ProviderResult.Failure.ParseError("Failed to parse OpenRouter response", e)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            ProviderResult.Failure.NetworkError("Failed to connect to OpenRouter", e)
        }
    }

    override fun testCredentials(account: Account, secret: String): ProviderResult =
        fetchBalance(account, secret)

    private fun mapError(code: Int, message: String): ProviderResult.Failure = when (code) {
        HTTP_UNAUTHORIZED -> ProviderResult.Failure.AuthError("Invalid OpenRouter provisioning key")
        HTTP_TOO_MANY_REQUESTS -> ProviderResult.Failure.RateLimited("OpenRouter rate limit exceeded")
        else -> ProviderResult.Failure.UnknownError("OpenRouter error: $code $message")
    }

    companion object {
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_TOO_MANY_REQUESTS = 429
    }

    private data class CreditsResponse(val data: CreditsData)
    private data class CreditsData(val total_credits: BigDecimal)

    private data class ActivityResponse(val data: List<ActivityEntry>)
    private data class ActivityEntry(val prompt_tokens: Long, val completion_tokens: Long)
}
