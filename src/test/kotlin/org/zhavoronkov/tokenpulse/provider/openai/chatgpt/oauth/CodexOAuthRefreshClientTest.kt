package org.zhavoronkov.tokenpulse.provider.openai.chatgpt.oauth

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

class CodexOAuthRefreshClientTest {

    private lateinit var server: HttpServer
    private var status: Int = 200
    private var body: String = ""

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/oauth/token") { exchange ->
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterEach
    fun tearDown() = server.stop(0)

    private fun client(): CodexOAuthRefreshClient =
        CodexOAuthRefreshClient("http://127.0.0.1:${server.address.port}/oauth/token")

    @Test
    fun `200 surfaces rotated tokens`() {
        status = 200
        body = """{"id_token":"new-id","access_token":"new-a","refresh_token":"new-r"}"""
        val r = client().refresh("old-r")
        assertTrue(r is CodexOAuthRefreshClient.RefreshResult.Refreshed)
        r as CodexOAuthRefreshClient.RefreshResult.Refreshed
        assertEquals("new-a", r.accessToken)
        assertEquals("new-r", r.refreshToken)
        assertEquals("new-id", r.idToken)
    }

    @Test
    fun `200 without access token is transient`() {
        status = 200
        body = """{"id_token":"x"}"""
        assertTrue(client().refresh("r") is CodexOAuthRefreshClient.RefreshResult.Transient)
    }

    @Test
    fun `400 refresh_token_reused is permanent auth error`() {
        status = 400
        body = """{"error":{"code":"refresh_token_reused"}}"""
        val r = client().refresh("r")
        assertTrue(r is CodexOAuthRefreshClient.RefreshResult.AuthError)
        assertEquals(
            CodexOAuthRefreshClient.RefreshFailureReason.Reused,
            (r as CodexOAuthRefreshClient.RefreshResult.AuthError).reason
        )
    }

    @Test
    fun `400 refresh_token_expired is permanent auth error`() {
        status = 400
        body = """{"error":"refresh_token_expired"}"""
        val r = client().refresh("r")
        assertTrue(r is CodexOAuthRefreshClient.RefreshResult.AuthError)
        assertEquals(
            CodexOAuthRefreshClient.RefreshFailureReason.Expired,
            (r as CodexOAuthRefreshClient.RefreshResult.AuthError).reason
        )
    }

    @Test
    fun `401 without recognized code is still permanent auth error`() {
        status = 401
        body = "unauthorized"
        val r = client().refresh("r")
        assertTrue(r is CodexOAuthRefreshClient.RefreshResult.AuthError)
        assertEquals(
            CodexOAuthRefreshClient.RefreshFailureReason.Other,
            (r as CodexOAuthRefreshClient.RefreshResult.AuthError).reason
        )
    }

    @Test
    fun `400 with unknown code is transient (bug in our request, retry-able)`() {
        status = 400
        body = """{"error":"invalid_request"}"""
        assertTrue(client().refresh("r") is CodexOAuthRefreshClient.RefreshResult.Transient)
    }

    @Test
    fun `500 is transient`() {
        status = 500
        body = "boom"
        assertTrue(client().refresh("r") is CodexOAuthRefreshClient.RefreshResult.Transient)
    }

    @Test
    fun `blank refresh token is auth error`() {
        val r = client().refresh("")
        assertTrue(r is CodexOAuthRefreshClient.RefreshResult.AuthError)
    }
}
