package org.zhavoronkov.tokenpulse.provider

import com.google.gson.Gson
import org.zhavoronkov.tokenpulse.model.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.math.BigDecimal

class OpenRouterProviderClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val gson: Gson = Gson()
) : ProviderClient {

    override fun fetchBalance(accountId: String, apiKey: String): ProviderResult {
        // For MVP simplicity, we fetch credits. 
        // Real implementation would bifurcate based on account.authType (not accessible here yet)
        // or try to fetch both activity and credits.
        return try {
            val creditsRequest = Request.Builder()
                .url("https://openrouter.ai/api/v1/credits")
                .header("Authorization", "Bearer $apiKey")
                .build()

            httpClient.newCall(creditsRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    return mapError(response.code, response.message)
                }

                val body = response.body?.string() ?: return ProviderResult.Failure.ParseError("Empty response body")
                val creditsData = gson.fromJson(body, CreditsResponse::class.java).data
                
                ProviderResult.Success(
                    BalanceSnapshot(
                        accountId = accountId,
                        providerId = ProviderId.OPENROUTER,
                        balance = Balance(
                            credits = Credits(
                                total = creditsData.total_credits,
                                remaining = creditsData.total_credits // Simplified for MVP
                            )
                        )
                    )
                )
            }
        } catch (e: Exception) {
            ProviderResult.Failure.NetworkError("Failed to connect to OpenRouter", e)
        }
    }

    override fun testCredentials(apiKey: String): ProviderResult {
        return fetchBalance("test-account", apiKey)
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
}
