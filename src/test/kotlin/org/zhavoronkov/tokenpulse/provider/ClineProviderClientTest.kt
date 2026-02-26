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

class ClineProviderClientTest {
    private lateinit var mockWebServer: MockWebServer
    private lateinit var client: ClineProviderClient

    @BeforeEach
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        client = ClineProviderClient(baseUrl = mockWebServer.url("/").toString())
    }

    @AfterEach
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `test fetchBalance personal success`() {
        // 1. Mock /me response
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"success":true,"data":{"id":"user-123","organizations":[]}}"""))

        // 2. Mock /balance response — 9964261 micro-dollars = $9.96
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"success":true,"data":{"balance": 9964261.0}}"""))

        // 3. Mock /usages response — 5500000 micro-dollars = $5.50
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"success":true,"data":{"items":[{"creditsUsed": 5500000.0, "totalTokens": 1000}]}}"""))

        val account = Account(providerId = ProviderId.CLINE, authType = AuthType.CLINE_API_KEY)
        val result = client.fetchBalance(account, "token")

        assertTrue(result is ProviderResult.Success)
        val success = result as ProviderResult.Success
        // 9964261 credits / 1,000,000 = $9.96 (rounded half-up)
        assertEquals(0, BigDecimal("9.96").compareTo(success.snapshot.balance.credits?.remaining))
        // 5500000 credits / 1,000,000 = $5.50
        assertEquals(0, BigDecimal("5.50").compareTo(success.snapshot.balance.credits?.used))
        assertEquals(1000L, success.snapshot.balance.tokens?.used)
    }
}
