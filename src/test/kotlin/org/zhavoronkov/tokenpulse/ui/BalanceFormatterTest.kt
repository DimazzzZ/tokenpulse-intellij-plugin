package org.zhavoronkov.tokenpulse.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.zhavoronkov.tokenpulse.model.Balance
import org.zhavoronkov.tokenpulse.model.Credits
import org.zhavoronkov.tokenpulse.model.Tokens
import java.math.BigDecimal

class BalanceFormatterTest {

    @Test
    fun `format returns No data when balance is empty`() {
        val balance = Balance()
        assertEquals("No data", BalanceFormatter.format(balance))
    }

    @Test
    fun `format returns credits only when no tokens`() {
        val balance = Balance(credits = Credits(remaining = BigDecimal("10.50")))
        assertEquals("\$10.50", BalanceFormatter.format(balance))
    }

    @Test
    fun `format returns tokens only when no credits`() {
        val balance = Balance(tokens = Tokens(used = 1500L))
        assertEquals("1,500 used", BalanceFormatter.format(balance))
    }

    @Test
    fun `format returns both credits and tokens`() {
        val balance = Balance(
            credits = Credits(remaining = BigDecimal("25.00")),
            tokens = Tokens(used = 5000L)
        )
        assertEquals("\$25.00 (5,000 used)", BalanceFormatter.format(balance))
    }

    @Test
    fun `format credits uses used when remaining is null`() {
        val balance = Balance(credits = Credits(used = BigDecimal("5.25")))
        assertEquals("\$5.25", BalanceFormatter.format(balance))
    }

    @Test
    fun `format credits uses total as fallback`() {
        val balance = Balance(credits = Credits(total = BigDecimal("100.00")))
        assertEquals("\$100.00", BalanceFormatter.format(balance))
    }

    @Test
    fun `format tokens uses remaining when available`() {
        val balance = Balance(tokens = Tokens(remaining = 50000L))
        assertEquals("50,000", BalanceFormatter.format(balance))
    }

    @Test
    fun `format tokens uses total as fallback`() {
        val balance = Balance(tokens = Tokens(total = 100000L))
        assertEquals("100,000", BalanceFormatter.format(balance))
    }

    @Test
    fun `format handles large numbers with formatting`() {
        val balance = Balance(tokens = Tokens(used = 1_234_567L))
        assertEquals("1,234,567 used", BalanceFormatter.format(balance))
    }

    @Test
    fun `format rounds credits to 2 decimal places`() {
        val balance = Balance(credits = Credits(remaining = BigDecimal("10.555")))
        assertEquals("\$10.56", BalanceFormatter.format(balance))
    }

    @Test
    fun `format handles zero values`() {
        val balance = Balance(
            credits = Credits(remaining = BigDecimal.ZERO),
            tokens = Tokens(used = 0L)
        )
        assertEquals("\$0.00 (0 used)", BalanceFormatter.format(balance))
    }

    @Test
    fun `formatDetailed returns dashes when nothing to show`() {
        val balance = Balance()
        assertEquals("--", BalanceFormatter.formatDetailed(balance, showCredits = true, showTokens = true))
    }

    @Test
    fun `formatDetailed shows only credits when showTokens is false`() {
        val balance = Balance(
            credits = Credits(remaining = BigDecimal("50.00")),
            tokens = Tokens(used = 1000L)
        )
        assertEquals("\$50.00", BalanceFormatter.formatDetailed(balance, showCredits = true, showTokens = false))
    }

    @Test
    fun `formatDetailed shows only tokens when showCredits is false`() {
        val balance = Balance(
            credits = Credits(remaining = BigDecimal("50.00")),
            tokens = Tokens(used = 1000L)
        )
        assertEquals("1,000 used", BalanceFormatter.formatDetailed(balance, showCredits = false, showTokens = true))
    }

    @Test
    fun `formatDetailed shows both with plus separator`() {
        val balance = Balance(
            credits = Credits(remaining = BigDecimal("50.00")),
            tokens = Tokens(used = 1000L)
        )
        assertEquals(
            "\$50.00 + 1,000 used",
            BalanceFormatter.formatDetailed(balance, showCredits = true, showTokens = true)
        )
    }

    @Test
    fun `formatDetailed shows tokens with total when available`() {
        val balance = Balance(
            tokens = Tokens(used = 5000L, total = 10000L)
        )
        assertEquals(
            "5,000 used/ 10,000",
            BalanceFormatter.formatDetailed(balance, showCredits = false, showTokens = true)
        )
    }

    @Test
    fun `format handles credits with all null fields`() {
        val balance = Balance(credits = Credits())
        assertEquals("--", BalanceFormatter.format(balance))
    }

    @Test
    fun `format handles tokens with all null fields`() {
        val balance = Balance(tokens = Tokens())
        assertEquals("--", BalanceFormatter.format(balance))
    }

    @Test
    fun `formatDetailed shows tokens remaining when available`() {
        val balance = Balance(
            tokens = Tokens(remaining = 7500L)
        )
        assertEquals("7,500", BalanceFormatter.formatDetailed(balance, showCredits = false, showTokens = true))
    }

    @Test
    fun `formatDetailed shows tokens total when only total available`() {
        val balance = Balance(
            tokens = Tokens(total = 20000L)
        )
        assertEquals("20,000", BalanceFormatter.formatDetailed(balance, showCredits = false, showTokens = true))
    }

    @Test
    fun `formatDetailed shows credits used when remaining is null`() {
        val balance = Balance(
            credits = Credits(used = BigDecimal("15.75"))
        )
        assertEquals("\$15.75", BalanceFormatter.formatDetailed(balance, showCredits = true, showTokens = false))
    }

    @Test
    fun `formatDetailed shows credits total when only total available`() {
        val balance = Balance(
            credits = Credits(total = BigDecimal("200.00"))
        )
        assertEquals("\$200.00", BalanceFormatter.formatDetailed(balance, showCredits = true, showTokens = false))
    }

    @Test
    fun `formatDetailed returns dashes when showCredits is false and no tokens`() {
        val balance = Balance(credits = Credits(remaining = BigDecimal("50.00")))
        assertEquals("--", BalanceFormatter.formatDetailed(balance, showCredits = false, showTokens = true))
    }

    @Test
    fun `formatDetailed returns dashes when showTokens is false and no credits`() {
        val balance = Balance(tokens = Tokens(remaining = 1000L))
        assertEquals("--", BalanceFormatter.formatDetailed(balance, showCredits = true, showTokens = false))
    }

    @Test
    fun `formatDetailed handles both flags false`() {
        val balance = Balance(
            credits = Credits(remaining = BigDecimal("50.00")),
            tokens = Tokens(used = 1000L)
        )
        assertEquals("--", BalanceFormatter.formatDetailed(balance, showCredits = false, showTokens = false))
    }
}
