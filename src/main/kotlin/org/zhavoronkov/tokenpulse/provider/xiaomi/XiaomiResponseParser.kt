package org.zhavoronkov.tokenpulse.provider.xiaomi

import com.google.gson.JsonObject
import org.zhavoronkov.tokenpulse.model.Balance
import org.zhavoronkov.tokenpulse.model.BalanceSnapshot
import org.zhavoronkov.tokenpulse.model.ConnectionType
import org.zhavoronkov.tokenpulse.model.Credits
import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.model.Tokens
import org.zhavoronkov.tokenpulse.settings.Account
import java.math.BigDecimal
import java.time.Instant

/**
 * Parses Xiaomi platform API responses into [ProviderResult] domain objects.
 *
 * Handles both the Xiaomi API balance endpoint and the Token Plan usage endpoint,
 * including safe null handling for stopped/expired plans where the API returns
 * `null` instead of expected objects or arrays.
 */
internal object XiaomiResponseParser {

    /**
     * Parse the Xiaomi API balance response (pay-as-you-go).
     *
     * Expected shape:
     * ```json
     * { "code": 0, "data": { "balance": "55.48", "giftBalance": "55.48", "cashBalance": "0.00", "currency": "USD" } }
     * ```
     */
    fun parseApiBalance(json: JsonObject, account: Account): ProviderResult {
        val envelopeError = checkEnvelope(json)
        if (envelopeError != null) return envelopeError

        val data = json.getObjectOrNull("data")
        val balance = data?.getStringOrNull("balance")?.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val giftBalance = data?.getStringOrNull("giftBalance")?.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val cashBalance = data?.getStringOrNull("cashBalance")?.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val currency = data?.getStringOrNull("currency") ?: "USD"

        return ProviderResult.Success(
            BalanceSnapshot(
                accountId = account.id,
                connectionType = ConnectionType.XIAOMI_API,
                balance = Balance(
                    credits = Credits(remaining = balance, total = null, used = null)
                ),
                timestamp = Instant.now(),
                metadata = mapOf(
                    "currency" to currency,
                    "giftBalance" to giftBalance.toPlainString(),
                    "cashBalance" to cashBalance.toPlainString()
                )
            )
        )
    }

    /**
     * Parse the Xiaomi Token Plan usage response.
     *
     * Expected shape:
     * ```json
     * { "code": 0, "data": { "monthUsage": { "percent": 0.248, "items": [{ "used": 2727524596, "limit": 11000000000 }] } } }
     * ```
     *
     * When a plan is stopped/expired, the API may return `null` for `data`,
     * `monthUsage`, or `items`. This method handles all such cases gracefully.
     */
    fun parseTokenPlanUsage(json: JsonObject, account: Account): ProviderResult {
        val envelopeError = checkEnvelope(json)
        if (envelopeError != null) return envelopeError

        val data = json.getObjectOrNull("data")
        val monthUsage = data?.getObjectOrNull("monthUsage")
        val usagePercent = monthUsage?.getAsDoubleOrZero("percent")

        val items = monthUsage?.getArrayOrNull("items")
        var usedCredits = 0L
        var totalCredits = 0L
        if (items != null && items.size() > 0) {
            val item = items[0].asJsonObject
            usedCredits = item.getLongOrZero("used")
            totalCredits = item.getLongOrZero("limit")
        }

        // When totalCredits is 0 (no active plan), the percentage is meaningless.
        // Report 100% used so the UI shows "0% remaining" instead of misleading "100% remaining".
        val effectiveUsedPercent = if (totalCredits == 0L) 100 else ((usagePercent ?: 0.0) * 100).toInt()

        val planStatus = if (totalCredits == 0L) "inactive" else "active"

        return ProviderResult.Success(
            BalanceSnapshot(
                accountId = account.id,
                connectionType = ConnectionType.XIAOMI_TOKEN_PLAN,
                balance = Balance(
                    tokens = Tokens(
                        used = usedCredits,
                        total = totalCredits,
                        remaining = totalCredits - usedCredits
                    )
                ),
                timestamp = Instant.now(),
                metadata = mapOf(
                    "sessionUsed" to effectiveUsedPercent.toString(),
                    "planUsed" to usedCredits.toString(),
                    "planTotal" to totalCredits.toString(),
                    "planStatus" to planStatus
                )
            )
        )
    }

    /**
     * Check the Xiaomi API envelope for errors.
     * Returns a [ProviderResult.Failure] if the response indicates an error, or `null` if successful.
     */
    private fun checkEnvelope(json: JsonObject): ProviderResult.Failure? {
        val code = json.getAsIntOrMinusOne("code")
        if (code != 0) {
            val message = json.getStringOrNull("message") ?: "Unknown error"
            return mapXiaomiApiError(code, message)
        }
        return null
    }

    /**
     * Map Xiaomi API error codes and messages to appropriate [ProviderResult.Failure] types.
     */
    private fun mapXiaomiApiError(code: Int, message: String): ProviderResult.Failure {
        val lowerMessage = message.lowercase()
        return when {
            lowerMessage.contains("token expired") ||
                lowerMessage.contains("session") ||
                lowerMessage.contains("unauthorized") ||
                lowerMessage.contains("login") ->
                ProviderResult.Failure.AuthError("Xiaomi API error: $message")
            lowerMessage.contains("rate") || lowerMessage.contains("limit") || lowerMessage.contains("throttl") ->
                ProviderResult.Failure.RateLimited("Xiaomi rate limit: $message")
            else ->
                ProviderResult.Failure.UnknownError("Xiaomi API error (code=$code): $message")
        }
    }

    // -- Safe JSON accessors --

    private fun JsonObject.getObjectOrNull(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.getArrayOrNull(key: String) =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonObject.getStringOrNull(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.getAsIntOrMinusOne(key: String): Int =
        get(key)?.takeIf { it.isJsonPrimitive }?.asInt ?: -1

    private fun JsonObject.getAsDoubleOrZero(key: String): Double =
        get(key)?.takeIf { it.isJsonPrimitive }?.asDouble ?: 0.0

    private fun JsonObject.getLongOrZero(key: String): Long =
        get(key)?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
}
