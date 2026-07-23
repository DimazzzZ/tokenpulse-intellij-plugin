package org.zhavoronkov.tokenpulse.provider.nebius

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class NebiusSessionRefresherTest {

    private lateinit var server: MockWebServer
    private lateinit var refresher: NebiusSessionRefresher

    private fun session() = NebiusProviderClient.NebiusSession(
        appSession = "app-session-abc",
        csrfCookie = "old-csrf-cookie",
        csrfToken = "old-csrf-token",
        parentId = "contract-1"
    )

    private fun authenticatedHtml(token: String) =
        """<!doctype html><html><head><script>window.__DATA__ = """ +
            """{"config":{"appEnvironment":"prod"},"csrfToken":"$token",""" +
            """"isAuthenticatedOnPageLoad":true,"mfes":{}};</script></head><body></body></html>"""

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        refresher = NebiusSessionRefresher(
            httpClient = OkHttpClient(),
            baseUrl = server.url("/").toString().removeSuffix("/")
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `refreshCsrf returns fresh token from authenticated landing page`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody(authenticatedHtml("NEW-CSRF-TOKEN"))
        )

        val result = refresher.refreshCsrf(session())

        requireNotNull(result)
        assertEquals("NEW-CSRF-TOKEN", result.csrfToken)
        // No Set-Cookie => the CSRF cookie is preserved.
        assertEquals("old-csrf-cookie", result.csrfCookie)
        // Session identity preserved.
        assertEquals("app-session-abc", result.appSession)
        assertEquals("contract-1", result.parentId)
    }

    @Test
    fun `refreshCsrf adopts rotated csrf cookie from Set-Cookie`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .addHeader("Set-Cookie", "__Host-psifi.x-csrf-token=ROTATED-COOKIE; Path=/; HttpOnly")
                .setBody(authenticatedHtml("NEW-CSRF-TOKEN"))
        )

        val result = refresher.refreshCsrf(session())

        requireNotNull(result)
        assertEquals("NEW-CSRF-TOKEN", result.csrfToken)
        assertEquals("ROTATED-COOKIE", result.csrfCookie)
    }

    @Test
    fun `refreshCsrf returns null when page is not authenticated`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody(
                    """<html><script>window.__DATA__ = """ +
                        """{"csrfToken":"x","isAuthenticatedOnPageLoad":false};</script></html>"""
                )
        )

        assertNull(refresher.refreshCsrf(session()))
    }

    @Test
    fun `refreshCsrf returns null on redirect (session cookie dead)`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "/auth/redirect?redirect_uri=%2F")
        )

        assertNull(refresher.refreshCsrf(session()))
    }

    @Test
    fun `refreshCsrf returns null when csrfToken is absent`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody("""<html><script>window.__DATA__ = {"isAuthenticatedOnPageLoad":true};</script></html>""")
        )

        assertNull(refresher.refreshCsrf(session()))
    }

    @Test
    fun `refreshCsrf skips network when appSession is blank`() {
        val result = refresher.refreshCsrf(
            NebiusProviderClient.NebiusSession(appSession = null, csrfCookie = "c", csrfToken = "t", parentId = "p")
        )
        assertNull(result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `refreshCsrf sends current cookies to landing page`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody(authenticatedHtml("T"))
        )

        refresher.refreshCsrf(session())

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        val cookie = recorded.getHeader("cookie") ?: ""
        assertEquals(true, cookie.contains("__Host-app_session=app-session-abc"))
        assertEquals(true, cookie.contains("__Host-psifi.x-csrf-token=old-csrf-cookie"))
    }
}
