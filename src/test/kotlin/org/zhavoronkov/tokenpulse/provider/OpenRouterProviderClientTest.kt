package org.zhavoronkov.tokenpulse.provider

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.zhavoronkov.tokenpulse.model.ConnectionType
import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.provider.openrouter.OpenRouterProviderClient
import org.zhavoronkov.tokenpulse.settings.Account
import org.zhavoronkov.tokenpulse.settings.AuthType
import java.math.BigDecimal

/**
 * Tests for [OpenRouterProviderClient].
 *
 * Only Provisioning Keys are supported — regular API keys do not expose the
 * `/api/v1/credits` endpoint required for balance tracking.
 */
class OpenRouterProviderClientTest {
    private lateinit var mockWebServer: MockWebServer
    private lateinit var client: OpenRouterProviderClient

    @BeforeEach
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        client = OpenRouterProviderClient(baseUrl = mockWebServer.url("/").toString().trimEnd('/'))
    }

    @AfterEach
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `fetchBalance with provisioning key returns credits and tokens on success`() {
        // Mock credits response
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"data":{"total_credits": 10.50}}""")
        )
        // Mock activity response
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"data":[{"prompt_tokens": 100, "completion_tokens": 50}]}""")
        )

        val account = Account(
            connectionType = ConnectionType.OPENROUTER_PROVISIONING,
            authType = AuthType.OPENROUTER_PROVISIONING_KEY
        )
        val result = client.fetchBalance(account, "test-provisioning-key")

        assertTrue(result is ProviderResult.Success)
        val success = result as ProviderResult.Success
        assertEquals(0, BigDecimal("10.50").compareTo(success.snapshot.balance.credits?.remaining))
        assertEquals(150L, success.snapshot.balance.tokens?.used)
    }

    @Test
    fun `fetchBalance returns AuthError on 401`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(401))

        val account = Account(
            connectionType = ConnectionType.OPENROUTER_PROVISIONING,
            authType = AuthType.OPENROUTER_PROVISIONING_KEY
        )
        val result = client.fetchBalance(account, "invalid-key")

        assertTrue(result is ProviderResult.Failure.AuthError)
    }

    @Test
    fun `fetchBalance returns RateLimited on 429`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(429))

        val account = Account(
            connectionType = ConnectionType.OPENROUTER_PROVISIONING,
            authType = AuthType.OPENROUTER_PROVISIONING_KEY
        )
        val result = client.fetchBalance(account, "test-key")

        assertTrue(result is ProviderResult.Failure.RateLimited)
    }

    @Test
    fun `fetchBalance returns ParseError on malformed JSON body`() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("not-json")
        )

        val account = Account(
            connectionType = ConnectionType.OPENROUTER_PROVISIONING,
            authType = AuthType.OPENROUTER_PROVISIONING_KEY
        )
        val result = client.fetchBalance(account, "test-key")

        // Gson throws JsonSyntaxException on malformed JSON → mapped to ParseError
        assertTrue(result is ProviderResult.Failure.ParseError, "Expected ParseError but got: $result")
    }

    @Test
    fun `fetchBalance succeeds even when activity endpoint returns non-200`() {
        // Credits OK, activity fails — tokens should be null but credits still returned
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"data":{"total_credits": 5.00}}""")
        )
        mockWebServer.enqueue(MockResponse().setResponseCode(500))

        val account = Account(
            connectionType = ConnectionType.OPENROUTER_PROVISIONING,
            authType = AuthType.OPENROUTER_PROVISIONING_KEY
        )
        val result = client.fetchBalance(account, "test-key")

        assertTrue(result is ProviderResult.Success)
        val success = result as ProviderResult.Success
        assertEquals(0, BigDecimal("5.00").compareTo(success.snapshot.balance.credits?.remaining))
        assertEquals(null, success.snapshot.balance.tokens)
    }

    @Test
    fun `fetchBalance returns AuthError on 403`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(403))

        val account = Account(
            connectionType = ConnectionType.OPENROUTER_PROVISIONING,
            authType = AuthType.OPENROUTER_PROVISIONING_KEY
        )
        val result = client.fetchBalance(account, "invalid-key")

        // 403 is handled as ServerException -> UnknownError
        assertTrue(result is ProviderResult.Failure.UnknownError)
    }

    @Test
    fun `fetchBalance returns UnknownError on 500`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(500))

        val account = Account(
            connectionType = ConnectionType.OPENROUTER_PROVISIONING,
            authType = AuthType.OPENROUTER_PROVISIONING_KEY
        )
        val result = client.fetchBalance(account, "test-key")

        assertTrue(result is ProviderResult.Failure.UnknownError)
    }

    @Test
    fun `fetchBalance with multiple activity entries sums tokens`() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"data":{"total_credits": 20.00}}""")
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"data":[
                    {"prompt_tokens": 100, "completion_tokens": 50},
                    {"prompt_tokens": 200, "completion_tokens": 100},
                    {"prompt_tokens": 50, "completion_tokens": 25}
                ]}"""
                )
        )

        val account = Account(
            connectionType = ConnectionType.OPENROUTER_PROVISIONING,
            authType = AuthType.OPENROUTER_PROVISIONING_KEY
        )
        val result = client.fetchBalance(account, "test-key")

        assertTrue(result is ProviderResult.Success)
        val success = result as ProviderResult.Success
        // 100+50 + 200+100 + 50+25 = 525
        assertEquals(525L, success.snapshot.balance.tokens?.used)
    }

    @Test
    fun `fetchBalance with empty activity returns zero tokens`() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"data":{"total_credits": 15.00}}""")
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"data":[]}""")
        )

        val account = Account(
            connectionType = ConnectionType.OPENROUTER_PROVISIONING,
            authType = AuthType.OPENROUTER_PROVISIONING_KEY
        )
        val result = client.fetchBalance(account, "test-key")

        assertTrue(result is ProviderResult.Success)
        val success = result as ProviderResult.Success
        assertEquals(0L, success.snapshot.balance.tokens?.used)
    }

    @Test
    fun `testCredentials delegates to fetchBalance`() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"data":{"total_credits": 1.00}}""")
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"data":[]}""")
        )

        val account = Account(
            connectionType = ConnectionType.OPENROUTER_PROVISIONING,
            authType = AuthType.OPENROUTER_PROVISIONING_KEY
        )
        val result = client.testCredentials(account, "test-key")

        assertTrue(result is ProviderResult.Success)
    }

    @Test
    fun `fetchBalance succeeds when activity returns 401`() {
        // Credits OK, but activity returns auth error — tokens should be null
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"data":{"total_credits": 8.00}}""")
        )
        mockWebServer.enqueue(MockResponse().setResponseCode(401))

        val account = Account(
            connectionType = ConnectionType.OPENROUTER_PROVISIONING,
            authType = AuthType.OPENROUTER_PROVISIONING_KEY
        )
        val result = client.fetchBalance(account, "test-key")

        assertTrue(result is ProviderResult.Success)
        val success = result as ProviderResult.Success
        assertEquals(0, BigDecimal("8.00").compareTo(success.snapshot.balance.credits?.remaining))
        assertEquals(null, success.snapshot.balance.tokens)
    }

    @Test
    fun `fetchBalance succeeds when activity returns 429`() {
        // Credits OK, but activity returns rate limit — tokens should be null
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"data":{"total_credits": 12.00}}""")
        )
        mockWebServer.enqueue(MockResponse().setResponseCode(429))

        val account = Account(
            connectionType = ConnectionType.OPENROUTER_PROVISIONING,
            authType = AuthType.OPENROUTER_PROVISIONING_KEY
        )
        val result = client.fetchBalance(account, "test-key")

        assertTrue(result is ProviderResult.Success)
        val success = result as ProviderResult.Success
        assertEquals(0, BigDecimal("12.00").compareTo(success.snapshot.balance.credits?.remaining))
        assertEquals(null, success.snapshot.balance.tokens)
    }

    @Test
    fun `fetchBalance handles credits with many decimal places`() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"data":{"total_credits": 0.123456789}}""")
        )
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"data":[]}""")
        )

        val account = Account(
            connectionType = ConnectionType.OPENROUTER_PROVISIONING,
            authType = AuthType.OPENROUTER_PROVISIONING_KEY
        )
        val result = client.fetchBalance(account, "test-key")

        assertTrue(result is ProviderResult.Success)
        val success = result as ProviderResult.Success
        assertEquals(0, BigDecimal("0.123456789").compareTo(success.snapshot.balance.credits?.remaining))
    }
}
