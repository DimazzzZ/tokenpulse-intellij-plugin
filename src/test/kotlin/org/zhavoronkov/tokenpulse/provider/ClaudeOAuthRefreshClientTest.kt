package org.zhavoronkov.tokenpulse.provider

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.zhavoronkov.tokenpulse.provider.anthropic.claudecode.ClaudeOAuthRefreshClient
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * Unit tests for [ClaudeOAuthRefreshClient] using a local HTTP server so we can
 * assert on status handling and response parsing without hitting the real
 * Anthropic auth server.
 */
class ClaudeOAuthRefreshClientTest {

    private lateinit var server: HttpServer
    private var status: Int = 200
    private var body: String = ""
    private var delayMs: Long = 0

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/oauth/token") { exchange ->
            if (delayMs > 0) {
                Thread.sleep(delayMs)
            }
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

    private fun client(): ClaudeOAuthRefreshClient {
        val base = "http://127.0.0.1:${server.address.port}/v1/oauth/token"
        return ClaudeOAuthRefreshClient(base)
    }

    @Test
    fun `200 with rotated refresh token returns Success with new tokens`() {
        status = 200
        body = """
            {"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600,"scope":"user:profile user:inference"}
        """.trimIndent()

        val result = client().refresh("old-refresh")

        assertTrue(result is ClaudeOAuthRefreshClient.RefreshResult.Success)
        result as ClaudeOAuthRefreshClient.RefreshResult.Success
        assertEquals("new-access", result.accessToken)
        assertEquals("new-refresh", result.refreshToken)
        assertEquals("user:profile user:inference", result.scope)
        assertTrue(result.expiresAt > System.currentTimeMillis())
    }

    @Test
    fun `200 without refresh token returns Success with null refresh token`() {
        status = 200
        body = """{"access_token":"new-access","expires_in":3600,"scope":"user:profile"}"""

        val result = client().refresh("old-refresh")

        assertTrue(result is ClaudeOAuthRefreshClient.RefreshResult.Success)
        result as ClaudeOAuthRefreshClient.RefreshResult.Success
        assertEquals("new-access", result.accessToken)
        assertNull(result.refreshToken)
    }

    @Test
    fun `401 returns auth error`() {
        status = 401
        body = """{"error":"invalid_grant"}"""

        val result = client().refresh("bad-refresh")

        assertTrue(result is ClaudeOAuthRefreshClient.RefreshResult.Error)
        result as ClaudeOAuthRefreshClient.RefreshResult.Error
        assertTrue(result.isAuthError)
    }

    @Test
    fun `400 returns auth error`() {
        status = 400
        body = """{"error":"invalid_request"}"""

        val result = client().refresh("bad-refresh")

        assertTrue(result is ClaudeOAuthRefreshClient.RefreshResult.Error)
        result as ClaudeOAuthRefreshClient.RefreshResult.Error
        assertTrue(result.isAuthError)
    }

    @Test
    fun `500 returns non-auth error`() {
        status = 500
        body = "internal error"

        val result = client().refresh("some-refresh")

        assertTrue(result is ClaudeOAuthRefreshClient.RefreshResult.Error)
        result as ClaudeOAuthRefreshClient.RefreshResult.Error
        assertTrue(!result.isAuthError)
    }

    @Test
    fun `blank refresh token short-circuits to auth error without a request`() {
        val result = client().refresh("")

        assertTrue(result is ClaudeOAuthRefreshClient.RefreshResult.Error)
        result as ClaudeOAuthRefreshClient.RefreshResult.Error
        assertTrue(result.isAuthError)
    }

    @Test
    fun `malformed json 200 returns error`() {
        status = 200
        body = "not-json{"

        val result = client().refresh("old-refresh")

        assertTrue(result is ClaudeOAuthRefreshClient.RefreshResult.Error)
    }
}
