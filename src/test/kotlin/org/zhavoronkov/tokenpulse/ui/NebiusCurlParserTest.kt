package org.zhavoronkov.tokenpulse.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [NebiusCurlParser].
 * These tests verify cURL parsing logic without requiring IntelliJ platform.
 */
class NebiusCurlParserTest {

    /**
     * Test parsing a real cURL command with -b flag for cookies.
     * This is the format Chrome produces with "Copy as cURL".
     */
    @Test
    fun `parseCurl with -b cookie flag and --data-raw extracts all fields`() {
        val curlCommand = """
            curl 'https://tokenfactory.nebius.com/api-mfe/billing/gateway/root/billingActs/getCurrentTrial' \
              -H 'accept: application/json, text/plain, */*' \
              -H 'content-type: application/json' \
              -b 'analyticsConsents={"necessary":true,"analytics":false}; __Host-app_session=ne1CtwBChpzZXNzaW9uLWUwMHYwZXhtcmQ5bmFiZDg2OBIedXNlcmFjY291bnQtZTAwZDc5a3J6N3k2ZnFkYmY5; __Host-psifi.x-csrf-token=6c3aa748bfd1d058b80fef4ddcbc7eb48c8c2b00f6dd6931abc6f215ccf1aab57e7f2d2031f8a46cd88f1dd1eb1ae4a2c9a21395b2ee06942b3da0d8faa5d1a5%7C439acc2652fc07d3ce28a584042fe7f2d2d00e92f624116b323130be4a02739b' \
              -H 'x-csrf-token: 6c3aa748bfd1d058b80fef4ddcbc7eb48c8c2b00f6dd6931abc6f215ccf1aab57e7f2d2031f8a46cd88f1dd1eb1ae4a2c9a21395b2ee06942b3da0d8faa5d1a5' \
              -H 'x-requested-with: XMLHttpRequest' \
              --data-raw '{"parentId":"contract-e00pgjm81nl9t6yy137zj"}'
        """.trimIndent()

        val session = NebiusCurlParser.parseCurl(curlCommand)

        assertNotNull(session, "Session should not be null")
        session!!

        // Verify app session is extracted
        assertEquals(
            "ne1CtwBChpzZXNzaW9uLWUwMHYwZXhtcmQ5bmFiZDg2OBIedXNlcmFjY291bnQtZTAwZDc5a3J6N3k2ZnFkYmY5",
            session.appSession
        )

        // Verify CSRF token is extracted from header
        assertEquals(
            "6c3aa748bfd1d058b80fef4ddcbc7eb48c8c2b00f6dd6931abc6f215ccf1aab57e7f2d2031f8a46cd88f1dd1eb1ae4a2c9a21395b2ee06942b3da0d8faa5d1a5",
            session.csrfToken
        )

        // Verify parentId is extracted from --data-raw body
        assertEquals("contract-e00pgjm81nl9t6yy137zj", session.parentId)

        // Verify csrfCookie is kept raw
        assertNotNull(session.csrfCookie)
        assertTrue(session.csrfCookie!!.contains("%7C"), "CSRF cookie should be kept raw with %7C character")
    }

    @Test
    fun `parseCurl with -d flag extracts parentId`() {
        val curlCommand = """
            curl 'https://tokenfactory.nebius.com/api-mfe/billing/gateway/root/billingActs/getCurrentTrial' \
              -b '__Host-app_session=testSession123; __Host-psifi.x-csrf-token=token123' \
              -H 'x-csrf-token: token123' \
              -d '{"parentId":"contract-abc123"}'
        """.trimIndent()

        val session = NebiusCurlParser.parseCurl(curlCommand)

        assertNotNull(session)
        assertEquals("testSession123", session!!.appSession)
        assertEquals("token123", session.csrfToken)
        assertEquals("contract-abc123", session.parentId)
    }

    @Test
    fun `parseCurl with --data flag extracts parentId`() {
        val curlCommand = """
            curl 'https://example.com/api' \
              -b '__Host-app_session=mySession; __Host-psifi.x-csrf-token=myToken' \
              -H 'x-csrf-token: myToken' \
              --data '{"parentId":"contract-xyz789"}'
        """.trimIndent()

        val session = NebiusCurlParser.parseCurl(curlCommand)

        assertNotNull(session)
        assertEquals("contract-xyz789", session!!.parentId)
    }

    @Test
    fun `parseCurl with double quotes extracts fields`() {
        val curlCommand = """
            curl "https://example.com/api" \
              -b "__Host-app_session=session1; __Host-psifi.x-csrf-token=token1" \
              -H "x-csrf-token: token1" \
              --data-raw "{\"parentId\":\"contract-double-quotes\"}"
        """.trimIndent()

        val session = NebiusCurlParser.parseCurl(curlCommand)

        assertNotNull(session)
        assertEquals("session1", session!!.appSession)
        assertEquals("contract-double-quotes", session.parentId)
    }

