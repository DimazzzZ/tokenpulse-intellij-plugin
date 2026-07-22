package org.zhavoronkov.tokenpulse.provider.xiaomi

import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class XiaomiSessionRefresherTest {

    private lateinit var server: MockWebServer
    private lateinit var refresher: XiaomiSessionRefresher
    private val gson = Gson()

    private fun sessionWithPassport() = XiaomiProviderClient.XiaomiSession(
        serviceToken = "old-token",
        userId = "12345",
        slh = "old-slh",
        ph = "old-ph",
        passToken = "pass-abc",
        cUserId = "cuser-9"
    )

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        val base = server.url("/").toString().removeSuffix("/")
        refresher = XiaomiSessionRefresher(
            httpClient = OkHttpClient(),
            gson = gson,
            platformBaseUrl = base,
            accountBaseUrl = base
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    /**
     * Route by path so the three-step chain (genLoginUrl -> serviceLogin -> sts) can
     * be scripted independently against a single mock server.
     */
    private fun dispatch(
        genLogin: MockResponse,
        serviceLogin: MockResponse,
        sts: MockResponse
    ) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.startsWith("/api/v1/genLoginUrl") -> genLogin
                    path.startsWith("/pass/serviceLogin") -> serviceLogin
                    path.startsWith("/sts") -> sts
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    @Test
    fun `refresh happy path returns fresh session and preserves passport`() {
        dispatch(
            genLogin = MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "https://account.xiaomi.com/pass/serviceLogin?sid=api-platform&_group=DEFAULT"),
            serviceLogin = MockResponse()
                .setResponseCode(200)
                .setBody(
                    "&&&START&&&{\"code\":0,\"ssecurity\":\"sec-xyz\"," +
                        "\"location\":\"https://platform.xiaomimimo.com/sts?sign=abc&followup=x\"}"
                ),
            sts = MockResponse()
                .setResponseCode(302)
                .addHeader("Set-Cookie", "api-platform_serviceToken=NEW-TOKEN; Path=/; HttpOnly")
                .addHeader("Set-Cookie", "api-platform_slh=NEW-SLH; Path=/")
                .addHeader("Set-Cookie", "api-platform_ph=NEW-PH; Path=/")
        )

        val result = refresher.refresh(sessionWithPassport())

        requireNotNull(result)
        assertEquals("NEW-TOKEN", result.serviceToken)
        assertEquals("NEW-SLH", result.slh)
        assertEquals("NEW-PH", result.ph)
        // Passport credentials preserved for the next refresh.
        assertEquals("pass-abc", result.passToken)
        assertEquals("12345", result.userId)
        assertEquals("cuser-9", result.cUserId)
    }

    @Test
    fun `refresh reads serviceLogin url from 200 json body location`() {
        dispatch(
            genLogin = MockResponse()
                .setResponseCode(200)
                .setBody("{\"location\":\"https://account.xiaomi.com/pass/serviceLogin?sid=api-platform\"}"),
            serviceLogin = MockResponse()
                .setResponseCode(200)
                .setBody(
                    "&&&START&&&{\"ssecurity\":\"s\",\"location\":\"https://platform.xiaomimimo.com/sts?sign=z\"}"
                ),
            sts = MockResponse()
                .setResponseCode(200)
                .addHeader("Set-Cookie", "api-platform_serviceToken=T2; Path=/")
        )

        val result = refresher.refresh(sessionWithPassport())

        requireNotNull(result)
        assertEquals("T2", result.serviceToken)
    }

    @Test
    fun `refresh returns null when no passToken`() {
        val result = refresher.refresh(
            XiaomiProviderClient.XiaomiSession(serviceToken = "t", userId = "1", passToken = null)
        )
        assertNull(result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `refresh returns null when serviceLogin lacks ssecurity`() {
        dispatch(
            genLogin = MockResponse().setResponseCode(302)
                .setHeader("Location", "https://account.xiaomi.com/pass/serviceLogin?sid=api-platform"),
            serviceLogin = MockResponse().setResponseCode(200)
                .setBody("&&&START&&&{\"code\":70016,\"_sign\":\"needs-login\"}"),
            sts = MockResponse().setResponseCode(200)
        )

        assertNull(refresher.refresh(sessionWithPassport()))
    }

    @Test
    fun `refresh returns null when sts sets no serviceToken cookie`() {
        dispatch(
            genLogin = MockResponse().setResponseCode(302)
                .setHeader("Location", "https://account.xiaomi.com/pass/serviceLogin?sid=api-platform"),
            serviceLogin = MockResponse().setResponseCode(200)
                .setBody("&&&START&&&{\"ssecurity\":\"s\",\"location\":\"https://platform.xiaomimimo.com/sts?sign=z\"}"),
            sts = MockResponse().setResponseCode(302)
                .addHeader("Set-Cookie", "some_other_cookie=abc; Path=/")
        )

        assertNull(refresher.refresh(sessionWithPassport()))
    }

    @Test
    fun `refresh returns null on malformed serviceLogin body`() {
        dispatch(
            genLogin = MockResponse().setResponseCode(302)
                .setHeader("Location", "https://account.xiaomi.com/pass/serviceLogin?sid=api-platform"),
            serviceLogin = MockResponse().setResponseCode(200).setBody("not-json-at-all"),
            sts = MockResponse().setResponseCode(200)
        )

        assertNull(refresher.refresh(sessionWithPassport()))
    }

    @Test
    fun `refresh sends passport cookies to serviceLogin`() {
        dispatch(
            genLogin = MockResponse().setResponseCode(302)
                .setHeader("Location", "https://account.xiaomi.com/pass/serviceLogin?sid=api-platform"),
            serviceLogin = MockResponse().setResponseCode(200)
                .setBody("&&&START&&&{\"ssecurity\":\"s\",\"location\":\"https://platform.xiaomimimo.com/sts?sign=z\"}"),
            sts = MockResponse().setResponseCode(200)
                .addHeader("Set-Cookie", "api-platform_serviceToken=T; Path=/")
        )

        refresher.refresh(sessionWithPassport())

        var sawServiceLogin = false
        repeat(server.requestCount) {
            val recorded = server.takeRequest()
            if ((recorded.path ?: "").startsWith("/pass/serviceLogin")) {
                sawServiceLogin = true
                val cookie = recorded.getHeader("Cookie") ?: ""
                assertEquals(true, cookie.contains("passToken=pass-abc"))
                assertEquals(true, cookie.contains("_json") || recorded.path!!.contains("_json=true"))
            }
        }
        assertEquals(true, sawServiceLogin)
    }
}
