package org.zhavoronkov.tokenpulse.provider

import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.zhavoronkov.tokenpulse.model.ProviderId
import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.settings.Account
import org.zhavoronkov.tokenpulse.settings.AuthType
import java.math.BigDecimal

class NebiusProviderClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: NebiusProviderClient
    private val gson = Gson()

    private val testAccount = Account(
        id = "test-nebius-account",
        providerId = ProviderId.NEBIUS,
        authType = AuthType.NEBIUS_BILLING_SESSION
    )

    private val validSession = NebiusProviderClient.NebiusSession(
        appSession = "ne1CtwBChpzZXNzaW9u",
        csrfCookie = "6c3aa748bfd1d058b80fef4ddcbc7eb4",
        csrfToken = "6c3aa748bfd1d058b80fef4ddcbc7eb4",
        parentId = "contract-e00pgjm81nl9t6yy137zj"
    )

    private val validSessionJson: String get() = gson.toJson(validSession)

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

    // ── Success cases ──────────────────────────────────────────────────────

    @Test
    fun `fetchBalance returns credits when trial response is valid`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "metadata": {"id": "trial-abc"},
                      "spec": {
                        "netConsumptionLimit": "1.00",
                        "limitExceeded": false,
                        "switchedToPaid": false
                      },
                      "status": {
                        "netConsumptionSpent": "0.25",
                        "daysLeft": "27",
                        "daysLimit": "30"
                      }
                    }
                    """.trimIndent()
                )
        )

        val result = client.fetchBalance(testAccount, validSessionJson)

        assertInstanceOf(ProviderResult.Success::class.java, result)
        val success = result as ProviderResult.Success
        val credits = success.snapshot.balance.credits!!
        assertEquals(0, credits.total!!.compareTo(BigDecimal("1.00")))
        assertEquals(0, credits.remaining!!.compareTo(BigDecimal("0.75")))
        assertEquals(ProviderId.NEBIUS, success.snapshot.providerId)
    }

    @Test
    fun `fetchBalance returns zero remaining when fully spent`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "spec": {"netConsumptionLimit": "1.00"},
                      "status": {"netConsumptionSpent": "1.00", "daysLeft": "0"}
                    }
                    """.trimIndent()
                )
        )

        val result = client.fetchBalance(testAccount, validSessionJson)

        assertInstanceOf(ProviderResult.Success::class.java, result)
        val credits = (result as ProviderResult.Success).snapshot.balance.credits!!
        // Use compareTo to avoid BigDecimal scale mismatch
        assertEquals(0, credits.remaining!!.compareTo(BigDecimal.ZERO))
    }

    @Test
    fun `fetchBalance clamps remaining to zero when spent exceeds limit`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "spec": {"netConsumptionLimit": "1.00"},
                      "status": {"netConsumptionSpent": "1.50"}
                    }
                    """.trimIndent()
                )
        )

        val result = client.fetchBalance(testAccount, validSessionJson)

        assertInstanceOf(ProviderResult.Success::class.java, result)
        val credits = (result as ProviderResult.Success).snapshot.balance.credits!!
        // Use compareTo to avoid BigDecimal scale mismatch (0 vs 0.00)
        assertEquals(0, credits.remaining!!.compareTo(BigDecimal.ZERO))
    }

    @Test
    fun `fetchBalance sends correct request headers and body`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"spec":{"netConsumptionLimit":"1.00"},"status":{"netConsumptionSpent":"0.00"}}""")
        )

        client.fetchBalance(testAccount, validSessionJson)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path!!.endsWith("/billingActs/getCurrentTrial"))
        assertEquals("application/json", request.getHeader("accept"))
        assertEquals("XMLHttpRequest", request.getHeader("x-requested-with"))
        assertEquals(validSession.csrfToken, request.getHeader("x-csrf-token"))
        val cookieHeader = request.getHeader("cookie") ?: ""
        assertTrue(cookieHeader.contains("__Host-app_session="))
        assertTrue(cookieHeader.contains("__Host-psifi.x-csrf-token="))
        val body = request.body.readUtf8()
        assertTrue(body.contains(validSession.parentId!!))
    }

    // ── Auth / session expiry ──────────────────────────────────────────────

    @Test
    fun `fetchBalance returns AuthError on 403 EBADCSRFTOKEN`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"code":"EBADCSRFTOKEN","statusCode":403,"message":"invalid csrf token"}""")
        )

        val result = client.fetchBalance(testAccount, validSessionJson)

        assertInstanceOf(ProviderResult.Failure.AuthError::class.java, result)
        val error = result as ProviderResult.Failure.AuthError
        assertTrue(error.message.contains("expired") || error.message.contains("session"))
    }

    @Test
    fun `fetchBalance returns AuthError on 401`() {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = client.fetchBalance(testAccount, validSessionJson)

        assertInstanceOf(ProviderResult.Failure.AuthError::class.java, result)
    }

    @Test
    fun `fetchBalance returns AuthError when session JSON is missing`() {
        val result = client.fetchBalance(testAccount, "")

        assertInstanceOf(ProviderResult.Failure.AuthError::class.java, result)
        assertTrue((result as ProviderResult.Failure.AuthError).message.contains("missing") ||
            result.message.contains("invalid"))
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
    fun `fetchBalance returns RateLimited on 429`() {
        server.enqueue(MockResponse().setResponseCode(429))

        val result = client.fetchBalance(testAccount, validSessionJson)

        assertInstanceOf(ProviderResult.Failure.RateLimited::class.java, result)
    }

    // ── Server errors ──────────────────────────────────────────────────────

    @Test
    fun `fetchBalance returns UnknownError on 500`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("""{"statusCode":500,"message":"Internal server error"}""")
        )

        val result = client.fetchBalance(testAccount, validSessionJson)

        assertInstanceOf(ProviderResult.Failure.UnknownError::class.java, result)
    }

    // ── Parse errors ───────────────────────────────────────────────────────

    @Test
    fun `fetchBalance returns ParseError when spec is missing`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"status":{"netConsumptionSpent":"0.00"}}""")
        )

        val result = client.fetchBalance(testAccount, validSessionJson)

        assertInstanceOf(ProviderResult.Failure.ParseError::class.java, result)
    }

    @Test
    fun `fetchBalance returns ParseError when status is missing`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"spec":{"netConsumptionLimit":"1.00"}}""")
        )

        val result = client.fetchBalance(testAccount, validSessionJson)

        assertInstanceOf(ProviderResult.Failure.ParseError::class.java, result)
    }

    @Test
    fun `fetchBalance returns ParseError on empty body`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("")
        )

        val result = client.fetchBalance(testAccount, validSessionJson)

        // Empty body → gson returns null object → ParseError or NetworkError
        assertTrue(
            result is ProviderResult.Failure.ParseError ||
                result is ProviderResult.Failure.NetworkError
        )
    }

    // ── testCredentials ────────────────────────────────────────────────────

    @Test
    fun `testCredentials delegates to fetchBalance`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"spec":{"netConsumptionLimit":"1.00"},"status":{"netConsumptionSpent":"0.00"}}""")
        )

        val result = client.testCredentials(testAccount, validSessionJson)

        assertInstanceOf(ProviderResult.Success::class.java, result)
    }
}