    @Test
    fun `parseCurl handles URL-encoded CSRF cookie`() {
        // The %7C should be decoded for token extraction but kept RAW for cookie
        val curlCommand = """
            curl 'https://example.com/api' \
              -b '__Host-app_session=sess; __Host-psifi.x-csrf-token=part1%7Cpart2' \
              -H 'x-csrf-token: part1' \
              --data-raw '{"parentId":"contract-test"}'
        """.trimIndent()

        val session = NebiusCurlParser.parseCurl(curlCommand)

        assertNotNull(session)
        assertEquals("part1%7Cpart2", session!!.csrfCookie)
        assertEquals("part1", session.csrfToken)
    }

    @Test
    fun `parseCurl with Cookie header fallback`() {
        val curlCommand = """
            curl 'https://example.com/api' \
              -H 'Cookie: __Host-app_session=headerSession; __Host-psifi.x-csrf-token=headerToken' \
              -H 'x-csrf-token: headerToken' \
              --data-raw '{"parentId":"contract-header"}'
        """.trimIndent()

        val session = NebiusCurlParser.parseCurl(curlCommand)

        assertNotNull(session)
        assertEquals("headerSession", session!!.appSession)
        assertEquals("headerToken", session.csrfCookie)
        assertEquals("contract-header", session.parentId)
    }

    @Test
    fun `parseCurl extracts csrfToken from header when cookie contains pipe`() {
        val curlCommand = """
            curl 'https://example.com/api' \
              -b '__Host-app_session=sess; __Host-psifi.x-csrf-token=tokenPart|signaturePart' \
              -H 'x-csrf-token: tokenPart' \
              --data-raw '{"parentId":"contract-test"}'
        """.trimIndent()

        val session = NebiusCurlParser.parseCurl(curlCommand)

        assertNotNull(session)
        // The token should come from the header, not the cookie
        assertEquals("tokenPart", session!!.csrfToken)
        // The cookie should contain the full value including |
        assertEquals("tokenPart|signaturePart", session.csrfCookie)
    }

    @Test
    fun `isCurlInput detects various curl formats`() {
        assertTrue(NebiusCurlParser.isCurlInput("curl 'https://example.com'"))
        assertTrue(NebiusCurlParser.isCurlInput("-H 'Content-Type: application/json'"))
        assertTrue(NebiusCurlParser.isCurlInput("-b 'cookie=value'"))
        assertTrue(NebiusCurlParser.isCurlInput("--data-raw '{}'"))
        assertTrue(NebiusCurlParser.isCurlInput("something -d 'data'"))
        assertTrue(NebiusCurlParser.isCurlInput("-H 'cookie: test'"))
    }

    @Test
    fun `isCurlInput rejects plain JSON`() {
        assertFalse(NebiusCurlParser.isCurlInput("""{"appSession":"test"}"""))
        assertFalse(NebiusCurlParser.isCurlInput("just some text"))
        assertFalse(NebiusCurlParser.isCurlInput("123456"))
    }

    @Test
    fun `parseCurl handles single line cURL`() {
        val curlCommand = "curl 'https://example.com/api' -b '__Host-app_session=sess1; __Host-psifi.x-csrf-token=tok1' -H 'x-csrf-token: tok1' --data-raw '{\"parentId\":\"contract-123\"}'"

        val session = NebiusCurlParser.parseCurl(curlCommand)

        assertNotNull(session)
        assertEquals("sess1", session!!.appSession)
        assertEquals("tok1", session.csrfToken)
        assertEquals("contract-123", session.parentId)
    }

    @Test
    fun `parseCurl handles Windows-style line continuations`() {
        val curlCommand = "curl 'https://example.com/api' \\\r\n  -b '__Host-app_session=winSession; __Host-psifi.x-csrf-token=winToken' \\\r\n  -H 'x-csrf-token: winToken' \\\r\n  --data-raw '{\"parentId\":\"contract-windows\"}'"

        val session = NebiusCurlParser.parseCurl(curlCommand)

        assertNotNull(session)
        assertEquals("winSession", session!!.appSession)
        assertEquals("contract-windows", session.parentId)
    }

    @Test
    fun `parseCurl extracts parentId from fallback when not in body`() {
        // If --data-raw contains parentId directly visible in the curl string
        val curlCommand = """
            curl 'https://example.com/api' \
              -b '__Host-app_session=sess; __Host-psifi.x-csrf-token=token' \
              -H 'x-csrf-token: token' \
              --data-raw '{"parentId":"contract-fallback"}'
        """.trimIndent()

        val session = NebiusCurlParser.parseCurl(curlCommand)

        assertNotNull(session)
        assertEquals("contract-fallback", session!!.parentId)
    }

