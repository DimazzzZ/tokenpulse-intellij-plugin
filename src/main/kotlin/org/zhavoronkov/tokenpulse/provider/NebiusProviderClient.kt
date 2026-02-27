package org.zhavoronkov.tokenpulse.provider

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.zhavoronkov.tokenpulse.model.Balance
import org.zhavoronkov.tokenpulse.model.BalanceSnapshot
import org.zhavoronkov.tokenpulse.model.Credits
import org.zhavoronkov.tokenpulse.model.ProviderId
import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.settings.Account
import java.math.BigDecimal

/**
 * Provider client for Nebius AI Studio (Token Factory).
 *
 * Nebius does not expose a billing API accessible via API key. Balance information is
 * retrieved from the internal billing gateway used by the Token Factory web UI.
 *
 * ## Authentication
 * The stored secret is a JSON blob produced by [NebiusSessionParser]:
 * ```json
 * {
 *   "appSession": "<__Host-app_session cookie value>",
 *   "csrfCookie": "<__Host-psifi.x-csrf-token cookie value>",
 *   "csrfToken":  "<x-csrf-token header value>",
 *   "parentId":   "<contract-... id>"
 * }
 * ```
 * This session is captured automatically via the embedded browser login flow in
 * [NebiusConnectDialog] — users never need to touch DevTools.
 *
 * ## Endpoints
 * - `POST /api-mfe/billing/gateway/root/billingActs/getCurrentTrial`
 *   Returns trial billing state: limit, spent, days left.
 *
 * ## Balance mapping
 * - `credits.total`     = `spec.netConsumptionLimit`
 * - `credits.remaining` = `spec.netConsumptionLimit - status.netConsumptionSpent`
 */
class NebiusProviderClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val gson: Gson = Gson(),
    private val baseUrl: String = "https://tokenfactory.nebius.com"
) : ProviderClient {

    companion object {
        private const val BILLING_ENDPOINT =
            "/api-mfe/billing/gateway/root/billingActs/getCurrentTrial"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_TOO_MANY_REQUESTS = 429
    }

    override fun fetchBalance(account: Account, secret: String): ProviderResult {
        return try {
            val session = parseSession(secret)
                ?: return ProviderResult.Failure.AuthError(
                    "Nebius billing session is missing or invalid. " +
                        "Please reconnect via Settings → Accounts → Edit."
                )

            val payload = gson.toJson(mapOf("parentId" to session.parentId!!))
            val body = payload.toRequestBody(JSON_MEDIA_TYPE)

            val request = Request.Builder()
                .url("$baseUrl$BILLING_ENDPOINT")
                .post(body)
                .header("accept", "application/json")
                .header("content-type", "application/json")
                .header("x-requested-with", "XMLHttpRequest")
                .header("x-csrf-token", session.csrfToken!!)
                .header(
                    "cookie",
                    "__Host-app_session=${session.appSession!!}; " +
                        "__Host-psifi.x-csrf-token=${session.csrfCookie!!}"
                )
                .build()

            httpClient.newCall(request).execute().use { response ->
                when {
                    response.code == HTTP_FORBIDDEN || response.code == HTTP_UNAUTHORIZED -> {
                        ProviderResult.Failure.AuthError(
                            "Nebius billing session expired. " +
                                "Please reconnect via Settings → Accounts → Edit."
                        )
                    }
                    response.code == HTTP_TOO_MANY_REQUESTS -> {
                        ProviderResult.Failure.RateLimited("Nebius rate limit exceeded")
                    }
                    !response.isSuccessful -> {
                        ProviderResult.Failure.UnknownError(
                            "Nebius billing error: ${response.code} ${response.message}"
                        )
                    }
                    else -> {
                        val bodyStr = response.body?.string()
                            ?: return ProviderResult.Failure.ParseError("Empty response body")
                        parseTrialResponse(account, bodyStr)
                    }
                }
            }
        } catch (e: JsonSyntaxException) {
            ProviderResult.Failure.ParseError("Failed to parse Nebius response", e)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            ProviderResult.Failure.NetworkError("Failed to connect to Nebius", e)
        }
    }

    override fun testCredentials(account: Account, secret: String): ProviderResult =
        fetchBalance(account, secret)

    private fun parseSession(secret: String): NebiusSession? {
        return try {
            val session = gson.fromJson(secret, NebiusSession::class.java)
            if (session.appSession.isNullOrBlank() ||
                session.csrfCookie.isNullOrBlank() ||
                session.csrfToken.isNullOrBlank() ||
                session.parentId.isNullOrBlank()
            ) null else session
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            null
        }
    }

    private fun parseTrialResponse(account: Account, body: String): ProviderResult {
        val resp = gson.fromJson(body, TrialResponse::class.java)
        val spec = resp.spec ?: return ProviderResult.Failure.ParseError("Missing spec in Nebius response")
        val status = resp.status ?: return ProviderResult.Failure.ParseError("Missing status in Nebius response")

        val total = spec.netConsumptionLimit?.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val spent = status.netConsumptionSpent?.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val remaining = (total - spent).coerceAtLeast(BigDecimal.ZERO)

        val credits = Credits(total = total, remaining = remaining)

        return ProviderResult.Success(
            BalanceSnapshot(
                accountId = account.id,
                providerId = ProviderId.NEBIUS,
                balance = Balance(credits = credits, tokens = null)
            )
        )
    }

    // ── JSON models ────────────────────────────────────────────────────────

    data class NebiusSession(
        val appSession: String? = null,
        val csrfCookie: String? = null,
        val csrfToken: String? = null,
        val parentId: String? = null
    )

    private data class TrialResponse(
        val spec: TrialSpec? = null,
        val status: TrialStatus? = null
    )

    private data class TrialSpec(
        val netConsumptionLimit: String? = null,
        val limitExceeded: Boolean? = null,
        val switchedToPaid: Boolean? = null
    )

    private data class TrialStatus(
        val netConsumptionSpent: String? = null,
        val daysLeft: String? = null,
        val daysLimit: String? = null
    )
}
