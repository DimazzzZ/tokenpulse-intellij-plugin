package org.zhavoronkov.tokenpulse.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class XiaomiConnectDialogTest {

    @Test
    fun `extractCookiesFromCurl parses single-quoted cURL with double-quoted values`() {
        val cUrl = """
            curl 'https://platform.xiaomimimo.com/api/v1/tokenPlan/usage' \
              -b 'api-platform_serviceToken="myToken123"; userId=1555143730; api-platform_slh="mySlh"; api-platform_ph="myPh"' \
              -H 'accept: */*'
        """.trimIndent()

        val result = XiaomiConnectDialog.extractCookiesFromCurl(cUrl)

        assertNotNull(result)
        assertEquals("myToken123", result!!.serviceToken)
        assertEquals("1555143730", result.userId)
        assertEquals("mySlh", result.slh)
        assertEquals("myPh", result.ph)
    }

    @Test
    fun `extractCookiesFromCurl parses double-quoted cURL with single-quoted values`() {
        val cUrl = """curl "https://platform.xiaomimimo.com/api/v1/balance" -b "api-platform_serviceToken='myToken'; userId=12345""""

        val result = XiaomiConnectDialog.extractCookiesFromCurl(cUrl)

        assertNotNull(result)
        assertEquals("myToken", result!!.serviceToken)
        assertEquals("12345", result.userId)
    }

    @Test
    fun `extractCookiesFromCurl returns null when no -b flag present`() {
        val cUrl = "curl 'https://platform.xiaomimimo.com/api/v1/balance' -H 'accept: */*'"

        val result = XiaomiConnectDialog.extractCookiesFromCurl(cUrl)

        assertNull(result)
    }

    @Test
    fun `extractCookiesFromCurl handles missing serviceToken`() {
        val cUrl = """curl 'https://platform.xiaomimimo.com/api/v1/balance' -b 'userId=12345; api-platform_slh="slh"'"""

        val result = XiaomiConnectDialog.extractCookiesFromCurl(cUrl)

        assertNotNull(result)
        assertNull(result!!.serviceToken)
        assertEquals("12345", result.userId)
    }

    @Test
    fun `extractCookiesFromCurl handles missing userId`() {
        val cUrl = """curl 'https://platform.xiaomimimo.com/api/v1/balance' -b 'api-platform_serviceToken="token"'"""

        val result = XiaomiConnectDialog.extractCookiesFromCurl(cUrl)

        assertNotNull(result)
        assertEquals("token", result!!.serviceToken)
        assertNull(result.userId)
    }

    @Test
    fun `extractCookiesFromCurl handles extra whitespace`() {
        val cUrl = """
            curl 'https://platform.xiaomimimo.com/api/v1/balance' \
              -b   'api-platform_serviceToken="token"  ;  userId=999  '
        """.trimIndent()

        val result = XiaomiConnectDialog.extractCookiesFromCurl(cUrl)

        assertNotNull(result)
        assertEquals("token", result!!.serviceToken)
        assertEquals("999", result.userId)
    }

    @Test
    fun `extractCookiesFromCurl handles real Chrome DevTools cURL`() {
        val cUrl = """curl 'https://platform.xiaomimimo.com/api/v1/tokenPlan/usage' \
  -H 'accept: */*' \
  -H 'content-type: application/json' \
  -b 'api-platform_serviceToken="yVHEDQlNiFT1WavLtPFne/MFS408c/z2JE/rw7Us3OfIsbXJMJxXcISEQInsw/ayh53Brz7Qqt5W7DhNgKjPH3SiHfpVzonZ++/KEnZJqswj0x7UtTfzlHarGLFxGat/0ZsZl3mdJPfJ19s8yX4UbxbatkMjaI61bSh3y02Kv42plYQN7tYZepcttPxJUFFK2NW4AZC+IVIrnwGvpQPKuUqthjLigUog0Tvc8eiYjsvuD3Mm9zsx/BL4qf5z/scO4JGXHcCE2jEOLfQxktcS2ZiyvY8mm+LjeCXRKij9MW6tukuhwFCap4MORLGRbG+gsg0zDOQgxVu4Km2w8n/fRdeazKNfqRgFRoZTgLUzasI="; userId=1555143730; api-platform_slh="9SlJ+Jk7FNeoMLHIK8RzdsZgdbM="; api-platform_ph="a2h4khFwa/gK6L0/EEzdPg=="' \
  -H 'x-timezone: Europe/Belgrade'"""

        val result = XiaomiConnectDialog.extractCookiesFromCurl(cUrl)

        assertNotNull(result)
        assertEquals(
            "yVHEDQlNiFT1WavLtPFne/MFS408c/z2JE/rw7Us3OfIsbXJMJxXcISEQInsw/ayh53Brz7Qqt5W7DhNgKjPH3SiHfpVzonZ++/KEnZJqswj0x7UtTfzlHarGLFxGat/0ZsZl3mdJPfJ19s8yX4UbxbatkMjaI61bSh3y02Kv42plYQN7tYZepcttPxJUFFK2NW4AZC+IVIrnwGvpQPKuUqthjLigUog0Tvc8eiYjsvuD3Mm9zsx/BL4qf5z/scO4JGXHcCE2jEOLfQxktcS2ZiyvY8mm+LjeCXRKij9MW6tukuhwFCap4MORLGRbG+gsg0zDOQgxVu4Km2w8n/fRdeazKNfqRgFRoZTgLUzasI=",
            result!!.serviceToken
        )
        assertEquals("1555143730", result.userId)
        assertEquals("9SlJ+Jk7FNeoMLHIK8RzdsZgdbM=", result.slh)
        assertEquals("a2h4khFwa/gK6L0/EEzdPg==", result.ph)
    }
}
