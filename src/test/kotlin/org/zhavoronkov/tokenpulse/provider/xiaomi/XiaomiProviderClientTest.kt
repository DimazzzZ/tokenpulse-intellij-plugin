package org.zhavoronkov.tokenpulse.provider.xiaomi

import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.zhavoronkov.tokenpulse.model.ConnectionType
import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.settings.Account

class XiaomiProviderClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: XiaomiProviderClient
    private val gson = Gson()

    private fun account(connectionType: ConnectionType) = Account(
        id = "test-account",
        connectionType = connectionType,
        authType = connectionType.defaultAuthType
    )

    private val validSession = gson.toJson(
        XiaomiProviderClient.XiaomiSession(
            serviceToken = "test-token",
            userId = "12345",
            slh = "test-slh",
            ph = "test-ph"
        )
    )

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = XiaomiProviderClient(
            httpClient = OkHttpClient(),
            gson = gson,
            baseUrl = server.url("/").toString().removeSuffix("/")
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `fetchBalance returns dollar balance for XIAOMI_API`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"code":0,"message":"","data":{"balance":"55.48","frozenBalance":"0.00","currency":"USD","overdraftLimit":"0.00","remainingOverdraftLimit":"0.00","giftBalance":"55.48","cashBalance":"0.00"}}"""
                )
        )

        val result = client.fetchBalance(account(ConnectionType.XIAOMI_API), validSession)

        assertTrue(result is ProviderResult.Success)
        val success = result as ProviderResult.Success
        assertEquals(ConnectionType.XIAOMI_API, success.snapshot.connectionType)
        assertEquals("55.48", success.snapshot.balance.credits?.remaining?.toPlainString())
        assertEquals("USD", success.snapshot.metadata["currency"])
        assertEquals("55.48", success.snapshot.metadata["giftBalance"])
    }

    @Test
    fun `fetchBalance returns Credits usage for XIAOMI_TOKEN_PLAN`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"code":0,"message":"","data":{"monthUsage":{"percent":0.248,"items":[{"name":"month_total_token","used":2727524596,"limit":11000000000,"percent":0.248}]},"usage":{"percent":0.25,"items":[{"name":"plan_total_token","used":2727524596,"limit":11000000000,"percent":0.25}]}}}"""
                )
        )

        val result = client.fetchBalance(account(ConnectionType.XIAOMI_TOKEN_PLAN), validSession)

        assertTrue(result is ProviderResult.Success)
        val success = result as ProviderResult.Success
        assertEquals(ConnectionType.XIAOMI_TOKEN_PLAN, success.snapshot.connectionType)
        assertEquals(2727524596L, success.snapshot.balance.tokens?.used)
        assertEquals(11000000000L, success.snapshot.balance.tokens?.total)
        assertEquals(8272475404L, success.snapshot.balance.tokens?.remaining)
    }

    @Test
    fun `fetchBalance returns AuthError when session is expired`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
        )

        val result = client.fetchBalance(account(ConnectionType.XIAOMI_API), validSession)

        assertTrue(result is ProviderResult.Failure.AuthError)
    }

    @Test
    fun `fetchBalance returns AuthError when session JSON is invalid`() {
        val result = client.fetchBalance(account(ConnectionType.XIAOMI_API), "not-valid-json")

        assertTrue(result is ProviderResult.Failure.AuthError)
    }

    @Test
    fun `fetchBalance returns AuthError when serviceToken is blank`() {
        val badSession = gson.toJson(
            XiaomiProviderClient.XiaomiSession(serviceToken = null, userId = "12345")
        )

        val result = client.fetchBalance(account(ConnectionType.XIAOMI_API), badSession)

        assertTrue(result is ProviderResult.Failure.AuthError)
    }

    @Test
    fun `testCredentials returns success for valid session`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"code":0,"message":"","data":{"balance":"10.00","currency":"USD"}}""")
        )

        val result = client.testCredentials(account(ConnectionType.XIAOMI_API), validSession)

        assertTrue(result is ProviderResult.Success)
    }

    @Test
    fun `testCredentials returns AuthError for expired session`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
        )

        val result = client.testCredentials(account(ConnectionType.XIAOMI_API), validSession)

        assertTrue(result is ProviderResult.Failure.AuthError)
    }

    @Test
    fun `fetchBalance sends correct cookies in request`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"code":0,"message":"","data":{"balance":"0","currency":"USD"}}""")
        )

        client.fetchBalance(account(ConnectionType.XIAOMI_API), validSession)

        val request = server.takeRequest()
        val cookie = request.getHeader("Cookie") ?: ""
        assertTrue(cookie.contains("api-platform_serviceToken=\"test-token\""))
        assertTrue(cookie.contains("userId=12345"))
        assertTrue(cookie.contains("api-platform_slh=\"test-slh\""))
        assertTrue(cookie.contains("api-platform_ph=\"test-ph\""))
    }

    @Test
    fun `fetchBalance returns RateLimited on 429`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
        )

        val result = client.fetchBalance(account(ConnectionType.XIAOMI_API), validSession)

        assertTrue(result is ProviderResult.Failure.RateLimited)
    }

    @Test
    fun `fetchBalance returns NetworkError on 500`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
        )

        val result = client.fetchBalance(account(ConnectionType.XIAOMI_API), validSession)

        assertTrue(result is ProviderResult.Failure.NetworkError)
    }

    @Test
    fun `testCredentials returns success for XIAOMI_TOKEN_PLAN`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"code":0,"message":"","data":{"monthUsage":{"percent":0.25,"items":[]}}}"""
                )
        )

        val result = client.testCredentials(
            account(ConnectionType.XIAOMI_TOKEN_PLAN),
            validSession
        )

        assertTrue(result is ProviderResult.Success)
    }

    @Test
    fun `fetchBalance returns AuthError for unsupported connection type`() {
        val unsupportedAccount = Account(
            id = "test-account",
            connectionType = ConnectionType.CLINE_API,
            authType = ConnectionType.CLINE_API.defaultAuthType
        )

        val result = client.fetchBalance(unsupportedAccount, validSession)

        assertTrue(result is ProviderResult.Failure.AuthError)
    }

    @Test
    fun `fetchTokenPlanUsage handles null monthUsage gracefully`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"code":0,"message":"","data":{"monthUsage":null}}"""
                )
        )

        val result = client.fetchBalance(
            account(ConnectionType.XIAOMI_TOKEN_PLAN),
            validSession
        )

        assertTrue(result is ProviderResult.Success)
        val success = result as ProviderResult.Success
        assertEquals(0L, success.snapshot.balance.tokens?.used)
        assertEquals(0L, success.snapshot.balance.tokens?.total)
        assertEquals(0L, success.snapshot.balance.tokens?.remaining)
        // totalCredits=0 → sessionUsed=100 → UI shows "0% remaining"
        assertEquals("100", success.snapshot.metadata["sessionUsed"])
    }

    @Test
    fun `fetchTokenPlanUsage handles null items array gracefully`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"code":0,"message":"","data":{"monthUsage":{"percent":0.0,"items":null}}}"""
                )
        )

        val result = client.fetchBalance(
            account(ConnectionType.XIAOMI_TOKEN_PLAN),
            validSession
        )

        assertTrue(result is ProviderResult.Success)
        val success = result as ProviderResult.Success
        assertEquals(0L, success.snapshot.balance.tokens?.used)
        assertEquals(0L, success.snapshot.balance.tokens?.total)
        assertEquals(0L, success.snapshot.balance.tokens?.remaining)
        assertEquals("100", success.snapshot.metadata["sessionUsed"])
    }

    @Test
    fun `fetchTokenPlanUsage handles null data gracefully`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"code":0,"message":"","data":null}"""
                )
        )

        val result = client.fetchBalance(
            account(ConnectionType.XIAOMI_TOKEN_PLAN),
            validSession
        )

        assertTrue(result is ProviderResult.Success)
        val success = result as ProviderResult.Success
        assertEquals(0L, success.snapshot.balance.tokens?.used)
        assertEquals(0L, success.snapshot.balance.tokens?.total)
        assertEquals(0L, success.snapshot.balance.tokens?.remaining)
        assertEquals("100", success.snapshot.metadata["sessionUsed"])
    }

    @Test
    fun `fetchApiBalance returns AuthError when code is not zero`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"code":-1,"message":"Token expired","data":{}}""")
        )

        val result = client.fetchBalance(account(ConnectionType.XIAOMI_API), validSession)

        assertTrue(result is ProviderResult.Failure.AuthError)
        assertEquals(
            "Xiaomi API error: Token expired",
            (result as ProviderResult.Failure.AuthError).message
        )
    }

    @Test
    fun `fetchTokenPlanUsage returns AuthError when code is not zero`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"code":-1,"message":"Session invalid","data":{}}""")
        )

        val result = client.fetchBalance(
            account(ConnectionType.XIAOMI_TOKEN_PLAN),
            validSession
        )

        assertTrue(result is ProviderResult.Failure.AuthError)
        assertEquals(
            "Xiaomi API error: Session invalid",
            (result as ProviderResult.Failure.AuthError).message
        )
    }

    @Test
    fun `fetchTokenPlanUsage handles missing monthUsage items`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"code":0,"message":"","data":{"monthUsage":{"percent":0.0}}}"""
                )
        )

        val result = client.fetchBalance(
            account(ConnectionType.XIAOMI_TOKEN_PLAN),
            validSession
        )

        assertTrue(result is ProviderResult.Success)
        val success = result as ProviderResult.Success
        assertEquals(0L, success.snapshot.balance.tokens?.used)
        assertEquals(0L, success.snapshot.balance.tokens?.total)
        assertEquals(0L, success.snapshot.balance.tokens?.remaining)
    }

    @Test
    fun `fetchApiBalance handles missing balance fields with zero fallback`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"code":0,"message":"","data":{"currency":"USD"}}""")
        )

        val result = client.fetchBalance(account(ConnectionType.XIAOMI_API), validSession)

        assertTrue(result is ProviderResult.Success)
        val success = result as ProviderResult.Success
        assertEquals("0", success.snapshot.balance.credits?.remaining?.toPlainString())
    }

    @Test
    fun `testCredentials returns AuthError for unsupported connection type`() {
        val unsupportedAccount = Account(
            id = "test-account",
            connectionType = ConnectionType.CLINE_API,
            authType = ConnectionType.CLINE_API.defaultAuthType
        )

        val result = client.testCredentials(unsupportedAccount, validSession)

        assertTrue(result is ProviderResult.Failure.AuthError)
    }
}
