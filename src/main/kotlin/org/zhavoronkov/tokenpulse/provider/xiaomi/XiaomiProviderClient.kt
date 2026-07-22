package org.zhavoronkov.tokenpulse.provider.xiaomi

import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import org.zhavoronkov.tokenpulse.model.Balance
import org.zhavoronkov.tokenpulse.model.BalanceSnapshot
import org.zhavoronkov.tokenpulse.model.ConnectionType
import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.provider.HttpErrorHandler
import org.zhavoronkov.tokenpulse.provider.ProviderClient
import org.zhavoronkov.tokenpulse.provider.SessionParser
import org.zhavoronkov.tokenpulse.settings.Account
import org.zhavoronkov.tokenpulse.utils.TokenPulseLogger
import java.time.Instant

/**
 * Provider client for Xiaomi MiMo platform.
 *
 * Serves the unified [ConnectionType.XIAOMI] (and, transitionally, any legacy
 * XIAOMI_API / XIAOMI_TOKEN_PLAN accounts not yet migrated). A single fetch
 * queries BOTH the pay-as-you-go balance endpoint and the Token Plan usage
 * endpoint and merges them into one snapshot carrying dollar credits and/or
 * token credits.
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
 *   "ph": "...",
 *   "passToken": "...",
 *   "cUserId": "..."
 * }
 * ```
 *
 * The platform cookies (`serviceToken`/`slh`/`ph`) are short-lived (~1 day). The
 * `passToken` (+ `userId`/`cUserId`) is the long-lived Xiaomi Passport credential
 * on `account.xiaomi.com` that [XiaomiSessionRefresher] uses to silently mint a
 * fresh platform session on expiry (see that class for the flow).
 *
 * ## Endpoints
 * - `GET /api/v1/balance` — Account balance (pay-as-you-go)
 * - `GET /api/v1/tokenPlan/usage` — Token Plan Credits usage
 * - `GET /api/v1/tokenPlan/current` — Current plan info
 */
class XiaomiProviderClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val gson: Gson = Gson(),
    private val baseUrl: String = XIAOMI_PLATFORM_URL,
    private val refresher: XiaomiSessionRefresher = XiaomiSessionRefresher(httpClient, gson, baseUrl),
    private val sessionWriter: (accountId: String, newSecretJson: String) -> Unit = { _, _ -> }
) : ProviderClient {

    override fun fetchBalance(account: Account, secret: String): ProviderResult {
        val traceId = java.util.UUID.randomUUID().toString().substring(0, 8)
        TokenPulseLogger.Provider.debug("[$traceId] Xiaomi fetchBalance: accountId=${account.id}")

        val session = parseSession(secret)
            ?: return ProviderResult.Failure.AuthError(
                "Invalid Xiaomi session. Please re-capture your session from platform.xiaomimimo.com"
            )

        // Unified XIAOMI (and any not-yet-migrated legacy types) route through
        // the merged path: query BOTH endpoints, share a single silent-refresh
        // retry, compose one snapshot with whichever parts we got.
        return when (account.connectionType) {
            ConnectionType.XIAOMI,
            ConnectionType.XIAOMI_API,
            ConnectionType.XIAOMI_TOKEN_PLAN -> fetchMerged(session, account, traceId)
            else -> ProviderResult.Failure.AuthError("Unsupported connection type: ${account.connectionType}")
        }
    }

    override fun testCredentials(account: Account, secret: String): ProviderResult =
        fetchBalance(account, secret)

    /**
     * Query both `/api/v1/balance` (dollar) and `/api/v1/tokenPlan/usage`
     * (Token Plan credits) and compose one [ProviderResult] carrying whichever
     * parts we got.
     *
     * Auth-refresh policy (shared across both endpoints):
     * - First pass: call both endpoints once with the current session.
     * - If either came back as [ProviderResult.Failure.AuthError] AND the
     *   session carries a `passToken`, attempt exactly ONE silent re-login via
     *   [XiaomiSessionRefresher], persist the new session, then retry only the
     *   endpoint(s) that failed with auth.
     * - Compose: any successful endpoint contributes its slice
     *   ([Credits] and/or [Tokens]) plus its metadata to a single
     *   [BalanceSnapshot] stamped as [ConnectionType.XIAOMI].
     *
     * Failure semantics: return [ProviderResult.Failure.AuthError] only when
     * BOTH endpoints failed with auth after the refresh attempt. If at least
     * one endpoint succeeded, return [ProviderResult.Success] with the parts
     * present — a non-auth failure of the other endpoint is silently dropped
     * so we still surface the balance data we have. If both endpoints failed
     * for non-auth reasons, propagate the first failure.
     */
    private fun fetchMerged(session: XiaomiSession, account: Account, traceId: String): ProviderResult {
        TokenPulseLogger.Provider.debug("[$traceId] Xiaomi merged fetch (balance + tokenPlan)")

        var balancePart = fetchPart(session, PATH_BALANCE, XiaomiResponseParser::parseApiBalance)
        var tokenPart = fetchPart(session, PATH_TOKEN_PLAN, XiaomiResponseParser::parseTokenPlanUsage)

        // Shared silent-refresh retry when either endpoint reported auth failure.
        val balanceAuthFailed = balancePart is XiaomiResponseParser.BalancePart.Failure &&
            balancePart.error is ProviderResult.Failure.AuthError
        val tokenAuthFailed = tokenPart is XiaomiResponseParser.BalancePart.Failure &&
            tokenPart.error is ProviderResult.Failure.AuthError

        if (balanceAuthFailed || tokenAuthFailed) {
            val refreshed = refresher.refresh(session)
            if (refreshed != null) {
                sessionWriter(account.id, gson.toJson(refreshed))
                TokenPulseLogger.Provider.debug(
                    "[$traceId] Xiaomi session refreshed for account=${account.id}; retrying failed endpoint(s)"
                )
                if (balanceAuthFailed) {
                    balancePart = fetchPart(refreshed, PATH_BALANCE, XiaomiResponseParser::parseApiBalance)
                }
                if (tokenAuthFailed) {
                    tokenPart = fetchPart(refreshed, PATH_TOKEN_PLAN, XiaomiResponseParser::parseTokenPlanUsage)
                }
            }
        }

        return composeSnapshot(account, balancePart, tokenPart)
    }

    /** Execute one endpoint and parse the body into a [XiaomiResponseParser.BalancePart]. */
    private fun fetchPart(
        session: XiaomiSession,
        path: String,
        parse: (JsonObject) -> XiaomiResponseParser.BalancePart
    ): XiaomiResponseParser.BalancePart {
        val request = buildRequest(session, path)
        return try {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return XiaomiResponseParser.BalancePart.Failure(
                        HttpErrorHandler.mapHttpError(response.code, "Xiaomi")
                    )
                }
                // A followed login-page redirect returns HTML with a 2xx status;
                // treat it as auth failure (same rule as the legacy path).
                if (body.trimStart().startsWith("<")) {
                    return XiaomiResponseParser.BalancePart.Failure(
                        ProviderResult.Failure.AuthError("Xiaomi session expired. Please reconnect.")
                    )
                }
                val json = gson.fromJson(body, JsonObject::class.java)
                parse(json)
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            XiaomiResponseParser.BalancePart.Failure(
                ProviderResult.Failure.NetworkError("Failed to connect to Xiaomi: ${e.message}")
            )
        }
    }

    private fun composeSnapshot(
        account: Account,
        balancePart: XiaomiResponseParser.BalancePart,
        tokenPart: XiaomiResponseParser.BalancePart
    ): ProviderResult {
        val credits = (balancePart as? XiaomiResponseParser.BalancePart.Credits)
        val tokens = (tokenPart as? XiaomiResponseParser.BalancePart.TokensPart)

        // Both failed: return AuthError if either was auth, else the first failure.
        if (credits == null && tokens == null) {
            val balanceFailure = (balancePart as XiaomiResponseParser.BalancePart.Failure).error
            val tokenFailure = (tokenPart as XiaomiResponseParser.BalancePart.Failure).error
            return when {
                balanceFailure is ProviderResult.Failure.AuthError -> balanceFailure
                tokenFailure is ProviderResult.Failure.AuthError -> tokenFailure
                else -> balanceFailure
            }
        }

        val merged = mutableMapOf<String, String>()
        credits?.metadata?.let { merged.putAll(it) }
        tokens?.metadata?.let { merged.putAll(it) }

        return ProviderResult.Success(
            BalanceSnapshot(
                accountId = account.id,
                connectionType = ConnectionType.XIAOMI,
                balance = Balance(credits = credits?.credits, tokens = tokens?.tokens),
                timestamp = Instant.now(),
                metadata = merged.toMap()
            )
        )
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

    data class XiaomiSession(
        val serviceToken: String? = null,
        val userId: String? = null,
        val slh: String? = null,
        val ph: String? = null,
        val passToken: String? = null,
        val cUserId: String? = null
    )

    companion object {
        const val XIAOMI_PLATFORM_URL = "https://platform.xiaomimimo.com"
        private const val PATH_BALANCE = "/api/v1/balance"
        private const val PATH_TOKEN_PLAN = "/api/v1/tokenPlan/usage"
    }
}
