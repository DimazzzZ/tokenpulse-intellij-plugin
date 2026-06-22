package org.zhavoronkov.tokenpulse.provider.xiaomi

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
import org.zhavoronkov.tokenpulse.provider.HttpErrorHandler
import org.zhavoronkov.tokenpulse.provider.ProviderClient
import org.zhavoronkov.tokenpulse.provider.SessionParser
import org.zhavoronkov.tokenpulse.settings.Account
import org.zhavoronkov.tokenpulse.utils.TokenPulseLogger
import java.math.BigDecimal
import java.time.Instant

/**
 * Provider client for Xiaomi MiMo platform.
 *
 * Supports two connection types:
 * - [ConnectionType.XIAOMI_API]: Pay-as-you-go, balance in USD
 * - [ConnectionType.XIAOMI_TOKEN_PLAN]: Subscription with Credits quota
 *
 * The Xiaomi API (`api.xiaomimimo.com`) is OpenAI-compatible for chat completions,
 * but has no balance/usage endpoints via API key. Balance is tracked via the
 * Xiaomi platform (`platform.xiaomimimo.com`) using session capture (like Nebius).
 *
 * ## Session capture
 * The stored secret is a JSON blob with Xiaomi platform session cookies:
 * ```json
 * {
 *   "serviceToken": "...",
 *   "userId": "...",
 *   "slh": "...",
 *   "ph": "..."
 * }
 * ```
 *
 * ## Endpoints
 * - `GET /api/v1/balance` — Account balance (pay-as-you-go)
 * - `GET /api/v1/tokenPlan/usage` — Token Plan Credits usage
 * - `GET /api/v1/tokenPlan/current` — Current plan info
 */
class XiaomiProviderClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val gson: Gson = Gson(),
    private val baseUrl: String = XIAOMI_PLATFORM_URL
) : ProviderClient {

    override fun fetchBalance(account: Account, secret: String): ProviderResult {
        val traceId = java.util.UUID.randomUUID().toString().substring(0, 8)
        TokenPulseLogger.Provider.debug("[$traceId] Xiaomi fetchBalance: accountId=${account.id}")

        val session = parseSession(secret)
            ?: return ProviderResult.Failure.AuthError(
                "Invalid Xiaomi session. Please re-capture your session from platform.xiaomimimo.com"
            )

        return when (account.connectionType) {
            ConnectionType.XIAOMI_API -> fetchApiBalance(session, account, traceId)
            ConnectionType.XIAOMI_TOKEN_PLAN -> fetchTokenPlanUsage(session, account, traceId)
            else -> ProviderResult.Failure.AuthError("Unsupported connection type: ${account.connectionType}")
        }
    }

    override fun testCredentials(account: Account, secret: String): ProviderResult =
        fetchBalance(account, secret)

    private fun fetchApiBalance(session: XiaomiSession, account: Account, traceId: String): ProviderResult {
        TokenPulseLogger.Provider.debug("[$traceId] Fetching Xiaomi API balance")
        val request = buildRequest(session, "/api/v1/balance")

        return executeRequest(request) { body ->
            val json = gson.fromJson(body, JsonObject::class.java)
            val code = json.getAsJsonPrimitive("code")?.asInt ?: -1

            if (code != 0) {
                val message = json.getAsJsonPrimitive("message")?.asString ?: "Unknown error"
                return@executeRequest ProviderResult.Failure.AuthError("Xiaomi API error: $message")
            }

            val data = json.getAsJsonObject("data")
            val balance = data.getAsJsonPrimitive("balance")?.asString?.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val giftBalance = data.getAsJsonPrimitive("giftBalance")?.asString?.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val cashBalance = data.getAsJsonPrimitive("cashBalance")?.asString?.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val currency = data.getAsJsonPrimitive("currency")?.asString ?: "USD"

            TokenPulseLogger.Provider.debug(
                "[$traceId] Xiaomi balance: $balance $currency (gift=$giftBalance, cash=$cashBalance)"
            )

            ProviderResult.Success(
                BalanceSnapshot(
                    accountId = account.id,
                    connectionType = ConnectionType.XIAOMI_API,
                    balance = Balance(
                        credits = Credits(
                            remaining = balance,
                            total = null,
                            used = null
                        )
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
    }

    private fun fetchTokenPlanUsage(
        session: XiaomiSession,
        account: Account,
        traceId: String
    ): ProviderResult {
        TokenPulseLogger.Provider.debug("[$traceId] Fetching Xiaomi Token Plan usage")
        val request = buildRequest(session, "/api/v1/tokenPlan/usage")

        return executeRequest(request) { body ->
            val json = gson.fromJson(body, JsonObject::class.java)
            val code = json.getAsJsonPrimitive("code")?.asInt ?: -1

            if (code != 0) {
                val message = json.getAsJsonPrimitive("message")?.asString ?: "Unknown error"
                return@executeRequest ProviderResult.Failure.AuthError("Xiaomi API error: $message")
            }

            val data = json.getAsJsonObject("data")
            val monthUsage = data.getAsJsonObject("monthUsage")
            val usagePercent = monthUsage?.getAsJsonPrimitive("percent")?.asDouble ?: 0.0

            val items = monthUsage?.getAsJsonArray("items")
            var usedCredits = 0L
            var totalCredits = 0L
            if (items != null && items.size() > 0) {
                val item = items[0].asJsonObject
                usedCredits = item.getAsJsonPrimitive("used")?.asLong ?: 0L
                totalCredits = item.getAsJsonPrimitive("limit")?.asLong ?: 0L
            }

            TokenPulseLogger.Provider.debug(
                "[$traceId] Token Plan: ${(usagePercent * 100).toInt()}% used, " +
                    "$usedCredits/$totalCredits Credits"
            )

            ProviderResult.Success(
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
                        "sessionUsed" to (usagePercent * 100).toInt().toString(),
                        "planUsed" to usedCredits.toString(),
                        "planTotal" to totalCredits.toString()
                    )
                )
            )
        }
    }

    private fun parseSession(secret: String): XiaomiSession? =
        SessionParser.parse(
            secret = secret,
            sessionClass = XiaomiSession::class.java,
            validator = { !it.serviceToken.isNullOrBlank() },
            providerName = "Xiaomi",
            gson = gson
        )

    private fun buildRequest(session: XiaomiSession, path: String): Request {
        val cookieBuilder = StringBuilder()
        session.serviceToken?.let { cookieBuilder.append("api-platform_serviceToken=\"$it\"; ") }
        session.userId?.let { cookieBuilder.append("userId=$it; ") }
        session.slh?.let { cookieBuilder.append("api-platform_slh=\"$it\"; ") }
        session.ph?.let { cookieBuilder.append("api-platform_ph=\"$it\"") }

        return Request.Builder()
            .url("$baseUrl$path")
            .header("Accept", "*/*")
            .header("Content-Type", "application/json")
            .header("Cookie", cookieBuilder.toString().trimEnd(';', ' '))
            .header("x-timezone", java.util.TimeZone.getDefault().id)
            .build()
    }

    private fun executeRequest(request: Request, parser: (String) -> ProviderResult): ProviderResult {
        val response = httpClient.newCall(request).execute()
        val body = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            return HttpErrorHandler.mapHttpError(response.code, "Xiaomi")
        }

        return parser(body)
    }

    data class XiaomiSession(
        val serviceToken: String? = null,
        val userId: String? = null,
        val slh: String? = null,
        val ph: String? = null
    )

    companion object {
        const val XIAOMI_PLATFORM_URL = "https://platform.xiaomimimo.com"
        const val XIAOMI_API_URL = "https://api.xiaomimimo.com/v1"
        const val XIAOMI_TOKEN_PLAN_SGP_URL = "https://token-plan-sgp.xiaomimimo.com/v1"
    }
}
