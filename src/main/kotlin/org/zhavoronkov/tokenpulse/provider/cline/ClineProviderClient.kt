package org.zhavoronkov.tokenpulse.provider.cline

import com.google.gson.Gson
import com.google.gson.JsonObject
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
import java.math.RoundingMode

/**
 * Provider client for the Cline AI coding assistant service.
 *
 * Cline provides a REST API for accessing user and organization balance information.
 * This client supports both personal accounts and organization members.
 *
 * ## Endpoints
 * - `GET /api/v1/users/me` → User info including organization membership
 * - `GET /api/v1/users/{id}/balance` → Personal account balance
 * - `GET /api/v1/users/{id}/usages` → Personal account usage
 * - `GET /api/v1/organizations/{id}/balance` → Organization balance
 * - `GET /api/v1/organizations/{id}/members/{memberId}/usages` → Member usage
 *
 * ## Balance Representation
 * Cline API returns balance in credits where 1 credit = $0.000001 (micro-dollars).
 * This client converts credits to USD for display.
 *
 * ## Error Handling
 * - 401/403 → AuthError (invalid/expired token)
 * - 429 → RateLimited (temporary, will retry)
 * - 5xx → NetworkError (transient, won't trigger credential cooldown)
 * - Other → NetworkError
 */
class ClineProviderClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val gson: Gson = Gson(),
    private val baseUrl: String = CLINE_API_BASE_URL
) : ProviderClient {

    override fun fetchBalance(account: Account, secret: String): ProviderResult {
        return try {
            val meResult = fetchMe(secret)
            val me = when (meResult) {
                is MeResult.Success -> meResult.user
                is MeResult.Failure.Auth -> return ProviderResult.Failure.AuthError(meResult.message)
                is MeResult.Failure.RateLimited -> return ProviderResult.Failure.RateLimited(meResult.message)
                is MeResult.Failure.Network -> return ProviderResult.Failure.NetworkError(meResult.message)
            }

            // API keys are personal - always fetch personal balance
            // Organization billing is managed separately and doesn't use API keys
            val (balanceVal, usages) = fetchUserData(me.id, secret)

            ProviderResult.Success(
                BalanceSnapshot(
                    accountId = account.id,
                    connectionType = ConnectionType.CLINE_API,
                    balance = Balance(
                        credits = Credits(
                            remaining = creditsToUsd(balanceVal),
                            used = creditsToUsd(usages.sumOf { it.creditsUsed })
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

    override fun testCredentials(account: Account, secret: String): ProviderResult {
        return when (val result = fetchMe(secret)) {
            is MeResult.Success -> ProviderResult.Success(
                BalanceSnapshot("test", ConnectionType.CLINE_API, Balance())
            )
            is MeResult.Failure.Auth -> ProviderResult.Failure.AuthError(result.message)
            is MeResult.Failure.RateLimited -> ProviderResult.Failure.RateLimited(result.message)
            is MeResult.Failure.Network -> ProviderResult.Failure.NetworkError(result.message)
        }
    }

    private fun fetchMe(secret: String): MeResult {
        val request = Request.Builder()
            .url("$baseUrl/api/v1/users/me")
            .header("Authorization", "Bearer $secret")
            .build()

        return httpClient.newCall(request).execute().use { response ->
            val code = response.code
            when {
                response.isSuccessful -> {
                    val body = response.body?.string() ?: return MeResult.Failure.Network("Empty response body")
                    val user = gson.fromJson(body, UserInfoWrapper::class.java).data
                    if (user != null) MeResult.Success(user) else MeResult.Failure.Network("Invalid response format")
                }
                code == HTTP_UNAUTHORIZED || code == HTTP_FORBIDDEN ->
                    MeResult.Failure.Auth("Invalid or expired Cline API key")
                code == HTTP_TOO_MANY_REQUESTS ->
                    MeResult.Failure.RateLimited("Cline API rate limit exceeded")
                code >= HTTP_INTERNAL_ERROR ->
                    MeResult.Failure.Network("Cline API server error: $code")
                else ->
                    MeResult.Failure.Network("Cline API error: $code")
            }
        }
    }

    private fun fetchUserData(userId: String, secret: String): Pair<BigDecimal, List<UsageTransaction>> {
        val balance = fetchJson<BalanceResponse>("$baseUrl/api/v1/users/$userId/balance", secret)
            ?.balance ?: BigDecimal.ZERO
        val usages = fetchJson<UsagesResponse>("$baseUrl/api/v1/users/$userId/usages", secret)
            ?.items ?: emptyList()
        return balance to usages
    }

    private inline fun <reified T> fetchJson(url: String, secret: String): T? {
        val request = buildRequest(url, secret)
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            parseJsonResponse(body, T::class.java)
        }
    }

    private fun buildRequest(url: String, secret: String): Request =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $secret")
            .build()

    private fun <T> parseJsonResponse(body: String, type: Class<T>): T? {
        val jsonObject = gson.fromJson(body, JsonObject::class.java)
        return if (jsonObject.get("success")?.asBoolean == true) {
            gson.fromJson(jsonObject.get("data"), type)
        } else {
            null
        }
    }

    /**
     * Converts Cline credits to USD.
     * Cline API returns balance in credits where 1 credit = $0.000001 (micro-dollars).
     */
    private fun creditsToUsd(credits: BigDecimal): BigDecimal =
        credits.divide(CREDITS_PER_DOLLAR, 2, RoundingMode.HALF_UP)

    private data class UserInfoWrapper(val data: UserResponse?)

    private data class UserResponse(
        val id: String,
        val organizations: List<OrgInfo>?
    )

    private data class OrgInfo(
        val organizationId: String,
        val memberId: String,
        val active: Boolean
    )

    private data class BalanceResponse(val balance: BigDecimal)

    private data class UsagesResponse(val items: List<UsageTransaction>)

    private data class UsageTransaction(
        val creditsUsed: BigDecimal,
        val totalTokens: Long
    )

    /**
     * Sealed result type for fetchMe to distinguish between auth failures and other errors.
     */
    private sealed class MeResult {
        data class Success(val user: UserResponse) : MeResult()
        sealed class Failure : MeResult() {
            data class Auth(val message: String) : Failure()
            data class RateLimited(val message: String) : Failure()
            data class Network(val message: String) : Failure()
        }
    }

    companion object {
        private const val CLINE_API_BASE_URL = "https://api.cline.bot"

        /** Cline API balance is in micro-dollars (1 credit = $0.000001). */
        private val CREDITS_PER_DOLLAR = BigDecimal(1_000_000)

        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_INTERNAL_ERROR = 500
    }
}
