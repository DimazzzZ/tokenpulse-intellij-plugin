package org.zhavoronkov.tokenpulse.provider

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.zhavoronkov.tokenpulse.model.ProviderId
import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.settings.Account
import org.zhavoronkov.tokenpulse.settings.AuthType
import java.math.BigDecimal

class OpenRouterProviderClientTest {
    private lateinit var mockWebServer: MockWebServer
    private lateinit var client: OpenRouterProviderClient

    @BeforeEach
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        client = OpenRouterProviderClient(baseUrl = mockWebServer.url("/").toString())
    }

    @AfterEach
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `test fetchBalance with provisioning key success`() {
        // Mock credits response
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"data":{"total_credits": 10.50}}"""))
        
        // Mock activity response
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"data":[{"prompt_tokens": 100, "completion_tokens": 50}]}"""))

        val account = Account(name = "Test", providerId = ProviderId.OPENROUTER, authType = AuthType.OPENROUTER_PROVISIONING_KEY)
        val result = client.fetchBalance(account, "test-secret")

        assertTrue(result is ProviderResult.Success)
        val success = result as ProviderResult.Success
        assertEquals(0, BigDecimal("10.50").compareTo(success.snapshot.balance.credits?.remaining))
        assertEquals(150L, success.snapshot.balance.tokens?.used)
    }

    @Test
    fun `test fetchBalance auth error`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(401))

        val account = Account(name = "Test", providerId = ProviderId.OPENROUTER, authType = AuthType.OPENROUTER_API_KEY)
        val result = client.fetchBalance(account, "wrong-secret")

        assertTrue(result is ProviderResult.Failure.AuthError)
    }
}
