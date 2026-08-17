package org.zhavoronkov.tokenpulse.provider.deepseek

import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import org.zhavoronkov.tokenpulse.model.Balance
import org.zhavoronkov.tokenpulse.model.BalanceSnapshot
import org.zhavoronkov.tokenpulse.model.ConnectionType
import org.zhavoronkov.tokenpulse.model.Credits
import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.provider.ProviderClient
import org.zhavoronkov.tokenpulse.settings.Account
import java.math.BigDecimal
import java.time.Instant

/**
 * Provider client for DeepSeek API.
 *
 * DeepSeek provides a dedicated balance endpoint: `GET /user/balance`
 * that returns the user's account balance in their native currency (typically CNY or USD).
 *
 * ## Endpoints
 * - `GET https://api.deepseek.com/user/balance` → User balance information
 *
 * ## Balance Representation
 * DeepSeek returns balance as a string decimal in the user's currency (e.g., "5.00" CNY or USD).
 * This client converts the string to BigDecimal and stores the currency in metadata for display.
 *
 * ## Error Handling
 * - 401/403 → AuthError (invalid/expired key)
 * - 429 → RateLimited (temporary, will retry)
 * - 5xx → NetworkError (transient, won't trigger credential cooldown)
 * - Other → NetworkError
 */
class DeepSeekProviderClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val gson: Gson = Gson(),
    private val baseUrl: String = DEEPSEEK_API_BASE_URL
) : ProviderClient {

    override fun fetchBalance(account: Account, secret: String): ProviderResult {
        return try {
            val response = fetchUserBalance(secret)
            when (response) {
                is BalanceResponse.Success -> {
                    ProviderResult.Success(
                        BalanceSnapshot(
                            accountId = account.id,
                            connectionType = ConnectionType.DEEPSEEK_API,
                            timestamp = Instant.now(),
                            balance = Balance(
                                credits = Credits(
                                    remaining = response.totalBalance
                                )
                            ),
                            metadata = mapOf("currency" to response.currency)
                        )
                    )
                }
                is BalanceResponse.Failure.Auth -> ProviderResult.Failure.AuthError(response.message)
                is BalanceResponse.Failure.RateLimited -> ProviderResult.Failure.RateLimited(response.message)
                is BalanceResponse.Failure.Network -> ProviderResult.Failure.NetworkError(response.message)
                is BalanceResponse.Failure.Parse -> ProviderResult.Failure.ParseError(response.message, response.cause)
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            ProviderResult.Failure.NetworkError("Failed to connect to DeepSeek", e)
        }
    }

    override fun testCredentials(account: Account, secret: String): ProviderResult {
        return when (val response = fetchUserBalance(secret)) {
            is BalanceResponse.Success -> ProviderResult.Success(
                BalanceSnapshot("test", ConnectionType.DEEPSEEK_API, Balance(), timestamp = Instant.now())
            )
            is BalanceResponse.Failure.Auth -> ProviderResult.Failure.AuthError(response.message)
            is BalanceResponse.Failure.RateLimited -> ProviderResult.Failure.RateLimited(response.message)
            is BalanceResponse.Failure.Network -> ProviderResult.Failure.NetworkError(response.message)
            is BalanceResponse.Failure.Parse -> ProviderResult.Failure.ParseError(response.message, response.cause)
        }
    }

    private fun fetchUserBalance(secret: String): BalanceResponse {
        val request = Request.Builder()
            .url("$baseUrl/user/balance")
            .header("Authorization", "Bearer $secret")
            .build()

        return httpClient.newCall(request).execute().use { response ->
            val code = response.code
            when {
                response.isSuccessful -> {
                    val body = response.body?.string() ?: return BalanceResponse.Failure.Network("Empty response body")
                    parseBalanceResponse(body)
                }
                code == HTTP_UNAUTHORIZED || code == HTTP_FORBIDDEN ->
                    BalanceResponse.Failure.Auth("Invalid or expired DeepSeek API key")
                code == HTTP_TOO_MANY_REQUESTS ->
                    BalanceResponse.Failure.RateLimited("DeepSeek API rate limit exceeded")
                code >= HTTP_INTERNAL_ERROR ->
                    BalanceResponse.Failure.Network("DeepSeek API server error: $code")
                else ->
                    BalanceResponse.Failure.Network("DeepSeek API error: $code")
            }
        }
    }

    private fun parseBalanceResponse(body: String): BalanceResponse {
        return try {
            val jsonObject = gson.fromJson(body, JsonObject::class.java)

            // Extract balance_infos array. Note: DeepSeek's `is_available` field
            // is informational — it means "account has enough balance to make
            // API calls" (i.e., non-zero balance). It is NOT an auth/connectivity
            // signal. A brand-new key with $0.00 balance returns is_available=false
            // but is a perfectly valid key, so we always parse the balance data.
            val balanceInfos = jsonObject.getAsJsonArray("balance_infos")
            if (balanceInfos == null || balanceInfos.size() == 0) {
                return BalanceResponse.Failure.Parse("Missing or empty balance_infos in response", null)
            }

            parseFirstBalanceEntry(balanceInfos.get(0).asJsonObject)
        } catch (e: Exception) {
            BalanceResponse.Failure.Parse("Failed to parse DeepSeek balance response", e)
        }
    }

    private fun parseFirstBalanceEntry(entry: JsonObject): BalanceResponse {
        val currency = entry.get("currency")?.asString ?: "CNY"
        val totalBalanceStr = entry.get("total_balance")?.asString
            ?: return BalanceResponse.Failure.Parse("Missing total_balance in balance_infos", null)

        val totalBalance = totalBalanceStr.toBigDecimalOrNull()
            ?: return BalanceResponse.Failure.Parse("Invalid total_balance format: $totalBalanceStr", null)

        return BalanceResponse.Success(totalBalance, currency)
    }

    private sealed class BalanceResponse {
        data class Success(val totalBalance: BigDecimal, val currency: String) : BalanceResponse()
        sealed class Failure : BalanceResponse() {
            data class Auth(val message: String) : Failure()
            data class RateLimited(val message: String) : Failure()
            data class Network(val message: String) : Failure()
            data class Parse(val message: String, val cause: Throwable?) : Failure()
        }
    }

    companion object {
        private const val DEEPSEEK_API_BASE_URL = "https://api.deepseek.com"
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_INTERNAL_ERROR = 500
    }
}
