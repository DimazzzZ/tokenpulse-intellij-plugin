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
import org.zhavoronkov.tokenpulse.provider.deepseek.DeepSeekProviderClient
import org.zhavoronkov.tokenpulse.settings.Account
import org.zhavoronkov.tokenpulse.settings.AuthType
import java.math.BigDecimal

class DeepSeekProviderClientTest {
    private lateinit var mockWebServer: MockWebServer
    private lateinit var client: DeepSeekProviderClient

    @BeforeEach
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        client = DeepSeekProviderClient(baseUrl = mockWebServer.url("/").toString())
    }

    @AfterEach
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `test fetchBalance success with CNY currency`() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{
                    "is_available": true,
                    "balance_infos": [
                        {
                            "currency": "CNY",
                            "total_balance": "100.50"
                        }
                    ]
                }"""
                )
        )

        val account = Account(connectionType = ConnectionType.DEEPSEEK_API, authType = AuthType.DEEPSEEK_API_KEY)
        val result = client.fetchBalance(account, "sk-test-key")

        assertTrue(result is ProviderResult.Success)
        val success = result as ProviderResult.Success
        assertEquals(0, BigDecimal("100.50").compareTo(success.snapshot.balance.credits?.remaining))
        assertEquals("CNY", success.snapshot.metadata["currency"])
    }

    @Test
    fun `test fetchBalance success with USD currency`() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{
                    "is_available": true,
                    "balance_infos": [
                        {
                            "currency": "USD",
                            "total_balance": "50.25"
                        }
                    ]
                }"""
                )
        )

        val account = Account(connectionType = ConnectionType.DEEPSEEK_API, authType = AuthType.DEEPSEEK_API_KEY)
        val result = client.fetchBalance(account, "sk-test-key")

        assertTrue(result is ProviderResult.Success)
        val success = result as ProviderResult.Success
        assertEquals(0, BigDecimal("50.25").compareTo(success.snapshot.balance.credits?.remaining))
        assertEquals("USD", success.snapshot.metadata["currency"])
    }

    @Test
    fun `test fetchBalance returns AuthError on 401`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(401))

        val account = Account(connectionType = ConnectionType.DEEPSEEK_API, authType = AuthType.DEEPSEEK_API_KEY)
        val result = client.fetchBalance(account, "bad-key")

        assertTrue(result is ProviderResult.Failure.AuthError)
    }

    @Test
    fun `test fetchBalance returns AuthError on 403`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(403))

        val account = Account(connectionType = ConnectionType.DEEPSEEK_API, authType = AuthType.DEEPSEEK_API_KEY)
        val result = client.fetchBalance(account, "forbidden-key")

        assertTrue(result is ProviderResult.Failure.AuthError)
    }

    @Test
    fun `test fetchBalance returns RateLimited on 429`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(429))

        val account = Account(connectionType = ConnectionType.DEEPSEEK_API, authType = AuthType.DEEPSEEK_API_KEY)
        val result = client.fetchBalance(account, "sk-test-key")

        assertTrue(result is ProviderResult.Failure.RateLimited)
    }

    @Test
    fun `test fetchBalance returns NetworkError on 500`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(500))

        val account = Account(connectionType = ConnectionType.DEEPSEEK_API, authType = AuthType.DEEPSEEK_API_KEY)
        val result = client.fetchBalance(account, "sk-test-key")

        assertTrue(result is ProviderResult.Failure.NetworkError)
    }

    @Test
    fun `test fetchBalance succeeds when is_available is false (zero balance)`() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{
                    "is_available": false,
                    "balance_infos": [
                        {
                            "currency": "USD",
                            "total_balance": "0.00"
                        }
                    ]
                }"""
                )
        )

        val account = Account(connectionType = ConnectionType.DEEPSEEK_API, authType = AuthType.DEEPSEEK_API_KEY)
        val result = client.fetchBalance(account, "sk-test-key")

        assertTrue(result is ProviderResult.Success)
        val success = result as ProviderResult.Success
        assertEquals(0, BigDecimal("0.00").compareTo(success.snapshot.balance.credits?.remaining))
        assertEquals("USD", success.snapshot.metadata["currency"])
    }

    @Test
    fun `test fetchBalance returns ParseError on missing balance_infos`() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"is_available": true}""")
        )

        val account = Account(connectionType = ConnectionType.DEEPSEEK_API, authType = AuthType.DEEPSEEK_API_KEY)
        val result = client.fetchBalance(account, "sk-test-key")

        assertTrue(result is ProviderResult.Failure.ParseError)
    }

    @Test
    fun `test fetchBalance returns ParseError on empty balance_infos`() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{
                    "is_available": true,
                    "balance_infos": []
                }"""
                )
        )

        val account = Account(connectionType = ConnectionType.DEEPSEEK_API, authType = AuthType.DEEPSEEK_API_KEY)
        val result = client.fetchBalance(account, "sk-test-key")

        assertTrue(result is ProviderResult.Failure.ParseError)
    }

    @Test
    fun `test fetchBalance returns ParseError on missing total_balance`() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{
                    "is_available": true,
                    "balance_infos": [
                        {
                            "currency": "CNY"
                        }
                    ]
                }"""
                )
        )

        val account = Account(connectionType = ConnectionType.DEEPSEEK_API, authType = AuthType.DEEPSEEK_API_KEY)
        val result = client.fetchBalance(account, "sk-test-key")

        assertTrue(result is ProviderResult.Failure.ParseError)
    }

    @Test
    fun `test fetchBalance returns ParseError on invalid total_balance format`() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{
                    "is_available": true,
                    "balance_infos": [
                        {
                            "currency": "CNY",
                            "total_balance": "not-a-number"
                        }
                    ]
                }"""
                )
        )

        val account = Account(connectionType = ConnectionType.DEEPSEEK_API, authType = AuthType.DEEPSEEK_API_KEY)
        val result = client.fetchBalance(account, "sk-test-key")

        assertTrue(result is ProviderResult.Failure.ParseError)
    }

    @Test
    fun `test testCredentials success`() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{
                    "is_available": true,
                    "balance_infos": [
                        {
                            "currency": "CNY",
                            "total_balance": "100.00"
                        }
                    ]
                }"""
                )
        )

        val account = Account(connectionType = ConnectionType.DEEPSEEK_API, authType = AuthType.DEEPSEEK_API_KEY)
        val result = client.testCredentials(account, "sk-test-key")

        assertTrue(result is ProviderResult.Success)
    }

    @Test
    fun `test testCredentials returns AuthError on 401`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(401))

        val account = Account(connectionType = ConnectionType.DEEPSEEK_API, authType = AuthType.DEEPSEEK_API_KEY)
        val result = client.testCredentials(account, "bad-key")

        assertTrue(result is ProviderResult.Failure.AuthError)
    }
}
