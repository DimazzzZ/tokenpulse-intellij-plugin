package org.zhavoronkov.tokenpulse.provider.openai.chatgpt.oauth

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

class CodexOAuthUsageClientTest {

    private lateinit var server: HttpServer
    private var status: Int = 200
    private var body: String = ""
    private var lastAccountId: String? = null
    private var lastAuthorization: String? = null
    private var lastFedramp: String? = null

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/backend-api/wham/usage") { exchange ->
            lastAccountId = exchange.requestHeaders.getFirst("ChatGPT-Account-Id")
            lastAuthorization = exchange.requestHeaders.getFirst("Authorization")
            lastFedramp = exchange.requestHeaders.getFirst("X-OpenAI-Fedramp")
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterEach
    fun tearDown() = server.stop(0)

    private fun client(): CodexOAuthUsageClient =
        CodexOAuthUsageClient("http://127.0.0.1:${server.address.port}/backend-api/wham/usage")

    @Test
    fun `200 parses both windows plan email and code-review`() {
        status = 200
        body = """
            {"user_id":"u","account_id":"acc-1","email":"a@b.com","plan_type":"plus",
             "rate_limit":{
               "allowed":true,"limit_reached":false,
               "primary_window":{"used_percent":42,"limit_window_seconds":18000,"reset_after_seconds":100,"reset_at":1784000000},
               "secondary_window":{"used_percent":84,"limit_window_seconds":604800,"reset_after_seconds":100,"reset_at":1784600000}
             },
             "code_review_rate_limit":null,
             "additional_rate_limits":[
               {"limit_name":"Code Review","metered_feature":"code_review","rate_limit":{"primary_window":{"used_percent":10,"limit_window_seconds":604800,"reset_at":1784600000},"secondary_window":null}}
             ]}
        """.trimIndent()

        val result = client().fetch("access-tok", "acc-1")
        assertTrue(result is CodexOAuthUsageClient.UsageResult.Success)
        result as CodexOAuthUsageClient.UsageResult.Success
        assertEquals(42, result.usage.fiveHour?.usedPercent)
        assertEquals(84, result.usage.weekly?.usedPercent)
        assertEquals(10, result.usage.codeReview?.usedPercent)
        assertEquals("plus", result.usage.planType)
        assertEquals("a@b.com", result.usage.email)
        assertEquals(1784000000L, result.usage.fiveHour?.resetAtEpochSeconds)
    }

    @Test
    fun `200 weekly-only account (no 5-hour window) is the normal case`() {
        // OpenAI removed the short window for many plans: only a weekly window
        // is returned, carried in primary_window; secondary is null.
        status = 200
        body = """
            {"email":"a@b.com","plan_type":"plus",
             "rate_limit":{
               "primary_window":{"used_percent":1,"limit_window_seconds":604800,"reset_at":1785258243},
               "secondary_window":null
             }}
        """.trimIndent()

        val result = client().fetch("access-tok", "acc-1")
        assertTrue(result is CodexOAuthUsageClient.UsageResult.Success)
        result as CodexOAuthUsageClient.UsageResult.Success
        assertNull(result.usage.fiveHour)
        assertEquals(1, result.usage.weekly?.usedPercent)
        assertEquals(1785258243L, result.usage.weekly?.resetAtEpochSeconds)
        assertNull(result.usage.codeReview)
    }

    @Test
    fun `200 5-hour-only account maps to fiveHour slot`() {
        status = 200
        body = """
            {"plan_type":"plus",
             "rate_limit":{
               "primary_window":{"used_percent":30,"limit_window_seconds":18000,"reset_at":1784000000},
               "secondary_window":null
             }}
        """.trimIndent()

        val result = client().fetch("t", "acc")
        assertTrue(result is CodexOAuthUsageClient.UsageResult.Success)
        result as CodexOAuthUsageClient.UsageResult.Success
        assertEquals(30, result.usage.fiveHour?.usedPercent)
        assertNull(result.usage.weekly)
    }

    @Test
    fun `request sends bearer token and account id header`() {
        status = 200
        body = """{"rate_limit":{"primary_window":null,"secondary_window":null}}"""
        client().fetch("access-tok", "acc-1")
        assertEquals("Bearer access-tok", lastAuthorization)
        assertEquals("acc-1", lastAccountId)
        assertNull(lastFedramp)
    }

    @Test
    fun `fedramp header only sent when flag set`() {
        status = 200
        body = """{"rate_limit":{"primary_window":null,"secondary_window":null}}"""
        client().fetch("access-tok", "acc-1", fedramp = true)
        assertEquals("true", lastFedramp)
    }

    @Test
    fun `401 maps to AuthError`() {
        status = 401
        body = "unauthorized"
        assertEquals(CodexOAuthUsageClient.UsageResult.AuthError, client().fetch("t", "acc"))
    }

    @Test
    fun `403 maps to Forbidden non-auth`() {
        status = 403
        body = "forbidden"
        val r = client().fetch("t", "acc")
        assertTrue(r is CodexOAuthUsageClient.UsageResult.Forbidden)
    }

    @Test
    fun `429 maps to RateLimited`() {
        status = 429
        body = "slow down"
        assertEquals(CodexOAuthUsageClient.UsageResult.RateLimited, client().fetch("t", "acc"))
    }

    @Test
    fun `500 maps to Transient`() {
        status = 500
        body = "boom"
        assertTrue(client().fetch("t", "acc") is CodexOAuthUsageClient.UsageResult.Transient)
    }

    @Test
    fun `blank token short-circuits to AuthError`() {
        assertEquals(CodexOAuthUsageClient.UsageResult.AuthError, client().fetch("", "acc"))
    }
}
