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
import org.zhavoronkov.tokenpulse.provider.cline.ClineProviderClient
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
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":true,"data":{"id":"user-123","organizations":[]}}""")
        )

        // 2. Mock /balance response — 9964261 micro-dollars = $9.96
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":true,"data":{"balance": 9964261.0}}""")
        )

        // 3. Mock /usages response — 5500000 micro-dollars = $5.50
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":true,"data":{"items":[{"creditsUsed": 5500000.0, "totalTokens": 1000}]}}""")
        )

        val account = Account(connectionType = ConnectionType.CLINE_API, authType = AuthType.CLINE_API_KEY)
        val result = client.fetchBalance(account, "token")

        assertTrue(result is ProviderResult.Success)
        val success = result as ProviderResult.Success
        // 9964261 credits / 1,000,000 = $9.96 (rounded half-up)
        assertEquals(0, BigDecimal("9.96").compareTo(success.snapshot.balance.credits?.remaining))
        // 5500000 credits / 1,000,000 = $5.50
        assertEquals(0, BigDecimal("5.50").compareTo(success.snapshot.balance.credits?.used))
        assertEquals(1000L, success.snapshot.balance.tokens?.used)
    }

    @Test
    fun `test fetchBalance organization success`() {
        // 1. Mock /me response with active organization
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"success":true,"data":{"id":"user-123","organizations":[
                    {"organizationId":"org-456","memberId":"member-789","active":true}
                ]}}"""
                )
        )

        // 2. Mock org /balance response — 50,000,000 micro-dollars = $50.00
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":true,"data":{"balance": 50000000.0}}""")
        )

        // 3. Mock org member /usages response — 10,000,000 micro-dollars = $10.00
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":true,"data":{"items":[{"creditsUsed": 10000000.0, "totalTokens": 5000}]}}""")
        )

        val account = Account(connectionType = ConnectionType.CLINE_API, authType = AuthType.CLINE_API_KEY)
        val result = client.fetchBalance(account, "token")

        assertTrue(result is ProviderResult.Success)
        val success = result as ProviderResult.Success
        assertEquals(0, BigDecimal("50.00").compareTo(success.snapshot.balance.credits?.remaining))
        assertEquals(0, BigDecimal("10.00").compareTo(success.snapshot.balance.credits?.used))
        assertEquals(5000L, success.snapshot.balance.tokens?.used)
    }

    @Test
    fun `test fetchBalance returns AuthError when me endpoint fails`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(401))

        val account = Account(connectionType = ConnectionType.CLINE_API, authType = AuthType.CLINE_API_KEY)
        val result = client.fetchBalance(account, "bad-token")

        assertTrue(result is ProviderResult.Failure.AuthError)
    }

    @Test
    fun `test fetchBalance handles empty usages`() {
        // 1. Mock /me response
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":true,"data":{"id":"user-123","organizations":[]}}""")
        )

        // 2. Mock /balance response
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":true,"data":{"balance": 5000000.0}}""")
        )

        // 3. Mock /usages response with empty items
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":true,"data":{"items":[]}}""")
        )

        val account = Account(connectionType = ConnectionType.CLINE_API, authType = AuthType.CLINE_API_KEY)
        val result = client.fetchBalance(account, "token")

        assertTrue(result is ProviderResult.Success)
        val success = result as ProviderResult.Success
        assertEquals(0, BigDecimal("5.00").compareTo(success.snapshot.balance.credits?.remaining))
        assertEquals(0, BigDecimal.ZERO.compareTo(success.snapshot.balance.credits?.used))
        assertEquals(0L, success.snapshot.balance.tokens?.used)
    }

    @Test
    fun `test fetchBalance handles balance endpoint failure`() {
        // 1. Mock /me response
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":true,"data":{"id":"user-123","organizations":[]}}""")
        )

        // 2. Mock /balance endpoint failure
        mockWebServer.enqueue(MockResponse().setResponseCode(500))

        // 3. Mock /usages response
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":true,"data":{"items":[{"creditsUsed": 1000000.0, "totalTokens": 100}]}}""")
        )

        val account = Account(connectionType = ConnectionType.CLINE_API, authType = AuthType.CLINE_API_KEY)
        val result = client.fetchBalance(account, "token")

        // Should still succeed with zero balance
        assertTrue(result is ProviderResult.Success)
        val success = result as ProviderResult.Success
        assertEquals(0, BigDecimal.ZERO.compareTo(success.snapshot.balance.credits?.remaining))
    }

    @Test
    fun `test fetchBalance handles usages endpoint failure`() {
        // 1. Mock /me response
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":true,"data":{"id":"user-123","organizations":[]}}""")
        )

        // 2. Mock /balance response
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":true,"data":{"balance": 3000000.0}}""")
        )

        // 3. Mock /usages endpoint failure
        mockWebServer.enqueue(MockResponse().setResponseCode(500))

        val account = Account(connectionType = ConnectionType.CLINE_API, authType = AuthType.CLINE_API_KEY)
        val result = client.fetchBalance(account, "token")

        // Should still succeed with zero usage
        assertTrue(result is ProviderResult.Success)
        val success = result as ProviderResult.Success
        assertEquals(0, BigDecimal("3.00").compareTo(success.snapshot.balance.credits?.remaining))
        assertEquals(0, BigDecimal.ZERO.compareTo(success.snapshot.balance.credits?.used))
    }

    @Test
    fun `test fetchBalance with multiple usage transactions`() {
        // 1. Mock /me response
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":true,"data":{"id":"user-123","organizations":[]}}""")
        )

        // 2. Mock /balance response
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":true,"data":{"balance": 10000000.0}}""")
        )

        // 3. Mock /usages response with multiple transactions
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"success":true,"data":{"items":[
                    {"creditsUsed": 1000000.0, "totalTokens": 100},
                    {"creditsUsed": 2000000.0, "totalTokens": 200},
                    {"creditsUsed": 500000.0, "totalTokens": 50}
                ]}}"""
                )
        )

        val account = Account(connectionType = ConnectionType.CLINE_API, authType = AuthType.CLINE_API_KEY)
        val result = client.fetchBalance(account, "token")

        assertTrue(result is ProviderResult.Success)
        val success = result as ProviderResult.Success
        assertEquals(0, BigDecimal("10.00").compareTo(success.snapshot.balance.credits?.remaining))
        // 1.00 + 2.00 + 0.50 = 3.50
        assertEquals(0, BigDecimal("3.50").compareTo(success.snapshot.balance.credits?.used))
        // 100 + 200 + 50 = 350
        assertEquals(350L, success.snapshot.balance.tokens?.used)
    }

    @Test
    fun `test testCredentials returns success when me endpoint succeeds`() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":true,"data":{"id":"user-123","organizations":[]}}""")
        )

        val account = Account(connectionType = ConnectionType.CLINE_API, authType = AuthType.CLINE_API_KEY)
        val result = client.testCredentials(account, "valid-token")

        assertTrue(result is ProviderResult.Success)
    }

    @Test
    fun `test testCredentials returns AuthError when me endpoint fails`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(401))

        val account = Account(connectionType = ConnectionType.CLINE_API, authType = AuthType.CLINE_API_KEY)
        val result = client.testCredentials(account, "invalid-token")

        assertTrue(result is ProviderResult.Failure.AuthError)
    }

    @Test
    fun `test fetchBalance selects first active org when multiple orgs exist`() {
        // 1. Mock /me response with multiple organizations, one active
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"success":true,"data":{"id":"user-123","organizations":[
                    {"organizationId":"org-1","memberId":"member-1","active":false},
                    {"organizationId":"org-2","memberId":"member-2","active":true},
                    {"organizationId":"org-3","memberId":"member-3","active":false}
                ]}}"""
                )
        )

        // 2. Mock org /balance response for org-2
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":true,"data":{"balance": 25000000.0}}""")
        )

        // 3. Mock org member /usages response for member-2
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":true,"data":{"items":[]}}""")
        )

        val account = Account(connectionType = ConnectionType.CLINE_API, authType = AuthType.CLINE_API_KEY)
        val result = client.fetchBalance(account, "token")

        assertTrue(result is ProviderResult.Success)
        val success = result as ProviderResult.Success
        assertEquals(0, BigDecimal("25.00").compareTo(success.snapshot.balance.credits?.remaining))
    }

    @Test
    fun `test fetchBalance handles success false in response`() {
        // 1. Mock /me response
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":true,"data":{"id":"user-123","organizations":[]}}""")
        )

        // 2. Mock /balance response with success:false
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":false,"data":{"balance": 5000000.0}}""")
        )

        // 3. Mock /usages response
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":true,"data":{"items":[]}}""")
        )

        val account = Account(connectionType = ConnectionType.CLINE_API, authType = AuthType.CLINE_API_KEY)
        val result = client.fetchBalance(account, "token")

        // Should still succeed with zero balance (success:false means null data)
        assertTrue(result is ProviderResult.Success)
        val success = result as ProviderResult.Success
        assertEquals(0, BigDecimal.ZERO.compareTo(success.snapshot.balance.credits?.remaining))
    }

    @Test
    fun `test fetchBalance uses personal data when no active org`() {
        // 1. Mock /me response with orgs but none active
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"success":true,"data":{"id":"user-123","organizations":[
                    {"organizationId":"org-1","memberId":"member-1","active":false}
                ]}}"""
                )
        )

        // 2. Mock personal /balance response
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":true,"data":{"balance": 7500000.0}}""")
        )

        // 3. Mock personal /usages response
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"success":true,"data":{"items":[]}}""")
        )

        val account = Account(connectionType = ConnectionType.CLINE_API, authType = AuthType.CLINE_API_KEY)
        val result = client.fetchBalance(account, "token")

        assertTrue(result is ProviderResult.Success)
        val success = result as ProviderResult.Success
        assertEquals(0, BigDecimal("7.50").compareTo(success.snapshot.balance.credits?.remaining))
    }

    @Test
    fun `test fetchMe returns AuthError on 401`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(401))

        val account = Account(connectionType = ConnectionType.CLINE_API, authType = AuthType.CLINE_API_KEY)
        val result = client.fetchBalance(account, "bad-token")

        assertTrue(result is ProviderResult.Failure.AuthError)
    }

    @Test
    fun `test fetchMe returns AuthError on 403`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(403))

        val account = Account(connectionType = ConnectionType.CLINE_API, authType = AuthType.CLINE_API_KEY)
        val result = client.fetchBalance(account, "forbidden-token")

        assertTrue(result is ProviderResult.Failure.AuthError)
    }

    @Test
    fun `test fetchMe returns RateLimited on 429`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(429))

        val account = Account(connectionType = ConnectionType.CLINE_API, authType = AuthType.CLINE_API_KEY)
        val result = client.fetchBalance(account, "rate-limited-token")

        assertTrue(result is ProviderResult.Failure.RateLimited)
    }

    @Test
    fun `test fetchMe returns NetworkError on 500`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(500))

        val account = Account(connectionType = ConnectionType.CLINE_API, authType = AuthType.CLINE_API_KEY)
        val result = client.fetchBalance(account, "token")

        // Should NOT be AuthError - 500 is a server error, not an auth issue
        assertTrue(result is ProviderResult.Failure.NetworkError)
        assertTrue(result !is ProviderResult.Failure.AuthError, "500 should not be classified as AuthError")
    }

    @Test
    fun `test testCredentials returns AuthError on 401`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(401))

        val account = Account(connectionType = ConnectionType.CLINE_API, authType = AuthType.CLINE_API_KEY)
        val result = client.testCredentials(account, "bad-token")

        assertTrue(result is ProviderResult.Failure.AuthError)
    }

    @Test
    fun `test testCredentials returns NetworkError on 500`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(500))

        val account = Account(connectionType = ConnectionType.CLINE_API, authType = AuthType.CLINE_API_KEY)
        val result = client.testCredentials(account, "token")

        assertTrue(result is ProviderResult.Failure.NetworkError)
        assertTrue(result !is ProviderResult.Failure.AuthError, "500 should not be classified as AuthError")
    }
}
