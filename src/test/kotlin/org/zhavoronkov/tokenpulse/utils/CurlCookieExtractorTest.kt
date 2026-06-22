package org.zhavoronkov.tokenpulse.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CurlCookieExtractorTest {

    @Test
    fun `extractCookieString finds cookies with -b flag single quotes`() {
        val curl = "curl 'https://example.com' -b 'cookie1=val1; cookie2=val2'"
        val result = CurlCookieExtractor.extractCookieString(curl)
        assertNotNull(result)
        assertEquals("cookie1=val1; cookie2=val2", result)
    }

    @Test
    fun `extractCookieString finds cookies with -b flag double quotes`() {
        val curl = """curl 'https://example.com' -b "cookie1=val1; cookie2=val2""""
        val result = CurlCookieExtractor.extractCookieString(curl)
        assertNotNull(result)
        assertEquals("cookie1=val1; cookie2=val2", result)
    }

    @Test
    fun `extractCookieString finds cookies with --cookie flag`() {
        val curl = "curl 'https://example.com' --cookie 'cookie1=val1; cookie2=val2'"
        val result = CurlCookieExtractor.extractCookieString(curl)
        assertNotNull(result)
        assertEquals("cookie1=val1; cookie2=val2", result)
    }

    @Test
    fun `extractCookieString finds cookies with -H Cookie header`() {
        val curl = "curl 'https://example.com' -H 'Cookie: cookie1=val1; cookie2=val2'"
        val result = CurlCookieExtractor.extractCookieString(curl)
        assertNotNull(result)
        assertEquals("cookie1=val1; cookie2=val2", result)
    }

    @Test
    fun `extractCookieString returns null when no cookies`() {
        val curl = "curl 'https://example.com' -H 'Accept: */*'"
        val result = CurlCookieExtractor.extractCookieString(curl)
        assertNull(result)
    }

    @Test
    fun `parseCookieString parses simple cookies`() {
        val cookieString = "cookie1=val1; cookie2=val2"
        val result = CurlCookieExtractor.parseCookieString(cookieString)
        assertEquals(2, result.size)
        assertEquals("val1", result["cookie1"])
        assertEquals("val2", result["cookie2"])
    }

    @Test
    fun `parseCookieString handles quoted values`() {
        val cookieString = """cookie1="val1"; cookie2='val2'"""
        val result = CurlCookieExtractor.parseCookieString(cookieString)
        assertEquals(2, result.size)
        assertEquals("val1", result["cookie1"])
        assertEquals("val2", result["cookie2"])
    }

    @Test
    fun `parseCookieString handles empty string`() {
        val result = CurlCookieExtractor.parseCookieString("")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseCookieString handles cookies with equals in value`() {
        val cookieString = "cookie1=val=1; cookie2=val2"
        val result = CurlCookieExtractor.parseCookieString(cookieString)
        assertEquals(2, result.size)
        assertEquals("val=1", result["cookie1"])
    }

    @Test
    fun `extractQuotedValue finds single quoted value`() {
        val text = "curl -b 'my-cookie-value' https://example.com"
        val result = CurlCookieExtractor.extractQuotedValue(text, 'b')
        assertEquals("my-cookie-value", result)
    }

    @Test
    fun `extractQuotedValue finds double quoted value`() {
        val text = """curl -b "my-cookie-value" https://example.com"""
        val result = CurlCookieExtractor.extractQuotedValue(text, 'b')
        assertEquals("my-cookie-value", result)
    }

    @Test
    fun `extractQuotedValue returns null when flag not found`() {
        val text = "curl 'https://example.com'"
        val result = CurlCookieExtractor.extractQuotedValue(text, 'b')
        assertNull(result)
    }
}
