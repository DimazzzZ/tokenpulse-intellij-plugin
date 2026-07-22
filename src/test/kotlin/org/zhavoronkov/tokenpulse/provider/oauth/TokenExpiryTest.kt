package org.zhavoronkov.tokenpulse.provider.oauth

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TokenExpiryTest {

    @Test
    fun `null expiry is treated as usable`() {
        assertFalse(isTokenExpired(expiry = null, now = 1_000L, skew = 60L))
    }

    @Test
    fun `token is expired exactly at the skew boundary`() {
        // now == expiry - skew  =>  expired.
        assertTrue(isTokenExpired(expiry = 1_000L, now = 940L, skew = 60L))
    }

    @Test
    fun `token is valid one unit before the skew boundary`() {
        assertFalse(isTokenExpired(expiry = 1_000L, now = 939L, skew = 60L))
    }

    @Test
    fun `token is expired past its real expiry`() {
        assertTrue(isTokenExpired(expiry = 1_000L, now = 2_000L, skew = 60L))
    }

    @Test
    fun `unit-agnostic - works with milliseconds`() {
        val skewMs = 60_000L
        val expiresAt = 1_000_000L
        assertFalse(isTokenExpired(expiresAt, expiresAt - skewMs - 1, skewMs))
        assertTrue(isTokenExpired(expiresAt, expiresAt - skewMs, skewMs))
    }
}
