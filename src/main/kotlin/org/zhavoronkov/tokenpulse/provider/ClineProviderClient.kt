package org.zhavoronkov.tokenpulse.provider

import com.google.gson.Gson
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
 * Client for the Cline provider.
 */
class ClineProviderClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val gson: Gson = Gson(),
    private val baseUrl: String = "https://api.cline.bot"
) : ProviderClient {

    override fun fetchBalance(account: Account, secret: String): ProviderResult {
        return try {
            // 1. Get user and organization info
            val me = fetchMe(secret) ?: return ProviderResult.Failure.AuthError("Failed to fetch user info")
            
            val activeOrg = me.organizations?.find { it.active }
            
            val (balanceVal, usages) = if (activeOrg != null) {
                fetchOrgData(activeOrg.organizationId, activeOrg.memberId, secret)
            } else {
                fetchUserData(me.id, secret)
            }

            ProviderResult.Success(
                BalanceSnapshot(
                    accountId = account.id,
                    providerId = ProviderId.CLINE,
                    balance = Balance(
                        credits = Credits(
                            remaining = balanceVal,
                            used = usages.sumOf { it.creditsUsed }
                        ),
                        tokens = Tokens(
                            used = usages.sumOf { it.totalTokens }
                        )
                    )
                )
            )
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            ProviderResult.Failure.NetworkError("Failed to connect to Cline", e)
        }
    }

    private fun fetchMe(secret: String): UserResponse? {
        val request = Request.Builder()
            .url("$baseUrl/api/v1/users/me")
            .header("Authorization", "Bearer $secret")
            .build()
        
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            gson.fromJson(body, UserInfoWrapper::class.java).data
        }
    }

    private fun fetchUserData(userId: String, secret: String): Pair<BigDecimal, List<UsageTransaction>> {
        val balance = fetchJson<BalanceResponse>("$baseUrl/api/v1/users/$userId/balance", secret)?.balance ?: BigDecimal.ZERO
        val usages = fetchJson<UsagesResponse>("$baseUrl/api/v1/users/$userId/usages", secret)?.items ?: emptyList()
        return balance to usages
    }

    private fun fetchOrgData(orgId: String, memberId: String, secret: String): Pair<BigDecimal, List<UsageTransaction>> {
        val balance = fetchJson<BalanceResponse>("$baseUrl/api/v1/organizations/$orgId/balance", secret)?.balance ?: BigDecimal.ZERO
        val usages = fetchJson<UsagesResponse>("$baseUrl/api/v1/organizations/$orgId/members/$memberId/usages", secret)?.items ?: emptyList()
        return balance to usages
    }

    private inline fun <reified T> fetchJson(url: String, secret: String): T? {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $secret")
            .build()
        
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val jsonObject = gson.fromJson(body, com.google.gson.JsonObject::class.java)
            if (jsonObject.get("success")?.asBoolean == true) {
                gson.fromJson(jsonObject.get("data"), T::class.java)
            } else null
        }
    }

    override fun testCredentials(account: Account, secret: String): ProviderResult {
        return if (fetchMe(secret) != null) {
            ProviderResult.Success(BalanceSnapshot("test", ProviderId.CLINE, Balance()))
        } else {
            ProviderResult.Failure.AuthError("Invalid Cline token")
        }
    }

    private data class UserInfoWrapper(val data: UserResponse)
    private data class UserResponse(val id: String, val organizations: List<OrgInfo>?)
    private data class OrgInfo(val organizationId: String, val memberId: String, val active: Boolean)
    private data class BalanceResponse(val balance: BigDecimal)
    private data class UsagesResponse(val items: List<UsageTransaction>)
    private data class UsageTransaction(val creditsUsed: BigDecimal, val totalTokens: Long)
}
