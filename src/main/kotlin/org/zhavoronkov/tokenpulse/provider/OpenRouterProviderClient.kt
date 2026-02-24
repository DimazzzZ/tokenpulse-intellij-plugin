package org.zhavoronkov.tokenpulse.provider

import com.google.gson.Gson
import org.zhavoronkov.tokenpulse.model.Balance
import org.zhavoronkov.tokenpulse.model.BalanceSnapshot
import org.zhavoronkov.tokenpulse.model.ProviderId
import org.zhavoronkov.tokenpulse.model.ProviderResult
import okhttp3.OkHttpClient
import okhttp3.Request
import java.math.BigDecimal

class OpenRouterProviderClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val gson: Gson = Gson()
) : ProviderClient {

    override fun fetchBalance(accountId: String, apiKey: String): ProviderResult {
        return try {
            val request = Request.Builder()
                .url("https://openrouter.ai/api/v1/credits")
                .header("Authorization", "Bearer $apiKey")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return ProviderResult.Error("OpenRouter API error: ${response.code} ${response.message}")
                }

                val body = response.body?.string() ?: return ProviderResult.Error("Empty response body")
                val data = gson.fromJson(body, CreditsResponse::class.java)
                
                ProviderResult.Success(
                    BalanceSnapshot(
                        accountId = accountId,
                        providerId = ProviderId.OPENROUTER,
                        balance = Balance.CreditsUsd(data.data.total_credits)
                    )
                )
            }
        } catch (e: Exception) {
            ProviderResult.Error("Failed to fetch OpenRouter balance", e)
        }
    }

    private data class CreditsResponse(val data: CreditsData)
    private data class CreditsData(val total_credits: BigDecimal)
}
