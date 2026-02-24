package org.zhavoronkov.tokenpulse.provider

import com.google.gson.Gson
import org.zhavoronkov.tokenpulse.model.*
import okhttp3.OkHttpClient
import okhttp3.Request

class ClineProviderClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val gson: Gson = Gson()
) : ProviderClient {

    override fun fetchBalance(accountId: String, apiKey: String): ProviderResult {
        return try {
            val request = Request.Builder()
                .url("https://api.cline.bot/v1/quota")
                .header("X-API-Key", apiKey)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return when (response.code) {
                        401 -> ProviderResult.Failure.AuthError("Invalid Cline API key")
                        429 -> ProviderResult.Failure.RateLimited("Cline rate limit exceeded")
                        else -> ProviderResult.Failure.UnknownError("Cline error: ${response.code} ${response.message}")
                    }
                }

                val body = response.body?.string() ?: return ProviderResult.Failure.ParseError("Empty response body")
                val data = gson.fromJson(body, QuotaResponse::class.java)
                
                ProviderResult.Success(
                    BalanceSnapshot(
                        accountId = accountId,
                        providerId = ProviderId.CLINE,
                        balance = Balance(
                            tokens = Tokens(
                                used = data.used_tokens,
                                total = data.total_tokens
                            )
                        )
                    )
                )
            }
        } catch (e: Exception) {
            ProviderResult.Failure.NetworkError("Failed to connect to Cline", e)
        }
    }

    override fun testCredentials(apiKey: String): ProviderResult {
        return fetchBalance("test-account", apiKey)
    }

    private data class QuotaResponse(val used_tokens: Long, val total_tokens: Long?)
}
