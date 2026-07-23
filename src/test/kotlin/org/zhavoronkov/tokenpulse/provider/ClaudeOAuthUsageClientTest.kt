package org.zhavoronkov.tokenpulse.provider

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.zhavoronkov.tokenpulse.provider.anthropic.claudecode.ClaudeOAuthUsageClient
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * Tests for [ClaudeOAuthUsageClient] using a local HTTP stub so we can assert
 * on status-code classification (esp. that 403 is NOT an auth error) and that
 * the request carries the `anthropic-version` header — without hitting the real
 * Anthropic endpoint.
 */
class ClaudeOAuthUsageClientTest {

    private lateinit var server: HttpServer
    private var status: Int = 200
    private var body: String = ""
    private var lastAnthropicVersion: String? = null

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/oauth/usage") { exchange ->
            lastAnthropicVersion = exchange.requestHeaders.getFirst("anthropic-version")
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    private fun client(): ClaudeOAuthUsageClient {
        val base = "http://127.0.0.1:${server.address.port}/api/oauth/usage"
        return ClaudeOAuthUsageClient(base)
    }

    @Test
    fun `200 parses usage into Success`() {
        status = 200
        body = """{"five_hour":{"utilization":0.25,"resets_at":"2026-01-01T00:00:00Z"},"seven_day":{"utilization":10}}"""

        val result = client().fetchUsage("token")

        assertTrue(result is ClaudeOAuthUsageClient.OAuthUsageResult.Success)
        result as ClaudeOAuthUsageClient.OAuthUsageResult.Success
        assertEquals(25, result.usageData.sessionUsedPercent)
        assertEquals(10, result.usageData.weekUsedPercent)
    }

    @Test
    fun `seven_day utilization of 1_0 is 1 percent not 100`() {
        // Regression: a percentage-mode value of 1.0 means 1%, not "100% as a fraction".
        // Previously `value <= 1.0` computed (1.0 * 100).toInt() = 100, showing a full red
        // weekly bar for an account with ~1% real usage.
        status = 200
        body = """{"five_hour":{"utilization":0.08},"seven_day":{"utilization":1.0}}"""

        val result = client().fetchUsage("token")

        assertTrue(result is ClaudeOAuthUsageClient.OAuthUsageResult.Success)
        result as ClaudeOAuthUsageClient.OAuthUsageResult.Success
        assertEquals(8, result.usageData.sessionUsedPercent)
        assertEquals(1, result.usageData.weekUsedPercent)
    }

    @Test
    fun `fractional utilization rounds instead of truncating`() {
        // 0.999 fraction = 99.9% should round to 100, not floor to 99.
        status = 200
        body = """{"five_hour":{"utilization":0.999},"seven_day":{"utilization":0.845}}"""

        val result = client().fetchUsage("token")

        assertTrue(result is ClaudeOAuthUsageClient.OAuthUsageResult.Success)
        result as ClaudeOAuthUsageClient.OAuthUsageResult.Success
        assertEquals(100, result.usageData.sessionUsedPercent)
        assertEquals(85, result.usageData.weekUsedPercent) // 84.5 rounds to 85
    }

    @Test
    fun `request carries anthropic-version header`() {
        status = 200
        body = "{}"

        client().fetchUsage("token")

        assertEquals("2023-06-01", lastAnthropicVersion)
    }

    @Test
    fun `401 is an auth error`() {
        status = 401
        body = """{"error":"unauthorized"}"""

        val result = client().fetchUsage("token")

        assertTrue(result is ClaudeOAuthUsageClient.OAuthUsageResult.Error)
        result as ClaudeOAuthUsageClient.OAuthUsageResult.Error
        assertTrue(result.isAuthError)
    }

    @Test
    fun `403 is NOT an auth error`() {
        status = 403
        body = """{"error":"forbidden"}"""

        val result = client().fetchUsage("token")

        assertTrue(result is ClaudeOAuthUsageClient.OAuthUsageResult.Error)
        result as ClaudeOAuthUsageClient.OAuthUsageResult.Error
        assertFalse(result.isAuthError, "403 must not be surfaced as a session/auth error")
    }

    @Test
    fun `429 is rate limited, not auth`() {
        status = 429
        body = "rate limited"

        val result = client().fetchUsage("token")

        assertTrue(result is ClaudeOAuthUsageClient.OAuthUsageResult.Error)
        result as ClaudeOAuthUsageClient.OAuthUsageResult.Error
        assertTrue(result.isRateLimited)
        assertFalse(result.isAuthError)
    }

    @Test
    fun `500 is a non-auth error`() {
        status = 500
        body = "boom"

        val result = client().fetchUsage("token")

        assertTrue(result is ClaudeOAuthUsageClient.OAuthUsageResult.Error)
        result as ClaudeOAuthUsageClient.OAuthUsageResult.Error
        assertFalse(result.isAuthError)
    }
}