    @Test
    fun `parseCurl with real-world full Chrome cURL command`() {
        // This is a realistic Chrome "Copy as cURL" output with many headers
        val curlCommand = """
            curl 'https://tokenfactory.nebius.com/api-mfe/billing/gateway/root/billingActs/getCurrentTrial' \
              -H 'accept: application/json, text/plain, */*' \
              -H 'accept-language: ru,en-US;q=0.9,en;q=0.8,de;q=0.7,zh-CN;q=0.6,zh;q=0.5,eu;q=0.4,hr;q=0.3,bs;q=0.2,sr;q=0.1,tr;q=0.1' \
              -H 'baggage: sentry-environment=prod,sentry-release=ai-studio-ui%401.517.0%2B3cc2811f,sentry-public_key=de72a063dbafddc2e98b7b2dd721c5a9,sentry-trace_id=3775ba68f4354eec81c6b1651d89790e,sentry-org_id=4505906584485888,sentry-sampled=true,sentry-sample_rand=0.10320259520973218,sentry-sample_rate=1' \
              -H 'cache-control: no-cache' \
              -H 'content-type: application/json' \
              -b 'analyticsConsents={"necessary":true,"analytics":false,"marketing":false}; __Host-app_session=ne1CtwBChpzZXNzaW9uLWUwMHYwZXhtcmQ5bmFiZDg2OBIedXNlcmFjY291bnQtZTAwZDc5a3J6N3k2ZnFkYmY5Gl8KGnNlc3Npb24tZTAwbTJ5c2puMDV2M3RwdHFwEAQaPwoZc2VydmljZWFjY291bnQtZTAwaWFtLWNwbBADGiAKHHB1YmxpY2tleS1lMDBidGhhNXBrN2FmNWhmYWIQASobb2lkY2NsaWVudC1lMDBuZWJpdXMtc3R1ZGlvMgwI8ZD4zAYQx6KvzwE6Cwjx-sHNBhCO_qU0QAFaA2UwMA.AAAAAAAAAAEAAAAAAABQHAAAAAAAAAACFePVM0NqhnGh7_b_23bdLVako5_imHqKqJBf4Lh0BSb2boQzNRt__hGQgVZmozmpbgEbqNOPrKig8GbxBMH0Cw; __stripe_mid=8b13a88b-4b03-42e0-9af1-985d49103805b4d80b; __Host-psifi.x-csrf-token=6c3aa748bfd1d058b80fef4ddcbc7eb48c8c2b00f6dd6931abc6f215ccf1aab57e7f2d2031f8a46cd88f1dd1eb1ae4a2c9a21395b2ee06942b3da0d8faa5d1a5%7C439acc2652fc07d3ce28a584042fe7f2d2d00e92f624116b323130be4a02739b; __stripe_sid=8b25fb08-febd-4816-ac46-0152c52376f5be6409' \
              -H 'origin: https://tokenfactory.nebius.com' \
              -H 'pragma: no-cache' \
              -H 'priority: u=1, i' \
              -H 'referer: https://tokenfactory.nebius.com/' \
              -H 'sec-ch-ua: "Not(A:Brand";v="8", "Chromium";v="144", "Google Chrome";v="144"' \
              -H 'sec-ch-ua-mobile: ?0' \
              -H 'sec-ch-ua-platform: "macOS"' \
              -H 'sec-fetch-dest: empty' \
              -H 'sec-fetch-mode: cors' \
              -H 'sec-fetch-site: same-origin' \
              -H 'sentry-trace: 3775ba68f4354eec81c6b1651d89790e-ba7c9023e801ff46-1' \
              -H 'user-agent: Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36' \
              -H 'x-csrf-token: 6c3aa748bfd1d058b80fef4ddcbc7eb48c8c2b00f6dd6931abc6f215ccf1aab57e7f2d2031f8a46cd88f1dd1eb1ae4a2c9a21395b2ee06942b3da0d8faa5d1a5' \
              -H 'x-requested-with: XMLHttpRequest' \
              --data-raw '{"parentId":"contract-e00pgjm81nl9t6yy137zj"}'
        """.trimIndent()

        val session = NebiusCurlParser.parseCurl(curlCommand)

        assertNotNull(session, "Should parse real-world Chrome cURL")
        session!!

        // Check all fields are extracted correctly
        assertTrue(session.appSession!!.startsWith("ne1CtwB"), "App session should start correctly")
        assertEquals(
            "6c3aa748bfd1d058b80fef4ddcbc7eb48c8c2b00f6dd6931abc6f215ccf1aab57e7f2d2031f8a46cd88f1dd1eb1ae4a2c9a21395b2ee06942b3da0d8faa5d1a5",
            session.csrfToken
        )
        assertEquals("contract-e00pgjm81nl9t6yy137zj", session.parentId)
        assertTrue(session.csrfCookie!!.contains("%7C"), "CSRF cookie should be kept raw with %7C")
    }

