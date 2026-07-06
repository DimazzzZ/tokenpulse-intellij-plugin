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
import java.time.Instant

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
 * - `GET /api/v1/users/me/plan/usage-limits` → ClinePass usage windows (5-hour / weekly / monthly)
 *
 * ## Balance Representation
 * Cline API returns balance in credits where 1 credit = $0.000001 (micro-dollars).
 * This client converts credits to USD for display.
 *
 * ## ClinePass Usage Limits
 * The plan-usage-limits endpoint is best-effort: it is not part of the documented
 * public API. The client maps recognized `type` values (`five_hour`, `weekly`,
 * `monthly`) into provider-specific metadata keys. Any failure (non-2xx, malformed
 * body, `success:false`, missing `data`, or no recognized limits) is silently
 * swallowed and the rest of the balance snapshot is returned as if the call was
 * never made.
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

            // Best-effort ClinePass usage limits; failure must not break balance fetch.
            val clinePassMetadata = fetchPlanUsageLimits(secret)

            ProviderResult.Success(
                BalanceSnapshot(
                    accountId = account.id,
                    connectionType = ConnectionType.CLINE_API,
                    timestamp = Instant.now(),
                    balance = Balance(
                        credits = Credits(
                            remaining = creditsToUsd(balanceVal),
                            used = creditsToUsd(usages.sumOf { it.creditsUsed })
                        ),
                        tokens = Tokens(
                            used = usages.sumOf { it.totalTokens }
                        )
                    ),
                    metadata = clinePassMetadata
                )
            )
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            ProviderResult.Failure.NetworkError("Failed to connect to Cline", e)
        }
    }

    override fun testCredentials(account: Account, secret: String): ProviderResult {
        return when (val result = fetchMe(secret)) {
            is MeResult.Success -> ProviderResult.Success(
                BalanceSnapshot("test", ConnectionType.CLINE_API, Balance(), timestamp = Instant.now())
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
     * Best-effort fetch of ClinePass usage limits.
     * Returns an empty map on any failure or when no recognized limit is reported.
     * Failures (HTTP errors, malformed bodies, `success:false`, parse errors) are
     * intentionally swallowed so the balance fetch can still succeed.
     */
    private fun fetchPlanUsageLimits(secret: String): Map<String, String> {
        val response: PlanUsageLimitsResponse? = try {
            val request = buildRequest("$baseUrl/api/v1/users/me/plan/usage-limits", secret)
            httpClient.newCall(request).execute().use { httpResponse ->
                if (!httpResponse.isSuccessful) {
                    null
                } else {
                    val body = httpResponse.body?.string()
                    if (body == null) null else parsePlanUsageLimitsResponse(body)
                }
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            null
        }
        return buildClinePassMetadata(response)
    }

    /**
     * Parses a plan-usage-limits response body into a typed [PlanUsageLimitsResponse]
     * or returns `null` if the body is malformed / `success:false` / missing `data`.
     */
    private fun parsePlanUsageLimitsResponse(body: String): PlanUsageLimitsResponse? {
        return try {
            val jsonObject = gson.fromJson(body, JsonObject::class.java)
            if (jsonObject.get("success")?.asBoolean == true) {
                gson.fromJson(jsonObject.get("data"), PlanUsageLimitsResponse::class.java)
            } else {
                null
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            null
        }
    }

    /**
     * Converts Cline credits to USD.
     * Cline API returns balance in credits where 1 credit = $0.000001 (micro-dollars).
     */
    private fun creditsToUsd(credits: BigDecimal): BigDecimal =
        credits.divide(CREDITS_PER_DOLLAR, 2, RoundingMode.HALF_UP)

    /**
     * Normalize recognized plan-usage-limits entries into provider-specific metadata keys.
     * Unknown limit types and missing fields are silently ignored. Returns an empty map
     * when there are no recognized limits so callers can attach nothing to the snapshot.
     */
    private fun buildClinePassMetadata(response: PlanUsageLimitsResponse?): Map<String, String> {
        if (response?.limits.isNullOrEmpty()) return emptyMap()

        val result = LinkedHashMap<String, String>()
        for (limit in response!!.limits) {
            val type = limit.type ?: continue
            val used = limit.percentUsed?.coerceIn(0, 100) ?: continue
            val resetsAt = limit.resetsAt?.takeIf { it.isNotBlank() }
            when (type) {
                TYPE_FIVE_HOUR -> {
                    result[METADATA_FIVE_HOUR_USED] = used.toString()
                    resetsAt?.let { result[METADATA_FIVE_HOUR_RESETS_AT] = it }
                }
                TYPE_WEEKLY -> {
                    result[METADATA_WEEKLY_USED] = used.toString()
                    resetsAt?.let { result[METADATA_WEEKLY_RESETS_AT] = it }
                }
                TYPE_MONTHLY -> {
                    result[METADATA_MONTHLY_USED] = used.toString()
                    resetsAt?.let { result[METADATA_MONTHLY_RESETS_AT] = it }
                }
                // Unknown limit types are intentionally ignored.
            }
        }
        return result
    }

    private data class UserInfoWrapper(val data: UserResponse?)

    private data class UserResponse(
        val id: String
    )

    private data class BalanceResponse(val balance: BigDecimal)

    private data class UsagesResponse(val items: List<UsageTransaction>)

    private data class UsageTransaction(
        val creditsUsed: BigDecimal,
        val totalTokens: Long
    )

    private data class PlanUsageLimitsResponse(
        val limits: List<PlanUsageLimit>?
    )

    private data class PlanUsageLimit(
        val type: String?,
        val percentUsed: Int?,
        val resetsAt: String?
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

        // ClinePass plan-usage-limits type values.
        private const val TYPE_FIVE_HOUR = "five_hour"
        private const val TYPE_WEEKLY = "weekly"
        private const val TYPE_MONTHLY = "monthly"

        // Normalized provider-specific metadata keys for the tooltip renderer.
        const val METADATA_FIVE_HOUR_USED = "clinePassFiveHourUsed"
        const val METADATA_FIVE_HOUR_RESETS_AT = "clinePassFiveHourResetsAt"
        const val METADATA_WEEKLY_USED = "clinePassWeeklyUsed"
        const val METADATA_WEEKLY_RESETS_AT = "clinePassWeeklyResetsAt"
        const val METADATA_MONTHLY_USED = "clinePassMonthlyUsed"
        const val METADATA_MONTHLY_RESETS_AT = "clinePassMonthlyResetsAt"
    }
}
