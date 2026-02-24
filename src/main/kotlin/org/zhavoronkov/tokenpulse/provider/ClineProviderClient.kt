package org.zhavoronkov.tokenpulse.provider

import com.google.gson.Gson
import org.zhavoronkov.tokenpulse.model.Balance
import org.zhavoronkov.tokenpulse.model.BalanceSnapshot
import org.zhavoronkov.tokenpulse.model.ProviderId
import org.zhavoronkov.tokenpulse.model.ProviderResult
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
                    return ProviderResult.Error("Cline API error: ${response.code} ${response.message}")
                }

                val body = response.body?.string() ?: return ProviderResult.Error("Empty response body")
                val data = gson.fromJson(body, QuotaResponse::class.java)
                
                ProviderResult.Success(
                    BalanceSnapshot(
                        accountId = accountId,
                        providerId = ProviderId.CLINE,
                        balance = Balance.Tokens(
                            used = data.used_tokens,
                            total = data.total_tokens
                        )
                    )
                )
            }
        } catch (e: Exception) {
            ProviderResult.Error("Failed to fetch Cline balance", e)
        }
    }

    private data class QuotaResponse(val used_tokens: Long, val total_tokens: Long?)
}
