package org.zhavoronkov.tokenpulse.ui

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for the pure cookie-map -> session-JSON mapping used by the JCEF login
 * flow. Kept off the JCEF path so it can run headless without an IDE fixture.
 */
class XiaomiCookieHarvestTest {

    @Test
    fun `buildSessionCookies extracts all six fields from platform+passport maps`() {
        val platform = mapOf(
            "api-platform_serviceToken" to "st-abc",
            "userId" to "1555143730",
            "api-platform_slh" to "slh-xyz",
            "api-platform_ph" to "ph-xyz"
        )
        val passport = mapOf(
            "passToken" to "V1:secretPass",
            "userId" to "1555143730",
            "cUserId" to "cuser-abc"
        )

        val cookies = XiaomiCookieHarvest.buildSessionCookies(platform, passport)

        assertNotNull(cookies)
        assertEquals("st-abc", cookies!!.serviceToken)
        assertEquals("1555143730", cookies.userId)
        assertEquals("slh-xyz", cookies.slh)
        assertEquals("ph-xyz", cookies.ph)
        assertEquals("V1:secretPass", cookies.passToken)
        assertEquals("cuser-abc", cookies.cUserId)
        assertTrue(XiaomiCookieHarvest.hasRefreshableSession(cookies))
    }

    @Test
    fun `buildSessionCookies returns null when serviceToken is missing`() {
        val platform = mapOf("userId" to "1555143730")
        assertNull(XiaomiCookieHarvest.buildSessionCookies(platform, emptyMap()))
    }

    @Test
    fun `buildSessionCookies returns null when userId is missing on both hosts`() {
        val platform = mapOf("api-platform_serviceToken" to "st-abc")
        assertNull(XiaomiCookieHarvest.buildSessionCookies(platform, emptyMap()))
    }

    @Test
    fun `buildSessionCookies falls back to passport userId when platform userId is absent`() {
        val platform = mapOf("api-platform_serviceToken" to "st-abc")
        val passport = mapOf(
            "userId" to "1555143730",
            "passToken" to "V1:x"
        )
        val cookies = XiaomiCookieHarvest.buildSessionCookies(platform, passport)
        assertNotNull(cookies)
        assertEquals("1555143730", cookies!!.userId)
    }

    @Test
    fun `buildSessionCookies without passport still yields a usable session (no auto-refresh)`() {
        val platform = mapOf(
            "api-platform_serviceToken" to "st-abc",
            "userId" to "1555143730"
        )
        val cookies = XiaomiCookieHarvest.buildSessionCookies(platform, emptyMap())
        assertNotNull(cookies)
        assertNull(cookies!!.passToken)
        assertFalse(XiaomiCookieHarvest.hasRefreshableSession(cookies))
    }

    @Test
    fun `buildSessionCookies treats blank cookie values as absent`() {
        val platform = mapOf(
            "api-platform_serviceToken" to "",
            "userId" to "1555143730"
        )
        assertNull(XiaomiCookieHarvest.buildSessionCookies(platform, emptyMap()))
    }

    @Test
    fun `buildSessionJson produces JSON with the expected field names`() {
        val platform = mapOf(
            "api-platform_serviceToken" to "st-abc",
            "userId" to "1555143730"
        )
        val json = XiaomiCookieHarvest.buildSessionJson(platform, emptyMap())
        assertNotNull(json)
        val obj = JsonParser.parseString(json).asJsonObject
        assertEquals("st-abc", obj["serviceToken"].asString)
        assertEquals("1555143730", obj["userId"].asString)
        // Optional fields are omitted rather than serialized as null.
        assertFalse(obj.has("slh"))
        assertFalse(obj.has("passToken"))
    }

    @Test
    fun `buildSessionJson JSON round-trips into XiaomiSessionCookies`() {
        val platform = mapOf(
            "api-platform_serviceToken" to "st-abc",
            "userId" to "1555143730",
            "api-platform_slh" to "slh-xyz",
            "api-platform_ph" to "ph-xyz"
        )
        val passport = mapOf(
            "passToken" to "V1:secretPass",
            "cUserId" to "cuser-abc"
        )
        val json = XiaomiCookieHarvest.buildSessionJson(platform, passport)!!
        val parsed = com.google.gson.Gson().fromJson(json, XiaomiConnectDialog.XiaomiSessionCookies::class.java)
        assertEquals("st-abc", parsed.serviceToken)
        assertEquals("V1:secretPass", parsed.passToken)
        assertEquals("cuser-abc", parsed.cUserId)
    }
}
