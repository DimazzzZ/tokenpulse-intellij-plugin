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
import org.zhavoronkov.tokenpulse.model.ConnectionType
import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.provider.openai.platform.OpenAiPlatformProviderClient
import org.zhavoronkov.tokenpulse.settings.Account
import org.zhavoronkov.tokenpulse.settings.AuthType
import java.math.BigDecimal

class OpenAiPlatformProviderClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OpenAiPlatformProviderClient
    private val gson = Gson()

    private val testAccount = Account(
        id = "test-openai-account",
        connectionType = ConnectionType.OPENAI_PLATFORM,
        authType = AuthType.OPENAI_API_KEY
    )

    /** Valid Admin API key token data — the only accepted format. */
    private val validAdminTokenData = OpenAiPlatformProviderClient.TokenData(
        accessToken = "sk-admin-test-admin-key-12345",
        refreshToken = "refresh_abc123",
        expiresAt = Long.MAX_VALUE
    )

    private val validAdminTokenJson: String get() = gson.toJson(validAdminTokenData)

    /** Plain Admin API key string (not wrapped in JSON). */
    private val plainAdminKey = "sk-admin-plain-key-67890"

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        val baseUrl = server.url("").toString().removeSuffix("/")
        client = OpenAiPlatformProviderClient(
            httpClient = OkHttpClient(),
            gson = gson,
            baseUrl = baseUrl,
            maxRetries = 2,
            initialRetryDelayMs = 0L,
            maxRetryDelayMs = 0L,
            retryJitterMs = 0L,
            sleepFn = { } // No-op for fast tests
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    // ── Success cases ──────────────────────────────────────────────────────

    @Test
    fun `fetchBalance returns usage data when Admin key is valid`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "data": [
                        {
                          "start_time": 1730419200,
                          "end_time": 1730505600,
                          "results": [
                            {"input_tokens": 1000, "output_tokens": 500, "input_cached_tokens": 100, "reasoning_tokens": 50}
                          ]
                        }
                      ],
                      "has_more": false,
                      "next_page": null
                    }
                    """.trimIndent()
                )
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "data": [
                        {
                          "start_time": 1730419200,
                          "end_time": 1730505600,
                          "results": [
                            {"amount": {"value": 0.05, "currency": "usd"}}
                          ]
                        }
                      ],
                      "has_more": false,
                      "next_page": null
                    }
                    """.trimIndent()
                )
        )

        val result = client.fetchBalance(testAccount, validAdminTokenJson)

        assertInstanceOf(ProviderResult.Success::class.java, result)
        val success = result as ProviderResult.Success
        val credits = success.snapshot.balance.credits!!
        val tokens = success.snapshot.balance.tokens!!
        assertEquals(0, credits.used!!.compareTo(BigDecimal("0.05")))
        assertEquals(0, tokens.used!!.compareTo(1650L)) // 1000 + 500 + 100 + 50
        assertEquals(ConnectionType.OPENAI_PLATFORM, success.snapshot.connectionType)
    }

    @Test
    fun `fetchBalance works with plain Admin API key string (not JSON-wrapped)`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data": [], "next_page": null}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data": [], "next_page": null}""")
        )

        val result = client.fetchBalance(testAccount, plainAdminKey)

        assertInstanceOf(ProviderResult.Success::class.java, result)
    }

    @Test
    fun `fetchBalance handles empty usage data`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data": [], "next_page": null}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data": [], "next_page": null}""")
        )

        val result = client.fetchBalance(testAccount, validAdminTokenJson)

        assertInstanceOf(ProviderResult.Success::class.java, result)
        val success = result as ProviderResult.Success
        val credits = success.snapshot.balance.credits!!
        val tokens = success.snapshot.balance.tokens!!
        assertEquals(0, credits.used!!.compareTo(BigDecimal.ZERO))
        assertEquals(0, tokens.used!!.compareTo(0L))
    }

    // ── Admin key enforcement ──────────────────────────────────────────────

    @Test
    fun `fetchBalance rejects project key (sk-proj-) with AuthError`() {
        // No server calls should be made — rejection happens before any HTTP request
        val projectKey = "sk-proj-test-project-key-12345"

        val result = client.fetchBalance(testAccount, projectKey)

        assertInstanceOf(ProviderResult.Failure.AuthError::class.java, result)
        val error = result as ProviderResult.Failure.AuthError
        assertTrue(error.message.contains("sk-admin-"), "Error should mention sk-admin- prefix")
        // Verify no HTTP requests were made
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `fetchBalance rejects personal key (sk-) with AuthError`() {
        val personalKey = "sk-personal-test-key-12345"

        val result = client.fetchBalance(testAccount, personalKey)

        assertInstanceOf(ProviderResult.Failure.AuthError::class.java, result)
        val error = result as ProviderResult.Failure.AuthError
        assertTrue(error.message.contains("sk-admin-"), "Error should mention sk-admin- prefix")
        assertEquals(0, server.requestCount)
    }

    // ── Auth / token expiry ────────────────────────────────────────────────

    @Test
    fun `fetchBalance returns AuthError when token is missing`() {
        val result = client.fetchBalance(testAccount, "")

        assertInstanceOf(ProviderResult.Failure.AuthError::class.java, result)
        assertTrue(
            (result as ProviderResult.Failure.AuthError).message.contains("missing") ||
                result.message.contains("invalid")
        )
    }

    @Test
    fun `fetchBalance returns AuthError on 401`() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401))

        val result = client.fetchBalance(testAccount, validAdminTokenJson)

        assertInstanceOf(ProviderResult.Failure.AuthError::class.java, result)
    }

    @Test
    fun `fetchBalance returns AuthError on 403`() {
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(MockResponse().setResponseCode(403))

        val result = client.fetchBalance(testAccount, validAdminTokenJson)

        assertInstanceOf(ProviderResult.Failure.AuthError::class.java, result)
    }

    // ── Rate limiting ──────────────────────────────────────────────────────

    @Test
    fun `fetchBalance returns RateLimited on 429`() {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(429))

        val result = client.fetchBalance(testAccount, validAdminTokenJson)

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
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("""{"statusCode":500,"message":"Internal server error"}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("""{"statusCode":500,"message":"Internal server error"}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("""{"statusCode":500,"message":"Internal server error"}""")
        )

        val result = client.fetchBalance(testAccount, validAdminTokenJson)

        assertInstanceOf(ProviderResult.Failure.UnknownError::class.java, result)
    }

    // ── Parse errors ───────────────────────────────────────────────────────

    @Test
    fun `fetchBalance returns NetworkError on empty body`() {
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

        val result = client.fetchBalance(testAccount, validAdminTokenJson)

        assertInstanceOf(ProviderResult.Failure.NetworkError::class.java, result)
    }

    @Test
    fun `fetchBalance returns ParseError on malformed JSON`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("not valid json")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("not valid json")
        )

        val result = client.fetchBalance(testAccount, validAdminTokenJson)

        assertInstanceOf(ProviderResult.Failure.ParseError::class.java, result)
    }

    // ── testCredentials ────────────────────────────────────────────────────

    @Test
    fun `testCredentials delegates to fetchBalance`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data": [], "next_page": null}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data": [], "next_page": null}""")
        )

        val result = client.testCredentials(testAccount, validAdminTokenJson)

        assertInstanceOf(ProviderResult.Success::class.java, result)
    }

    // ── Retry delay with headers ───────────────────────────────────────────

    @Test
    fun `calculateRetryDelayWithHeaders uses Retry-After header when present`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "5")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data": [], "next_page": null}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data": [], "next_page": null}""")
        )

        val result = client.fetchBalance(testAccount, validAdminTokenJson)

        // Should succeed after retry
        assertInstanceOf(ProviderResult.Success::class.java, result)
    }

    @Test
    fun `calculateRetryDelayWithHeaders uses x-ratelimit-reset-requests when Retry-After missing`() {
        val futureResetTime = (System.currentTimeMillis() / 1000) + 2
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("x-ratelimit-reset-requests", futureResetTime.toString())
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data": [], "next_page": null}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data": [], "next_page": null}""")
        )

        val result = client.fetchBalance(testAccount, validAdminTokenJson)

        assertInstanceOf(ProviderResult.Success::class.java, result)
    }

    @Test
    fun `calculateRetryDelayWithHeaders ignores invalid Retry-After header`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "not-a-number")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data": [], "next_page": null}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data": [], "next_page": null}""")
        )

        val result = client.fetchBalance(testAccount, validAdminTokenJson)

        assertInstanceOf(ProviderResult.Success::class.java, result)
    }

    @Test
    fun `calculateRetryDelayWithHeaders ignores invalid x-ratelimit-reset-requests header`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("x-ratelimit-reset-requests", "invalid")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data": [], "next_page": null}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data": [], "next_page": null}""")
        )

        val result = client.fetchBalance(testAccount, validAdminTokenJson)

        assertInstanceOf(ProviderResult.Success::class.java, result)
    }

    // ── Pagination ─────────────────────────────────────────────────────────

    @Test
    fun `fetchBalance handles pagination with has_more and next_page`() {
        // First page of usage data
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "data": [{"results": [{"input_tokens": 100}]}],
                      "has_more": true,
                      "next_page": "page2"
                    }
                    """.trimIndent()
                )
        )
        // Second page of usage data
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "data": [{"results": [{"input_tokens": 200}]}],
                      "has_more": false,
                      "next_page": null
                    }
                    """.trimIndent()
                )
        )
        // First page of cost data
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "data": [{"results": [{"amount": {"value": 0.01}}]}],
                      "has_more": true,
                      "next_page": "page2"
                    }
                    """.trimIndent()
                )
        )
        // Second page of cost data
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "data": [{"results": [{"amount": {"value": 0.02}}]}],
                      "has_more": false,
                      "next_page": null
                    }
                    """.trimIndent()
                )
        )

        val result = client.fetchBalance(testAccount, validAdminTokenJson)

        assertInstanceOf(ProviderResult.Success::class.java, result)
        val success = result as ProviderResult.Success
        assertEquals(0, success.snapshot.balance.credits!!.used!!.compareTo(BigDecimal("0.03")))
        assertEquals(300L, success.snapshot.balance.tokens!!.used)
    }

    // ── Server error retries ───────────────────────────────────────────────

    @Test
    fun `fetchBalance retries on 500 and succeeds`() {
        // First attempt fails with 500
        server.enqueue(MockResponse().setResponseCode(500))
        // Retry succeeds
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data": [], "next_page": null}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data": [], "next_page": null}""")
        )

        val result = client.fetchBalance(testAccount, validAdminTokenJson)

        assertInstanceOf(ProviderResult.Success::class.java, result)
    }

    @Test
    fun `fetchBalance retries on 502 Bad Gateway`() {
        server.enqueue(MockResponse().setResponseCode(502))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data": [], "next_page": null}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data": [], "next_page": null}""")
        )

        val result = client.fetchBalance(testAccount, validAdminTokenJson)

        assertInstanceOf(ProviderResult.Success::class.java, result)
    }

    @Test
    fun `fetchBalance retries on 503 Service Unavailable`() {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data": [], "next_page": null}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data": [], "next_page": null}""")
        )

        val result = client.fetchBalance(testAccount, validAdminTokenJson)

        assertInstanceOf(ProviderResult.Success::class.java, result)
    }

    @Test
    fun `fetchBalance retries on 504 Gateway Timeout`() {
        server.enqueue(MockResponse().setResponseCode(504))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data": [], "next_page": null}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data": [], "next_page": null}""")
        )

        val result = client.fetchBalance(testAccount, validAdminTokenJson)

        assertInstanceOf(ProviderResult.Success::class.java, result)
    }

    // ── Error response parsing ─────────────────────────────────────────────

    @Test
    fun `fetchBalance extracts error message from JSON response`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error": {"message": "Invalid API key provided"}}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error": {"message": "Invalid API key provided"}}""")
        )

        val result = client.fetchBalance(testAccount, validAdminTokenJson)

        assertInstanceOf(ProviderResult.Failure.AuthError::class.java, result)
        assertTrue((result as ProviderResult.Failure.AuthError).message.contains("Invalid API key"))
    }

    @Test
    fun `fetchBalance handles error response with non-JSON body`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("Access denied")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("Access denied")
        )

        val result = client.fetchBalance(testAccount, validAdminTokenJson)

        assertInstanceOf(ProviderResult.Failure.AuthError::class.java, result)
    }

    // ── Rate limit header logging ──────────────────────────────────────────

    @Test
    fun `fetchBalance logs rate limit headers when present`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setHeader("x-ratelimit-limit-requests", "100")
                .setHeader("x-ratelimit-remaining-requests", "99")
                .setHeader("x-ratelimit-reset-requests", "1234567890")
                .setBody("""{"data": [], "next_page": null}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data": [], "next_page": null}""")
        )

        val result = client.fetchBalance(testAccount, validAdminTokenJson)

        assertInstanceOf(ProviderResult.Success::class.java, result)
    }

    // ── Cost entry parsing ─────────────────────────────────────────────────

    @Test
    fun `fetchBalance handles cost entry with missing amount`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data": [{"results": [{}]}], "next_page": null}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data": [{"results": [{}]}], "next_page": null}""")
        )

        val result = client.fetchBalance(testAccount, validAdminTokenJson)

        assertInstanceOf(ProviderResult.Success::class.java, result)
    }

    @Test
    fun `fetchBalance handles usage entry with null tokens`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "data": [{
                        "results": [{
                          "input_tokens": null,
                          "output_tokens": null
                        }]
                      }],
                      "next_page": null
                    }
                    """.trimIndent()
                )
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data": [], "next_page": null}""")
        )

        val result = client.fetchBalance(testAccount, validAdminTokenJson)

        assertInstanceOf(ProviderResult.Success::class.java, result)
    }
}
