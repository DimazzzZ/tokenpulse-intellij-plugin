package org.zhavoronkov.tokenpulse.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Tests for [Credits] data class.
 */
class CreditsTest {

    @Test
    fun `Credits has null defaults`() {
        val credits = Credits()

        assertNull(credits.total)
        assertNull(credits.used)
        assertNull(credits.remaining)
    }

    @Test
    fun `Credits holds BigDecimal values`() {
        val credits = Credits(
            total = BigDecimal("100.00"),
            used = BigDecimal("25.50"),
            remaining = BigDecimal("74.50")
        )

        assertEquals(BigDecimal("100.00"), credits.total)
        assertEquals(BigDecimal("25.50"), credits.used)
        assertEquals(BigDecimal("74.50"), credits.remaining)
    }

    @Test
    fun `Credits can have only remaining`() {
        val credits = Credits(remaining = BigDecimal("50.00"))

        assertNull(credits.total)
        assertNull(credits.used)
        assertEquals(BigDecimal("50.00"), credits.remaining)
    }

    @Test
    fun `Credits equals compares values`() {
        val credits1 = Credits(remaining = BigDecimal("10.00"))
        val credits2 = Credits(remaining = BigDecimal("10.00"))
        val credits3 = Credits(remaining = BigDecimal("20.00"))

        assertEquals(credits1, credits2)
        assertNotEquals(credits1, credits3)
    }
}

/**
 * Tests for [Tokens] data class.
 */
class TokensTest {

    @Test
    fun `Tokens has null defaults`() {
        val tokens = Tokens()

        assertNull(tokens.total)
        assertNull(tokens.used)
        assertNull(tokens.remaining)
    }

    @Test
    fun `Tokens holds Long values`() {
        val tokens = Tokens(
            total = 1_000_000L,
            used = 250_000L,
            remaining = 750_000L
        )

        assertEquals(1_000_000L, tokens.total)
        assertEquals(250_000L, tokens.used)
        assertEquals(750_000L, tokens.remaining)
    }

    @Test
    fun `Tokens can have only remaining`() {
        val tokens = Tokens(remaining = 500_000L)

        assertNull(tokens.total)
        assertNull(tokens.used)
        assertEquals(500_000L, tokens.remaining)
    }

    @Test
    fun `Tokens equals compares values`() {
        val tokens1 = Tokens(remaining = 100L)
        val tokens2 = Tokens(remaining = 100L)
        val tokens3 = Tokens(remaining = 200L)

        assertEquals(tokens1, tokens2)
        assertNotEquals(tokens1, tokens3)
    }
}

/**
 * Tests for [Balance] data class.
 */
class BalanceTest {

    @Test
    fun `Balance has null defaults`() {
        val balance = Balance()

        assertNull(balance.credits)
        assertNull(balance.tokens)
    }

    @Test
    fun `Balance can have only credits`() {
        val balance = Balance(
            credits = Credits(remaining = BigDecimal("100.00"))
        )

        assertEquals(BigDecimal("100.00"), balance.credits?.remaining)
        assertNull(balance.tokens)
    }

    @Test
    fun `Balance can have only tokens`() {
        val balance = Balance(
            tokens = Tokens(remaining = 500_000L)
        )

        assertNull(balance.credits)
        assertEquals(500_000L, balance.tokens?.remaining)
    }

    @Test
    fun `Balance can have both credits and tokens`() {
        val balance = Balance(
            credits = Credits(remaining = BigDecimal("50.00")),
            tokens = Tokens(remaining = 250_000L)
        )

        assertEquals(BigDecimal("50.00"), balance.credits?.remaining)
        assertEquals(250_000L, balance.tokens?.remaining)
    }

    @Test
    fun `Balance equals compares nested values`() {
        val balance1 = Balance(credits = Credits(remaining = BigDecimal("10.00")))
        val balance2 = Balance(credits = Credits(remaining = BigDecimal("10.00")))
        val balance3 = Balance(credits = Credits(remaining = BigDecimal("20.00")))

        assertEquals(balance1, balance2)
        assertNotEquals(balance1, balance3)
    }

    @Test
    fun `Balance copy works correctly`() {
        val original = Balance(credits = Credits(remaining = BigDecimal("10.00")))
        val copy = original.copy(tokens = Tokens(remaining = 100L))

        assertEquals(BigDecimal("10.00"), copy.credits?.remaining)
        assertEquals(100L, copy.tokens?.remaining)
    }
}
