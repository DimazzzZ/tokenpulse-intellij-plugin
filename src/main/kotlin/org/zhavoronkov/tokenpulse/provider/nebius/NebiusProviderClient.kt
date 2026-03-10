package org.zhavoronkov.tokenpulse.provider.nebius

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.zhavoronkov.tokenpulse.model.Balance
import org.zhavoronkov.tokenpulse.model.BalanceSnapshot
import org.zhavoronkov.tokenpulse.model.ConnectionType
import org.zhavoronkov.tokenpulse.model.Credits
import org.zhavoronkov.tokenpulse.model.NebiusBalanceBreakdown
import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.provider.ProviderClient
import org.zhavoronkov.tokenpulse.settings.Account
import org.zhavoronkov.tokenpulse.utils.TokenPulseLogger
import java.math.BigDecimal

/**
 * Provider client for Nebius AI Studio (Token Factory).
 *
 * Nebius does not expose a billing API accessible via API key. Balance information is
 * retrieved from the internal billing gateway used by the Token Factory web UI.
 *
 * ## Authentication
 * The stored secret is a JSON blob produced by session capture:
 * ```json
 * {
 *   "appSession": "<__Host-app_session cookie value>",
 *   "csrfCookie": "<__Host-psifi.x-csrf-token cookie value>",
 *   "csrfToken":  "<x-csrf-token header value>",
 *   "parentId":   "<contract-... id>"
 * }
 * ```
 *
 * ## Endpoints
 * - `POST /api-mfe/billing/gateway/root/billingActs/getCurrentTrial` → Trial billing state
 * - `POST /api-mfe/billing/gateway/root/customers/getBalance` → Paid balance
 * - `POST /connect/nebius.iam.v1.AiTenantService/List` → Tenant info
 *
 * ## Balance mapping
 * - `credits.total` = `spec.netConsumptionLimit`
 * - `credits.remaining` = `spec.netConsumptionLimit - status.netConsumptionSpent`
 */
class NebiusProviderClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val gson: Gson = Gson(),
    private val baseUrl: String = NEBIUS_BASE_URL
) : ProviderClient {

    internal var commandExecutor: CommandExecutor = DefaultCommandExecutor

    private val baseClient = httpClient.newBuilder().apply {
        val devTimeout = if (System.getProperty("tokenpulse.debug", "false").toBoolean()) 12L else null
        devTimeout?.let {
            connectTimeout(java.time.Duration.ofSeconds(it))
            readTimeout(java.time.Duration.ofSeconds(it))
            callTimeout(java.time.Duration.ofSeconds(it + 5))
        }
    }.build()

    private val http1Client = baseClient.newBuilder()
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        .build()

    private val directClient = baseClient.newBuilder()
        .proxy(java.net.Proxy.NO_PROXY)
        .build()

    override fun fetchBalance(account: Account, secret: String): ProviderResult {
        val traceId = java.util.UUID.randomUUID().toString().substring(0, 8)
        TokenPulseLogger.trace("NEBIUS", account.id, traceId, "start", "Starting balance fetch")

        val session = parseSession(secret)
        if (session == null) {
            TokenPulseLogger.trace(
                "NEBIUS",
                account.id,
                traceId,
                "error",
                "Session parse failed",
                mapOf("secretLength" to secret.length)
            )
            return ProviderResult.Failure.AuthError(
                "Nebius billing session is missing or invalid. Please reconnect via Settings → Accounts → Edit."
            )
        }

        val testMode = System.getProperty("tokenpulse.testMode", "false").toBoolean()
        val tenantName: String? = if (!testMode) fetchTenantName(session, account, traceId) else null

        val paidResult = fetchPaidBalance(session, account, traceId)
        val paidSuccess = paidResult as? ProviderResult.Success
        val paidHardFailure = paidResult is ProviderResult.Failure.AuthError ||
            paidResult is ProviderResult.Failure.RateLimited

        if (paidHardFailure) {
            return paidResult
        }

        val trialResult = fetchTrialBalance(session, account, traceId)
        val trialSuccess = trialResult as? ProviderResult.Success

        return combineResults(account, paidSuccess, trialSuccess, tenantName, trialResult, traceId)
    }

    override fun testCredentials(account: Account, secret: String): ProviderResult =
        fetchBalance(account, secret)

    private fun combineResults(
        account: Account,
        paidSuccess: ProviderResult.Success?,
        trialSuccess: ProviderResult.Success?,
        tenantName: String?,
        trialResult: ProviderResult,
        traceId: String
    ): ProviderResult {
        return when {
            paidSuccess != null && trialSuccess != null -> {
                val paidRemaining = paidSuccess.snapshot.balance.credits?.remaining ?: BigDecimal.ZERO
                val trialRemaining = trialSuccess.snapshot.balance.credits?.remaining ?: BigDecimal.ZERO
                val combinedRemaining = paidRemaining + trialRemaining
                val combinedTotal = (paidSuccess.snapshot.balance.credits?.total ?: BigDecimal.ZERO) +
                    (trialSuccess.snapshot.balance.credits?.total ?: BigDecimal.ZERO)

                TokenPulseLogger.trace(
                    "NEBIUS",
                    account.id,
                    traceId,
                    "combined",
                    "Both paid and trial succeeded",
                    mapOf("paid" to paidRemaining, "trial" to trialRemaining, "total" to combinedRemaining)
                )

                ProviderResult.Success(
                    BalanceSnapshot(
                        accountId = account.id,
                        connectionType = ConnectionType.NEBIUS_BILLING,
                        balance = Balance(credits = Credits(total = combinedTotal, remaining = combinedRemaining)),
                        nebiusBreakdown = NebiusBalanceBreakdown(
                            paidRemaining = paidRemaining,
                            trialRemaining = trialRemaining,
                            tenantName = tenantName
                        )
                    )
                )
            }
            paidSuccess != null -> {
                val paidRemaining = paidSuccess.snapshot.balance.credits?.remaining ?: BigDecimal.ZERO
                ProviderResult.Success(
                    paidSuccess.snapshot.copy(
                        nebiusBreakdown = NebiusBalanceBreakdown(
                            paidRemaining = paidRemaining,
                            trialRemaining = null,
                            tenantName = tenantName
                        )
                    )
                )
            }
            trialSuccess != null -> {
                val trialRemaining = trialSuccess.snapshot.balance.credits?.remaining ?: BigDecimal.ZERO
                ProviderResult.Success(
                    trialSuccess.snapshot.copy(
                        nebiusBreakdown = NebiusBalanceBreakdown(
                            paidRemaining = null,
                            trialRemaining = trialRemaining,
                            tenantName = tenantName
                        )
                    )
                )
            }
            else -> {
                TokenPulseLogger.trace("NEBIUS", account.id, traceId, "both_failed", "Both endpoints failed")
                trialResult
            }
        }
    }

    private fun fetchPaidBalance(session: NebiusSession, account: Account, traceId: String): ProviderResult {
        val contractId = session.parentId ?: return ProviderResult.Failure.AuthError(
            "Missing contractId in Nebius session. Please reconnect via Settings → Accounts → Edit."
        )
        return executeBalanceRequest(
            session,
            account,
            traceId,
            BALANCE_ENDPOINT,
            contractId,
            ::parsePaidBalanceResponse
        )
    }

    private fun fetchTrialBalance(session: NebiusSession, account: Account, traceId: String): ProviderResult {
        if (!validateSession(session)) {
            return ProviderResult.Failure.AuthError(
                "Nebius session is incomplete. Please reconnect via Settings → Accounts → Edit."
            )
        }
        return executeBalanceRequest(
            session,
            account,
            traceId,
            TRIAL_ENDPOINT,
            session.parentId ?: "",
            ::parseTrialResponse
        )
    }

    private fun executeBalanceRequest(
        session: NebiusSession,
        account: Account,
        traceId: String,
        endpoint: String,
        contractId: String,
        parser: (Account, String) -> ProviderResult
    ): ProviderResult {
        val hasParityData = !session.rawCookieHeader.isNullOrBlank()
        val errors = mutableListOf<String>()

        // Strategy sequence: NativeCurl → Parity → Constructed → Direct
        if (hasParityData) {
            tryStrategy("NativeCurl", account, traceId) {
                executeWithNativeCurl(session, endpoint, contractId, parser, account)
            }?.let { return it }?.also { errors.add("NativeCurl: failed") }

            tryStrategy("Parity+Standard", account, traceId) {
                val request = buildRequest(session, endpoint, contractId, useParity = true)
                executeWithClient(baseClient, request, account, parser)
            }?.let { return it }?.also { errors.add("Parity+Standard: failed") }

            tryStrategy("Parity+HTTP/1.1", account, traceId) {
                val request = buildRequest(session, endpoint, contractId, useParity = true)
                executeWithClient(http1Client, request, account, parser)
            }?.let { return it }?.also { errors.add("Parity+HTTP/1.1: failed") }
        }

        tryStrategy("Constructed+Standard", account, traceId) {
            val request = buildRequest(session, endpoint, contractId, useParity = false)
            executeWithClient(baseClient, request, account, parser)
        }?.let { return it }?.also { errors.add("Constructed+Standard: failed") }

        tryStrategy("Direct", account, traceId) {
            val request = buildRequest(session, endpoint, contractId, useParity = hasParityData)
            executeWithClient(directClient, request, account, parser)
        }?.let { return it }?.also { errors.add("Direct: failed") }

        return ProviderResult.Failure.NetworkError("Failed to connect to Nebius: ${errors.joinToString(", ")}")
    }

    private inline fun tryStrategy(
        name: String,
        account: Account,
        traceId: String,
        block: () -> ProviderResult
    ): ProviderResult? {
        return try {
            val result = block()
            if (result is ProviderResult.Success) result else null
        } catch (e: Exception) {
            TokenPulseLogger.trace(
                "NEBIUS",
                account.id,
                traceId,
                "strategy_fail",
                "$name failed",
                mapOf("error" to e.javaClass.simpleName)
            )
            null
        }
    }

    private fun executeWithClient(
        client: OkHttpClient,
        request: Request,
        account: Account,
        parser: (Account, String) -> ProviderResult
    ): ProviderResult {
        return client.newCall(request).execute().use { response ->
            when {
                response.code in listOf(HTTP_FORBIDDEN, HTTP_UNAUTHORIZED) ->
                    ProviderResult.Failure.AuthError("Nebius session expired. Please reconnect.")
                response.code == HTTP_TOO_MANY_REQUESTS ->
                    ProviderResult.Failure.RateLimited("Nebius rate limit exceeded")
                !response.isSuccessful ->
                    ProviderResult.Failure.UnknownError("Nebius error: ${response.code}")
                else -> {
                    val body = response.body?.string()
                        ?: return ProviderResult.Failure.ParseError("Empty response body")
                    parser(account, body)
                }
            }
        }
    }

    private fun executeWithNativeCurl(
        session: NebiusSession,
        endpoint: String,
        contractId: String,
        parser: (Account, String) -> ProviderResult,
        account: Account
    ): ProviderResult {
        val isTrialEndpoint = endpoint == TRIAL_ENDPOINT
        val resolvedPath = if (isTrialEndpoint && !session.rawPath.isNullOrBlank()) session.rawPath else endpoint
        val url = "$baseUrl$resolvedPath"

        val cmd = mutableListOf("curl", "-v", "-s", "-X", "POST", url)

        session.capturedHeaders?.forEach { (name, value) ->
            if (name.lowercase() !in listOf("cookie", "content-type", "x-csrf-token")) {
                cmd.addAll(listOf("-H", "$name: $value"))
            }
        }
        cmd.addAll(listOf("-H", "Content-Type: application/json"))
        cmd.addAll(listOf("-H", "x-csrf-token: ${session.csrfToken ?: ""}"))

        session.rawCookieHeader?.let { cmd.addAll(listOf("-b", it)) }

        val body = if (isTrialEndpoint && !session.rawBody.isNullOrBlank()) {
            session.rawBody
        } else {
            when (endpoint) {
                BALANCE_ENDPOINT -> gson.toJson(mapOf("contractId" to contractId))
                TENANT_ENDPOINT -> "{}"
                else -> gson.toJson(mapOf("parentId" to contractId))
            }
        }
        cmd.addAll(listOf("--data-raw", body))

        val result = commandExecutor.execute(cmd)
        return if (result.exitCode == 0) {
            parser(account, result.output)
        } else {
            throw RuntimeException("curl exit code ${result.exitCode}")
        }
    }

    private fun buildRequest(
        session: NebiusSession,
        endpoint: String,
        contractId: String,
        useParity: Boolean
    ): Request {
        val isTrialEndpoint = endpoint == TRIAL_ENDPOINT
        val canUseParity = useParity && isTrialEndpoint

        val payload = if (canUseParity && !session.rawBody.isNullOrBlank()) {
            session.rawBody
        } else {
            when (endpoint) {
                BALANCE_ENDPOINT -> gson.toJson(mapOf("contractId" to contractId))
                TENANT_ENDPOINT -> "{}"
                else -> gson.toJson(mapOf("parentId" to contractId))
            }
        }

        val path = if (canUseParity && !session.rawPath.isNullOrBlank()) session.rawPath else endpoint

        return Request.Builder()
            .url("$baseUrl$path")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .apply {
                if (useParity && !session.rawCookieHeader.isNullOrBlank()) {
                    session.capturedHeaders?.forEach { (name, value) ->
                        if (name.lowercase() !in listOf("cookie", "content-type", "x-csrf-token")) {
                            header(name, value)
                        }
                    }
                    header("content-type", "application/json")
                    header("x-csrf-token", session.csrfToken ?: "")
                    header("cookie", session.rawCookieHeader)
                } else {
                    header("accept", "application/json, text/plain, */*")
                    header("content-type", "application/json")
                    header("origin", NEBIUS_BASE_URL)
                    header("referer", "$NEBIUS_BASE_URL/")
                    header("user-agent", USER_AGENT)
                    header("x-requested-with", "XMLHttpRequest")
                    header("x-csrf-token", session.csrfToken ?: "")
                    header(
                        "cookie",
                        "__Host-app_session=${session.appSession}; __Host-psifi.x-csrf-token=${session.csrfCookie}"
                    )
                }
            }
            .build()
    }

    private fun fetchTenantName(session: NebiusSession, account: Account, traceId: String): String? {
        return try {
            val request = buildRequest(session, TENANT_ENDPOINT, "", useParity = false)
            baseClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.string()?.let { parseTenantName(it) }
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            TokenPulseLogger.trace("NEBIUS", account.id, traceId, "tenant_error", "Failed to fetch tenant", mapOf())
            null
        }
    }

    private fun validateSession(session: NebiusSession): Boolean =
        !session.appSession.isNullOrBlank() &&
            !session.csrfCookie.isNullOrBlank() &&
            !session.csrfToken.isNullOrBlank() &&
            !session.parentId.isNullOrBlank()

    private fun parseSession(secret: String): NebiusSession? {
        return try {
            val session = gson.fromJson(secret, NebiusSession::class.java)
            if (validateSession(session)) session else null
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            null
        }
    }

    private fun parsePaidBalanceResponse(account: Account, body: String): ProviderResult {
        return try {
            val json = gson.fromJson(body, JsonObject::class.java)
                ?: return ProviderResult.Failure.ParseError("Empty response from Nebius API")

            val statusCode = json.get("statusCode")?.asInt
            if (statusCode != null && statusCode >= 400) {
                val code = json.get("code")?.asString ?: ""
                return when {
                    code == "EBADCSRFTOKEN" || statusCode in listOf(401, 403) ->
                        ProviderResult.Failure.AuthError("Nebius session expired. Please reconnect.")
                    statusCode == 429 -> ProviderResult.Failure.RateLimited("Nebius rate limit exceeded")
                    else -> ProviderResult.Failure.UnknownError("Nebius error $statusCode")
                }
            }

            val balanceStr = json.get("balance")?.asString
                ?: return ProviderResult.Failure.ParseError("Missing 'balance' field")

            val balanceValue = balanceStr.toBigDecimalOrNull()
                ?: return ProviderResult.Failure.ParseError("Invalid balance value: $balanceStr")

            ProviderResult.Success(
                BalanceSnapshot(
                    accountId = account.id,
                    connectionType = ConnectionType.NEBIUS_BILLING,
                    balance = Balance(credits = Credits(total = balanceValue, remaining = balanceValue))
                )
            )
        } catch (e: JsonSyntaxException) {
            ProviderResult.Failure.ParseError("Failed to parse Nebius paid balance response: ${e.message}")
        }
    }

    private fun parseTrialResponse(account: Account, body: String): ProviderResult {
        return try {
            val json = gson.fromJson(body, JsonObject::class.java)
                ?: return ProviderResult.Failure.ParseError("Empty response from Nebius API")

            val statusCode = json.get("statusCode")?.asInt
            if (statusCode != null && statusCode >= 400) {
                val code = json.get("code")?.asString ?: ""
                return when {
                    code == "EBADCSRFTOKEN" || statusCode in listOf(401, 403) ->
                        ProviderResult.Failure.AuthError("Nebius session expired. Please reconnect.")
                    statusCode == 429 -> ProviderResult.Failure.RateLimited("Nebius rate limit exceeded")
                    else -> ProviderResult.Failure.UnknownError("Nebius error $statusCode")
                }
            }

            val resp = gson.fromJson(body, TrialResponse::class.java)
            val spec = resp?.spec ?: return ProviderResult.Failure.ParseError("Missing 'spec'")
            val status = resp.status ?: return ProviderResult.Failure.ParseError("Missing 'status'")

            val total = spec.netConsumptionLimit?.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val spent = status.netConsumptionSpent?.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val remaining = (total - spent).coerceAtLeast(BigDecimal.ZERO)

            ProviderResult.Success(
                BalanceSnapshot(
                    accountId = account.id,
                    connectionType = ConnectionType.NEBIUS_BILLING,
                    balance = Balance(credits = Credits(total = total, remaining = remaining))
                )
            )
        } catch (e: JsonSyntaxException) {
            ProviderResult.Failure.ParseError("Failed to parse Nebius response: ${e.message}")
        }
    }

    private fun parseTenantName(body: String): String? {
        return try {
            val json = gson.fromJson(body, JsonObject::class.java) ?: return null
            val items = json.getAsJsonArray("items") ?: return null
            if (items.size() == 0) return null
            items[0].asJsonObject.getAsJsonObject("metadata")?.get("name")?.asString
        } catch (e: Exception) {
            null
        }
    }

    interface CommandExecutor {
        fun execute(cmd: List<String>): CommandResult
    }

    data class CommandResult(val output: String, val error: String, val exitCode: Int)

    object DefaultCommandExecutor : CommandExecutor {
        override fun execute(cmd: List<String>): CommandResult {
            val process = ProcessBuilder(cmd).start()
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            return CommandResult(output, error, exitCode)
        }
    }

    data class NebiusSession(
        val appSession: String? = null,
        val csrfCookie: String? = null,
        val csrfToken: String? = null,
        val parentId: String? = null,
        val rawCookieHeader: String? = null,
        val capturedHeaders: Map<String, String>? = null,
        val rawBody: String? = null,
        val rawPath: String? = null
    )

    private data class TrialResponse(val spec: TrialSpec? = null, val status: TrialStatus? = null)
    private data class TrialSpec(val netConsumptionLimit: String? = null)
    private data class TrialStatus(val netConsumptionSpent: String? = null)

    companion object {
        private const val NEBIUS_BASE_URL = "https://tokenfactory.nebius.com"
        private const val TRIAL_ENDPOINT = "/api-mfe/billing/gateway/root/billingActs/getCurrentTrial"
        private const val BALANCE_ENDPOINT = "/api-mfe/billing/gateway/root/customers/getBalance"
        private const val TENANT_ENDPOINT = "/connect/nebius.iam.v1.AiTenantService/List"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
    }
}
