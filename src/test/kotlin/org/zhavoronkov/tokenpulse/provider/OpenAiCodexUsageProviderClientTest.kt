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

class OpenAiCodexUsageProviderClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OpenAiCodexUsageProviderClient
    private val gson = Gson()

    private val testAccount = Account(
        id = "test-openai-account",
        providerId = ProviderId.OPENAI,
        authType = AuthType.OPENAI_OAUTH
    )

    private val validTokenData = OpenAiCodexUsageProviderClient.TokenData(
        accessToken = "Bearer sk-test-token-12345",
        refreshToken = "refresh_abc123",
        expiresAt = Long.MAX_VALUE
    )

    private val validTokenJson: String get() = gson.toJson(validTokenData)

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        // Use the mock server URL directly - client adds /v1/organization/...
        // server.url("") returns something like "http://127.0.0.1:xxxx/"
        // We need to strip the trailing slash for URL concatenation
        val baseUrl = server.url("").toString().removeSuffix("/")
        client = OpenAiCodexUsageProviderClient(
            httpClient = OkHttpClient(),
            gson = gson,
            baseUrl = baseUrl
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    // ── Success cases ──────────────────────────────────────────────────────

    @Test
    fun `fetchBalance returns usage data when response is valid`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "data": [
                        {"inputTokens": 1000, "outputTokens": 500, "cachedInputTokens": 100, "reasoningTokens": 50}
                      ],
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
                      "data": [{"amount": 0.05}],
                      "next_page": null
                    }
                    """.trimIndent()
                )
        )

        val result = client.fetchBalance(testAccount, validTokenJson)

        assertInstanceOf(ProviderResult.Success::class.java, result)
        val success = result as ProviderResult.Success
        val credits = success.snapshot.balance.credits!!
        val tokens = success.snapshot.balance.tokens!!
        assertEquals(0, credits.used!!.compareTo(BigDecimal("0.05")))
        assertEquals(0, tokens.used!!.compareTo(1650L)) // 1000 + 500 + 100 + 50
        assertEquals(ProviderId.OPENAI, success.snapshot.providerId)
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

        val result = client.fetchBalance(testAccount, validTokenJson)

        assertInstanceOf(ProviderResult.Success::class.java, result)
        val success = result as ProviderResult.Success
        val credits = success.snapshot.balance.credits!!
        val tokens = success.snapshot.balance.tokens!!
        assertEquals(0, credits.used!!.compareTo(BigDecimal.ZERO))
        assertEquals(0, tokens.used!!.compareTo(0L))
    }

    @Test
    fun `fetchBalance handles pagination`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "data": [{"inputTokens": 100, "outputTokens": 50}],
                      "next_page": "page2"
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
                      "data": [{"inputTokens": 200, "outputTokens": 100}],
                      "next_page": null
                    }
                    """.trimIndent()
                )
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data": [{"amount": 0.03}], "next_page": null}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data": [{"amount": 0.02}], "next_page": null}""")
        )

        val result = client.fetchBalance(testAccount, validTokenJson)

        assertInstanceOf(ProviderResult.Success::class.java, result)
        val success = result as ProviderResult.Success
        val tokens = success.snapshot.balance.tokens!!
        assertEquals(0, tokens.used!!.compareTo(550L)) // (100+50) + (200+100)
    }

    // ── Auth / token expiry ────────────────────────────────────────────────

    @Test
    fun `fetchBalance returns AuthError when token is missing`() {
        val result = client.fetchBalance(testAccount, "")

        assertInstanceOf(ProviderResult.Failure.AuthError::class.java, result)
        assertTrue((result as ProviderResult.Failure.AuthError).message.contains("missing") ||
            result.message.contains("invalid"))
    }

    @Test
    fun `fetchBalance returns AuthError when token is incomplete`() {
        val incompleteToken = """{"accessToken":"sk-test","refreshToken":"","expiresAt":999}"""

        val result = client.fetchBalance(testAccount, incompleteToken)

        assertInstanceOf(ProviderResult.Failure.AuthError::class.java, result)
    }

    @Test
    fun `fetchBalance returns AuthError on 401`() {
        // First request (usage) returns 401
        server.enqueue(MockResponse().setResponseCode(401))
        // Second request (costs) returns 401
        server.enqueue(MockResponse().setResponseCode(401))

        val result = client.fetchBalance(testAccount, validTokenJson)

        assertInstanceOf(ProviderResult.Failure.AuthError::class.java, result)
    }

    @Test
    fun `fetchBalance returns AuthError on 403`() {
        // First request (usage) returns 403
        server.enqueue(MockResponse().setResponseCode(403))
        // Second request (costs) returns 403
        server.enqueue(MockResponse().setResponseCode(403))

        val result = client.fetchBalance(testAccount, validTokenJson)

        assertInstanceOf(ProviderResult.Failure.AuthError::class.java, result)
    }

    // ── Rate limiting ──────────────────────────────────────────────────────

    @Test
    fun `fetchBalance returns RateLimited on 429`() {
        // First request (usage) returns 429
        server.enqueue(MockResponse().setResponseCode(429))
        // Second request (costs) returns 429
        server.enqueue(MockResponse().setResponseCode(429))

        val result = client.fetchBalance(testAccount, validTokenJson)

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

        val result = client.fetchBalance(testAccount, validTokenJson)

        assertInstanceOf(ProviderResult.Failure.UnknownError::class.java, result)
    }

    // ── Parse errors ───────────────────────────────────────────────────────

    @Test
    fun `fetchBalance returns ParseError on empty body`() {
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

        val result = client.fetchBalance(testAccount, validTokenJson)

        assertTrue(
            result is ProviderResult.Failure.ParseError ||
                result is ProviderResult.Failure.NetworkError
        )
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

        val result = client.fetchBalance(testAccount, validTokenJson)

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

        val result = client.testCredentials(testAccount, validTokenJson)

        assertInstanceOf(ProviderResult.Success::class.java, result)
    }
}