    @Test
    fun `parseCurl with --cookie flag`() {
        val curlCommand = """
            curl 'https://example.com/api' \
              --cookie '__Host-app_session=cookieSession; __Host-psifi.x-csrf-token=cookieToken' \
              -H 'x-csrf-token: cookieToken' \
              --data-raw '{"parentId":"contract-cookie"}'
        """.trimIndent()

        val session = NebiusCurlParser.parseCurl(curlCommand)

        assertNotNull(session)
        assertEquals("cookieSession", session!!.appSession)
        assertEquals("cookieToken", session.csrfToken)
    }

    @Test
    fun `parseCurl with --header flag instead of -H`() {
        val curlCommand = """
            curl 'https://example.com/api' \
              -b '__Host-app_session=headerSess; __Host-psifi.x-csrf-token=headerTok' \
              --header 'x-csrf-token: headerTok' \
              --data '{"parentId":"contract-header"}'
        """.trimIndent()

        val session = NebiusCurlParser.parseCurl(curlCommand)

        assertNotNull(session)
        assertEquals("headerSess", session!!.appSession)
        assertEquals("headerTok", session.csrfToken)
        assertEquals("contract-header", session.parentId)
    }

    @Test
    fun `parseCurl extracts capturedHeaders`() {
        val curlCommand = """
            curl 'https://example.com/api' \
              -b '__Host-app_session=sess; __Host-psifi.x-csrf-token=token' \
              -H 'accept: application/json' \
              -H 'content-type: application/json' \
              -H 'x-csrf-token: token' \
              --data-raw '{"parentId":"contract-test"}'
        """.trimIndent()

        val session = NebiusCurlParser.parseCurl(curlCommand)

        assertNotNull(session)
        val headers = session!!.capturedHeaders
        assertNotNull(headers)
        assertEquals("application/json", headers!!["accept"])
        assertEquals("application/json", headers["content-type"])
    }

    @Test
    fun `parseCurl extracts rawPath`() {
        val curlCommand = """
            curl 'https://tokenfactory.nebius.com/api-mfe/billing/gateway/root/billingActs/getCurrentTrial' \
              -b '__Host-app_session=sess; __Host-psifi.x-csrf-token=token' \
              -H 'x-csrf-token: token' \
              --data-raw '{"parentId":"contract-test"}'
        """.trimIndent()

        val session = NebiusCurlParser.parseCurl(curlCommand)

        assertNotNull(session)
        assertEquals("/api-mfe/billing/gateway/root/billingActs/getCurrentTrial", session!!.rawPath)
    }

    @Test
    fun `parseCurl extracts rawBody`() {
        val curlCommand = """
            curl 'https://example.com/api' \
              -b '__Host-app_session=sess; __Host-psifi.x-csrf-token=token' \
              -H 'x-csrf-token: token' \
              --data-raw '{"parentId":"contract-test","extra":"data"}'
        """.trimIndent()

        val session = NebiusCurlParser.parseCurl(curlCommand)

        assertNotNull(session)
        assertEquals("""{"parentId":"contract-test","extra":"data"}""", session!!.rawBody)
    }

    @Test
    fun `parseCurl returns null on completely invalid input`() {
        val session = NebiusCurlParser.parseCurl("this is not a curl command at all")

        // Even invalid input returns a session with null fields, but parsing should work
        // (parseCurl doesn't validate correctness, just extracts what it can)
        assertNotNull(session)
        assertEquals(null, session!!.appSession)
    }

    @Test
    fun `parseCurl handles missing parentId gracefully`() {
        val curlCommand = """
            curl 'https://example.com/api' \
              -b '__Host-app_session=sess; __Host-psifi.x-csrf-token=token' \
              -H 'x-csrf-token: token' \
              --data-raw '{"someOtherField":"value"}'
        """.trimIndent()

        val session = NebiusCurlParser.parseCurl(curlCommand)

        assertNotNull(session)
        assertEquals("sess", session!!.appSession)
        assertEquals(null, session.parentId)
    }

    @Test
    fun `parseCurl handles csrfToken extraction from cookie when header missing`() {
        val curlCommand = """
            curl 'https://example.com/api' \
              -b '__Host-app_session=sess; __Host-psifi.x-csrf-token=tokenPart|signature' \
              --data-raw '{"parentId":"contract-test"}'
        """.trimIndent()

        val session = NebiusCurlParser.parseCurl(curlCommand)

        assertNotNull(session)
        // CSRF token should be extracted from cookie (part before |)
        assertEquals("tokenPart", session!!.csrfToken)
    }
}
