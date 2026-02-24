package org.zhavoronkov.tokenpulse.provider

import com.google.gson.Gson
import org.zhavoronkov.tokenpulse.model.*
import org.zhavoronkov.tokenpulse.settings.Account
import org.zhavoronkov.tokenpulse.settings.AuthType
import okhttp3.OkHttpClient
import okhttp3.Request
import java.math.BigDecimal

class OpenRouterProviderClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val gson: Gson = Gson()
) : ProviderClient {

    override fun fetchBalance(account: Account, secret: String): ProviderResult {
        return when (account.authType) {
            AuthType.OPENROUTER_PROVISIONING_KEY -> fetchProvisioningKeyBalance(account, secret)
            AuthType.OPENROUTER_API_KEY -> fetchApiKeyBalance(account, secret)
            else -> ProviderResult.Failure.UnknownError("Unsupported auth type: ${account.authType}")
        }
    }

    private fun fetchProvisioningKeyBalance(account: Account, secret: String): ProviderResult {
        return try {
            val creditsRequest = Request.Builder()
                .url("https://openrouter.ai/api/v1/credits")
                .header("Authorization", "Bearer $secret")
                .build()

            val credits = httpClient.newCall(creditsRequest).execute().use { response ->
                if (!response.isSuccessful) return mapError(response.code, response.message)
                val body = response.body?.string() ?: return ProviderResult.Failure.ParseError("Empty response body")
                val creditsData = gson.fromJson(body, CreditsResponse::class.java).data
                Credits(
                    total = creditsData.total_credits,
                    remaining = creditsData.total_credits
                )
            }

            val activityRequest = Request.Builder()
                .url("https://openrouter.ai/api/v1/activity")
                .header("Authorization", "Bearer $secret")
                .build()

            val tokens = httpClient.newCall(activityRequest).execute().use { response ->
                if (!response.isSuccessful) null
                else {
                    val body = response.body?.string() ?: return@use null
                    val activityData = gson.fromJson(body, ActivityResponse::class.java).data
                    // Sum all tokens from activity
                    val used = activityData.sumOf { it.prompt_tokens + it.completion_tokens }
                    Tokens(used = used)
                }
            }

            ProviderResult.Success(
                BalanceSnapshot(
                    accountId = account.id,
                    providerId = ProviderId.OPENROUTER,
                    balance = Balance(credits = credits, tokens = tokens)
                )
            )
        } catch (e: Exception) {
            ProviderResult.Failure.NetworkError("Failed to connect to OpenRouter", e)
        }
    }

    private fun fetchApiKeyBalance(account: Account, secret: String): ProviderResult {
        return try {
            val keyRequest = Request.Builder()
                .url("https://openrouter.ai/api/v1/key")
                .header("Authorization", "Bearer $secret")
                .build()

            httpClient.newCall(keyRequest).execute().use { response ->
                if (!response.isSuccessful) return mapError(response.code, response.message)

                val body = response.body?.string() ?: return ProviderResult.Failure.ParseError("Empty response body")
                val keyData = gson.fromJson(body, KeyResponse::class.java).data
                
                ProviderResult.Success(
                    BalanceSnapshot(
                        accountId = account.id,
                        providerId = ProviderId.OPENROUTER,
                        balance = Balance(
                            credits = Credits(
                                remaining = keyData.limit?.subtract(keyData.usage ?: BigDecimal.ZERO) 
                                           ?: BigDecimal.ZERO
                            )
                        )
                    )
                )
            }
        } catch (e: Exception) {
            ProviderResult.Failure.NetworkError("Failed to connect to OpenRouter", e)
        }
    }

    override fun testCredentials(account: Account, secret: String): ProviderResult {
        return fetchBalance(account, secret)
    }

    private fun mapError(code: Int, message: String): ProviderResult.Failure {
        return when (code) {
            401 -> ProviderResult.Failure.AuthError("Invalid OpenRouter API key")
            429 -> ProviderResult.Failure.RateLimited("OpenRouter rate limit exceeded")
            else -> ProviderResult.Failure.UnknownError("OpenRouter error: $code $message")
        }
    }

    private data class CreditsResponse(val data: CreditsData)
    private data class CreditsData(val total_credits: BigDecimal)
    
    private data class KeyResponse(val data: KeyData)
    private data class KeyData(val label: String, val limit: BigDecimal?, val usage: BigDecimal?)

    private data class ActivityResponse(val data: List<ActivityEntry>)
    private data class ActivityEntry(val prompt_tokens: Long, val completion_tokens: Long)
}
