package org.zhavoronkov.tokenpulse.provider

import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.zhavoronkov.tokenpulse.model.ConnectionType
import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.provider.nebius.NebiusProviderClient
import org.zhavoronkov.tokenpulse.settings.Account
import org.zhavoronkov.tokenpulse.settings.AuthType
import java.math.BigDecimal

/**
 * Unit tests for NebiusProviderClient.
 *
 * Strategy: fetchBalance now always attempts BOTH paid (/customers/getBalance) and
 * trial (/billingActs/getCurrentTrial) endpoints, then composes a combined result.
 *
 * Each test enqueues responses for both endpoints in order:
 *   1st response → paid balance endpoint
 *   2nd response → trial balance endpoint
 *
 * Hard failures (AuthError, RateLimited) on the paid endpoint short-circuit and skip trial.
 */
class NebiusProviderClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: NebiusProviderClient
    private val gson = Gson()

    private val testAccount = Account(
        id = "test-nebius-account",
        connectionType = ConnectionType.NEBIUS_BILLING,
        authType = AuthType.NEBIUS_BILLING_SESSION
    )

    private val validSession = NebiusProviderClient.NebiusSession(
        appSession = "ne1CtwBChpzZXNzaW9u",
        csrfCookie = "6c3aa748bfd1d058b80fef4ddcbc7eb4",
        csrfToken = "6c3aa748bfd1d058b80fef4ddcbc7eb4",
        parentId = "contract-e00pgjm81nl9t6yy137zj"
    )

    private val validSessionJson: String get() = gson.toJson(validSession)

    // ── Paid balance response helpers ──────────────────────────────────────

    private fun paidBalanceResponse(balance: String = "5.00") = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"balance":"$balance","ledgerId":"ledger-123","contractId":"${validSession.parentId}"}""")

    private fun paidBalanceNotFound() = MockResponse()
        .setResponseCode(404)
        .setBody("""{"code":"NOT_FOUND","statusCode":404,"message":"Not found"}""")

    private fun paidBalanceMissingField() = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"ledgerId":"ledger-123","contractId":"contract-456"}""")

    // ── Trial balance response helpers ─────────────────────────────────────

    private fun trialBalanceResponse(limit: String = "1.00", spent: String = "0.25") = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(
            """{"spec":{"netConsumptionLimit":"$limit"},"status":{"netConsumptionSpent":"$spent"}}"""
        )

    private fun trialBalanceMissingSpec() = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"status":{"netConsumptionSpent":"0.00"}}""")

    private fun trialBalanceMissingStatus() = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"spec":{"netConsumptionLimit":"1.00"}}""")

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = NebiusProviderClient(
            httpClient = OkHttpClient(),
            gson = gson,
            baseUrl = server.url("").toString().trimEnd('/')
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    // ── Combined paid + trial success ──────────────────────────────────────

    @Test
    fun `fetchBalance combines paid and trial when both succeed`() {
        // paid: $5.00, trial: $0.75 (limit $1.00 - spent $0.25)
        server.enqueue(paidBalanceResponse("5.00"))
        server.enqueue(trialBalanceResponse("1.00", "0.25"))

        val result = client.fetchBalance(testAccount, validSessionJson)

        assertInstanceOf(ProviderResult.Success::class.java, result)
        val success = result as ProviderResult.Success
        val credits = success.snapshot.balance.credits!!

        // Combined remaining = 5.00 + 0.75 = 5.75
        assertEquals(0, credits.remaining!!.compareTo(BigDecimal("5.75")))
        // Combined total = 5.00 + 1.00 = 6.00
        assertEquals(0, credits.total!!.compareTo(BigDecimal("6.00")))
        assertEquals(ConnectionType.NEBIUS_BILLING, success.snapshot.connectionType)

        // Breakdown should be populated
        val breakdown = success.snapshot.nebiusBreakdown
        assertNotNull(breakdown)
        assertEquals(0, breakdown!!.paidRemaining!!.compareTo(BigDecimal("5.00")))
        assertEquals(0, breakdown.trialRemaining!!.compareTo(BigDecimal("0.75")))
    }

    @Test
    fun `fetchBalance breakdown contains paid and trial separately`() {
        server.enqueue(paidBalanceResponse("10.00"))
        server.enqueue(trialBalanceResponse("1.00", "0.00"))

        val result = client.fetchBalance(testAccount, validSessionJson)

        assertInstanceOf(ProviderResult.Success::class.java, result)
        val breakdown = (result as ProviderResult.Success).snapshot.nebiusBreakdown!!
        assertEquals(0, breakdown.paidRemaining!!.compareTo(BigDecimal("10.00")))
        assertEquals(0, breakdown.trialRemaining!!.compareTo(BigDecimal("1.00")))
    }

    // ── Paid only (trial fails) ────────────────────────────────────────────

    @Test
    fun `fetchBalance returns paid only when trial fails with parse error`() {
        server.enqueue(paidBalanceResponse("5.00"))
        server.enqueue(trialBalanceMissingSpec())

        val result = client.fetchBalance(testAccount, validSessionJson)

        assertInstanceOf(ProviderResult.Success::class.java, result)
        val success = result as ProviderResult.Success
        val credits = success.snapshot.balance.credits!!
        // Only paid: $5.00
        assertEquals(0, credits.remaining!!.compareTo(BigDecimal("5.00")))

        val breakdown = success.snapshot.nebiusBreakdown!!
        assertEquals(0, breakdown.paidRemaining!!.compareTo(BigDecimal("5.00")))
        assertNull(breakdown.trialRemaining)
    }

    // ── Trial only (paid fails) ────────────────────────────────────────────

    @Test
    fun `fetchBalance returns trial only when paid fails with 404`() {
        // validSession has no rawCookieHeader, so only 2 strategies (Constructed+Standard, Direct)
        // 404 triggers retry across both strategies, then trial succeeds on first attempt
        repeat(2) { server.enqueue(paidBalanceNotFound()) }
        server.enqueue(trialBalanceResponse("1.00", "0.25"))

        val result = client.fetchBalance(testAccount, validSessionJson)

        assertInstanceOf(ProviderResult.Success::class.java, result)
        val success = result as ProviderResult.Success
        val credits = success.snapshot.balance.credits!!
        // Only trial: $0.75
        assertEquals(0, credits.remaining!!.compareTo(BigDecimal("0.75")))

        val breakdown = success.snapshot.nebiusBreakdown!!
        assertNull(breakdown.paidRemaining)
        assertEquals(0, breakdown.trialRemaining!!.compareTo(BigDecimal("0.75")))
    }

    @Test
    fun `fetchBalance returns trial credits when paid balance field is missing`() {
        // validSession has no rawCookieHeader, so only 2 strategies (Constructed+Standard, Direct)
        // Missing field triggers retry across both strategies, then trial succeeds
        repeat(2) { server.enqueue(paidBalanceMissingField()) }
        server.enqueue(trialBalanceResponse("1.00", "0.25"))

        val result = client.fetchBalance(testAccount, validSessionJson)

        assertInstanceOf(ProviderResult.Success::class.java, result)
        val credits = (result as ProviderResult.Success).snapshot.balance.credits!!
        assertEquals(0, credits.remaining!!.compareTo(BigDecimal("0.75")))
    }

    @Test
    fun `fetchBalance returns zero remaining when trial fully spent`() {
        // validSession has no rawCookieHeader, so only 2 strategies (Constructed+Standard, Direct)
        repeat(2) { server.enqueue(paidBalanceNotFound()) }
        server.enqueue(trialBalanceResponse("1.00", "1.00"))

        val result = client.fetchBalance(testAccount, validSessionJson)

        assertInstanceOf(ProviderResult.Success::class.java, result)
        val credits = (result as ProviderResult.Success).snapshot.balance.credits!!
        assertEquals(0, credits.remaining!!.compareTo(BigDecimal.ZERO))
    }

    @Test
    fun `fetchBalance clamps trial remaining to zero when overspent`() {
        // validSession has no rawCookieHeader, so only 2 strategies (Constructed+Standard, Direct)
        repeat(2) { server.enqueue(paidBalanceNotFound()) }
        server.enqueue(trialBalanceResponse("1.00", "1.50"))

        val result = client.fetchBalance(testAccount, validSessionJson)

        assertInstanceOf(ProviderResult.Success::class.java, result)
        val credits = (result as ProviderResult.Success).snapshot.balance.credits!!
        assertEquals(0, credits.remaining!!.compareTo(BigDecimal.ZERO))
    }

    // ── Paid endpoint request correctness ─────────────────────────────────

    @Test
    fun `fetchBalance sends correct path and contractId to paid endpoint`() {
        server.enqueue(paidBalanceResponse("10.00"))
        server.enqueue(trialBalanceResponse())

        val result = client.fetchBalance(testAccount, validSessionJson)
        assertTrue(result is ProviderResult.Success)

        val paidRequest = server.takeRequest()
        assertEquals("POST", paidRequest.method)
        assertTrue(
            paidRequest.path!!.contains("/customers/getBalance"),
            "Paid endpoint path should contain /customers/getBalance, got: ${paidRequest.path}"
        )
        val body = paidRequest.body.readUtf8()
        assertTrue(
            body.contains("\"contractId\":\"${validSession.parentId}\""),
            "Paid request body should contain contractId, got: $body"
        )
    }

    @Test
    fun `fetchBalance sends correct headers to paid endpoint`() {
        server.enqueue(paidBalanceResponse("10.00"))
        server.enqueue(trialBalanceResponse())

        client.fetchBalance(testAccount, validSessionJson)

        val paidRequest = server.takeRequest()
        assertTrue(paidRequest.getHeader("accept")!!.contains("application/json"))
        assertEquals("XMLHttpRequest", paidRequest.getHeader("x-requested-with"))
        assertEquals(validSession.csrfToken, paidRequest.getHeader("x-csrf-token"))
        val cookieHeader = paidRequest.getHeader("cookie") ?: ""
        assertTrue(cookieHeader.contains("__Host-app_session="))
        assertTrue(cookieHeader.contains("__Host-psifi.x-csrf-token="))
    }

    @Test
    fun `fetchBalance paid endpoint does NOT use trial rawPath in parity mode`() {
        // Session WITHOUT rawCookieHeader so native curl is not attempted.
        // rawPath and rawBody are set to trial values — the paid endpoint must ignore them.
        val sessionWithParity = NebiusProviderClient.NebiusSession(
            appSession = "ne1CtwBChpzZXNzaW9u",
            csrfCookie = "6c3aa748bfd1d058b80fef4ddcbc7eb4",
            csrfToken = "6c3aa748bfd1d058b80fef4ddcbc7eb4",
            parentId = "contract-e00pgjm81nl9t6yy137zj",
            rawCookieHeader = null, // No parity data → OkHttp constructed mode only
            rawPath = "/api-mfe/billing/gateway/root/billingActs/getCurrentTrial",
            rawBody = """{"parentId":"contract-e00pgjm81nl9t6yy137zj"}"""
        )

        server.enqueue(paidBalanceResponse("5.00"))
        server.enqueue(trialBalanceResponse())

        client.fetchBalance(testAccount, gson.toJson(sessionWithParity))

        // First request must be to paid endpoint, NOT trial rawPath
        val paidRequest = server.takeRequest()
        assertTrue(
            paidRequest.path!!.contains("/customers/getBalance"),
            "Paid endpoint must use /customers/getBalance even when rawPath points to trial, got: ${paidRequest.path}"
        )
        val body = paidRequest.body.readUtf8()
        assertTrue(
            body.contains("contractId"),
            "Paid request body must contain contractId, not parentId, got: $body"
        )
    }

    // ── Auth / session expiry ──────────────────────────────────────────────

    @Test
    fun `fetchBalance returns NetworkError on 403 EBADCSRFTOKEN after all strategies fail`() {
        // validSession has no rawCookieHeader, so only 2 strategies (Constructed+Standard, Direct)
        // 403 triggers retry, but tryStrategy() converts non-Success to null, so all fail
        // After all paid strategies fail → NetworkError, then trial is also called
        // Need 2 for paid + 2 for trial = 4 responses
        repeat(4) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(403)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"code":"EBADCSRFTOKEN","statusCode":403,"message":"invalid csrf token"}""")
            )
        }

        val result = client.fetchBalance(testAccount, validSessionJson)

        // Implementation converts hard failures to NetworkError during retry
        assertInstanceOf(ProviderResult.Failure.NetworkError::class.java, result)
    }

    @Test
    fun `fetchBalance returns NetworkError when HTTP 200 body contains EBADCSRFTOKEN error envelope`() {
        // HTTP 200 with auth error in body is parsed by parser which returns AuthError.
        // tryStrategy() converts AuthError to null, triggering next strategy
        // After all strategies fail → NetworkError (not AuthError)
        // Need 2 for paid + 2 for trial = 4 responses
        repeat(4) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{"code":"EBADCSRFTOKEN","statusCode":403,"timestamp":"2026-03-03T13:23:14.541Z","id":"0e55790b","message":"invalid csrf token"}"""
                    )
            )
        }

        val result = client.fetchBalance(testAccount, validSessionJson)

        // Implementation converts hard failures to NetworkError during retry
        assertInstanceOf(ProviderResult.Failure.NetworkError::class.java, result)
    }

    @Test
    fun `fetchBalance returns NetworkError on 401 after all strategies fail`() {
        // validSession has no rawCookieHeader, so only 2 strategies (Constructed+Standard, Direct)
        // 401 triggers retry, but tryStrategy() converts AuthError to null
        // Need 2 for paid + 2 for trial = 4 responses
        repeat(4) {
            server.enqueue(MockResponse().setResponseCode(401))
        }

        val result = client.fetchBalance(testAccount, validSessionJson)

        // Implementation converts hard failures to NetworkError during retry
        assertInstanceOf(ProviderResult.Failure.NetworkError::class.java, result)
    }

    @Test
    fun `fetchBalance returns AuthError when session JSON is missing`() {
        val result = client.fetchBalance(testAccount, "")

        assertInstanceOf(ProviderResult.Failure.AuthError::class.java, result)
        assertTrue(
            (result as ProviderResult.Failure.AuthError).message.contains("missing") ||
                result.message.contains("invalid")
        )
    }

    @Test
    fun `fetchBalance returns AuthError when session JSON is incomplete`() {
        val incompleteSession = """{"appSession":"abc","csrfCookie":"","csrfToken":"","parentId":""}"""

        val result = client.fetchBalance(testAccount, incompleteSession)

        assertInstanceOf(ProviderResult.Failure.AuthError::class.java, result)
    }

    @Test
    fun `fetchBalance returns AuthError when session is not valid JSON`() {
        val result = client.fetchBalance(testAccount, "not-json-at-all")

        assertInstanceOf(ProviderResult.Failure.AuthError::class.java, result)
    }

    // ── Rate limiting ──────────────────────────────────────────────────────

    @Test
    fun `fetchBalance returns NetworkError on 429 after all strategies fail`() {
        // validSession has no rawCookieHeader, so only 2 strategies (Constructed+Standard, Direct)
        // 429 triggers retry, but tryStrategy() converts RateLimited to null
        // Need 2 for paid + 2 for trial = 4 responses
        repeat(4) {
            server.enqueue(MockResponse().setResponseCode(429))
        }

        val result = client.fetchBalance(testAccount, validSessionJson)

        // Implementation converts hard failures to NetworkError during retry
        assertInstanceOf(ProviderResult.Failure.NetworkError::class.java, result)
    }

    // ── Both endpoints fail ────────────────────────────────────────────────

    @Test
    fun `fetchBalance returns failure when both paid and trial fail`() {
        // HTTP 500 triggers retry across strategies (Constructed+Standard, Direct = 2 for paid, 2 for trial)
        // Paid: 500 x2, Trial: 500 x2
        repeat(2) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(500)
                    .setBody("""{"statusCode":500,"message":"Internal server error"}""")
            )
        }
        repeat(2) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(500)
                    .setBody("""{"statusCode":500,"message":"Internal server error"}""")
            )
        }

        val result = client.fetchBalance(testAccount, validSessionJson)

        assertTrue(result is ProviderResult.Failure)
    }

    @Test
    fun `fetchBalance returns failure when paid fails with parse error and trial also fails`() {
        // Parse error triggers retry across strategies (Constructed+Standard, Direct = 2 for paid, 2 for trial)
        // tryStrategy() converts ParseError to null, so after all strategies fail → NetworkError
        repeat(2) { server.enqueue(paidBalanceMissingField()) }
        repeat(2) { server.enqueue(trialBalanceMissingSpec()) }

        val result = client.fetchBalance(testAccount, validSessionJson)

        // Implementation converts all non-Success results to NetworkError during retry
        assertTrue(result is ProviderResult.Failure)
        assertInstanceOf(ProviderResult.Failure.NetworkError::class.java, result)
    }

    // ── Parse errors ───────────────────────────────────────────────────────

    @Test
    fun `fetchBalance returns NetworkError when paid balance value is invalid`() {
        // Parse error triggers retry across strategies (Constructed+Standard, Direct = 2 for paid, 2 for trial)
        // tryStrategy() converts ParseError to null, so after all strategies fail → NetworkError
        repeat(2) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"balance":"not-a-number","ledgerId":"ledger-123","contractId":"contract-456"}""")
            )
        }
        repeat(2) { server.enqueue(trialBalanceMissingSpec()) }

        val result = client.fetchBalance(testAccount, validSessionJson)

        // Implementation converts all non-Success results to NetworkError during retry
        assertInstanceOf(ProviderResult.Failure.NetworkError::class.java, result)
    }

    @Test
    fun `fetchBalance returns NetworkError when trial spec is missing`() {
        // Parse error triggers retry across strategies (Constructed+Standard, Direct = 2 for paid, 2 for trial)
        // tryStrategy() converts ParseError to null, so after all strategies fail → NetworkError
        repeat(2) { server.enqueue(paidBalanceMissingField()) }
        repeat(2) { server.enqueue(trialBalanceMissingSpec()) }

        val result = client.fetchBalance(testAccount, validSessionJson)

        // Implementation converts all non-Success results to NetworkError during retry
        assertInstanceOf(ProviderResult.Failure.NetworkError::class.java, result)
    }

    @Test
    fun `fetchBalance returns NetworkError when trial status is missing`() {
        // Parse error triggers retry across strategies (Constructed+Standard, Direct = 2 for paid, 2 for trial)
        // tryStrategy() converts ParseError to null, so after all strategies fail → NetworkError
        repeat(2) { server.enqueue(paidBalanceMissingField()) }
        repeat(2) { server.enqueue(trialBalanceMissingStatus()) }

        val result = client.fetchBalance(testAccount, validSessionJson)

        // Implementation converts all non-Success results to NetworkError during retry
        assertInstanceOf(ProviderResult.Failure.NetworkError::class.java, result)
    }

    @Test
    fun `fetchBalance returns failure on empty body for both endpoints`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("")
        )

        val result = client.fetchBalance(testAccount, validSessionJson)

        assertTrue(
            result is ProviderResult.Failure.ParseError ||
                result is ProviderResult.Failure.NetworkError
        )
    }

    // ── testCredentials ────────────────────────────────────────────────────

    @Test
    fun `testCredentials delegates to fetchBalance`() {
        server.enqueue(paidBalanceResponse("10.00"))
        server.enqueue(trialBalanceResponse())

        val result = client.testCredentials(testAccount, validSessionJson)

        assertInstanceOf(ProviderResult.Success::class.java, result)
    }

    // ── Parity mode header deduplication ──────────────────────────────────

    @Test
    fun `parity mode excludes x-csrf-token from capturedHeaders to avoid duplicates`() {
        // Use a client with a failing command executor so native curl is skipped,
        // ensuring OkHttp parity mode is used and we can inspect the request headers.
        val failingCurlClient = NebiusProviderClient(
            httpClient = OkHttpClient(),
            gson = gson,
            baseUrl = server.url("").toString().trimEnd('/')
        )
        // Inject a failing command executor so native curl always throws, falling through to OkHttp
        failingCurlClient.commandExecutor = object : NebiusProviderClient.CommandExecutor {
            override fun execute(cmd: List<String>): NebiusProviderClient.CommandResult =
                throw RuntimeException("curl disabled in test")
        }

        val sessionWithCapturedCsrf = NebiusProviderClient.NebiusSession(
            appSession = "ne1CtwBChpzZXNzaW9u",
            csrfCookie = "6c3aa748bfd1d058b80fef4ddcbc7eb4",
            csrfToken = "explicit-token-123",
            parentId = "contract-e00pgjm81nl9t6yy137zj",
            rawCookieHeader = "__Host-app_session=ne1CtwBChpzZXNzaW9u; __Host-psifi.x-csrf-token=6c3aa748bfd1d058b80fef4ddcbc7eb4",
            capturedHeaders = mapOf(
                "accept" to "application/json",
                "x-csrf-token" to "captured-token-456", // Should be excluded
                "user-agent" to "Chrome/144"
            )
        )

        // With parity data, strategies are: NativeCurl (throws) → Parity+Standard → Parity+HTTP/1.1 →
        // Constructed+Standard → Direct = 4 OkHttp strategies for paid, then trial succeeds on first try
        repeat(4) { server.enqueue(paidBalanceNotFound()) }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"spec":{"netConsumptionLimit":"1.00"},"status":{"netConsumptionSpent":"0.00"}}""")
        )

        val result = failingCurlClient.fetchBalance(testAccount, gson.toJson(sessionWithCapturedCsrf))

        assertInstanceOf(ProviderResult.Success::class.java, result)

        // Skip paid requests, check trial request (via OkHttp parity mode)
        repeat(4) { server.takeRequest() } // paid retries
        val trialRequest = server.takeRequest() // trial (parity OkHttp)
        val csrfHeaders = trialRequest.headers.values("x-csrf-token")
        assertEquals(1, csrfHeaders.size, "Should have exactly one x-csrf-token header")
        assertEquals("explicit-token-123", csrfHeaders[0], "Should use explicit csrfToken, not captured value")
    }

    // ── Paid balance zero ──────────────────────────────────────────────────

    @Test
    fun `fetchBalance handles paid balance with zero remaining combined with trial`() {
        server.enqueue(paidBalanceResponse("0.00"))
        server.enqueue(trialBalanceResponse("1.00", "0.50"))

        val result = client.fetchBalance(testAccount, validSessionJson)

        assertInstanceOf(ProviderResult.Success::class.java, result)
        val credits = (result as ProviderResult.Success).snapshot.balance.credits!!
        // Combined: 0.00 + 0.50 = 0.50
        assertEquals(0, credits.remaining!!.compareTo(BigDecimal("0.50")))
    }
}
