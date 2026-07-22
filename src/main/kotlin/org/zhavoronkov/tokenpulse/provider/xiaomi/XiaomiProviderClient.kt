package org.zhavoronkov.tokenpulse.provider.xiaomi

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.zhavoronkov.tokenpulse.model.ConnectionType
import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.provider.HttpErrorHandler
import org.zhavoronkov.tokenpulse.provider.ProviderClient
import org.zhavoronkov.tokenpulse.provider.SessionParser
import org.zhavoronkov.tokenpulse.settings.Account
import org.zhavoronkov.tokenpulse.utils.TokenPulseLogger

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
        return executeRequest(session, account, "/api/v1/balance") { body ->
            val json = gson.fromJson(body, JsonObject::class.java)
            XiaomiResponseParser.parseApiBalance(json, account)
        }
    }

    private fun fetchTokenPlanUsage(
        session: XiaomiSession,
        account: Account,
        traceId: String
    ): ProviderResult {
        TokenPulseLogger.Provider.debug("[$traceId] Fetching Xiaomi Token Plan usage")
        return executeRequest(session, account, "/api/v1/tokenPlan/usage") { body ->
            val json = gson.fromJson(body, JsonObject::class.java)
            XiaomiResponseParser.parseTokenPlanUsage(json, account)
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

    /**
     * Execute [path] for [session], with a single silent-refresh retry on expiry.
     *
     * Expiry is detected as HTTP 401/403 OR a successful response whose body is HTML
     * (a login page the shared client may have followed a redirect to) rather than
     * the expected JSON. On expiry, if the session carries a `passToken`, we attempt
     * [XiaomiSessionRefresher.refresh]; on success the new session is persisted via
     * [sessionWriter] and the request is retried exactly once with the fresh cookies.
     */
    private fun executeRequest(
        session: XiaomiSession,
        account: Account,
        path: String,
        parser: (String) -> ProviderResult
    ): ProviderResult {
        val first = executeOnce(session, path, parser)
        if (first !is ProviderResult.Failure.AuthError) {
            return first
        }

        // Expired: attempt a one-shot silent re-login using the passport cookie.
        val refreshed = refresher.refresh(session) ?: return first
        sessionWriter(account.id, gson.toJson(refreshed))
        TokenPulseLogger.Provider.debug("Xiaomi session refreshed for account=${account.id}, retrying $path")
        return executeOnce(refreshed, path, parser)
    }

    private fun executeOnce(
        session: XiaomiSession,
        path: String,
        parser: (String) -> ProviderResult
    ): ProviderResult {
        val request = buildRequest(session, path)
        return try {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    return HttpErrorHandler.mapHttpError(response.code, "Xiaomi")
                }

                // A followed login-page redirect returns HTML with a 2xx status; treat
                // it as an auth failure rather than letting it become a ParseError.
                if (body.trimStart().startsWith("<")) {
                    return ProviderResult.Failure.AuthError("Xiaomi session expired. Please reconnect.")
                }

                parser(body)
            }
        } catch (e: JsonSyntaxException) {
            ProviderResult.Failure.ParseError("Failed to parse Xiaomi response: ${e.message}")
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            ProviderResult.Failure.NetworkError("Failed to connect to Xiaomi: ${e.message}")
        }
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
    }
}
