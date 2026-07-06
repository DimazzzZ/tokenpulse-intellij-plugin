package org.zhavoronkov.tokenpulse.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.zhavoronkov.tokenpulse.provider.cline.ClineProviderClient

class ClinePassUsageRendererTest {

    @Test
    fun `hasUsage returns false for null metadata`() {
        assertFalse(ClinePassUsageRenderer.hasUsage(null))
    }

    @Test
    fun `hasUsage returns false for empty metadata`() {
        assertFalse(ClinePassUsageRenderer.hasUsage(emptyMap()))
    }

    @Test
    fun `hasUsage returns false for metadata with only reset keys`() {
        val metadata = mapOf(
            ClineProviderClient.METADATA_FIVE_HOUR_RESETS_AT to "2026-07-06T16:12:41Z"
        )
        assertFalse(ClinePassUsageRenderer.hasUsage(metadata))
    }

    @Test
    fun `hasUsage returns false for metadata with unknown keys only`() {
        val metadata = mapOf("someOtherKey" to "10")
        assertFalse(ClinePassUsageRenderer.hasUsage(metadata))
    }

    @Test
    fun `hasUsage returns false when used value is blank`() {
        val metadata = mapOf(ClineProviderClient.METADATA_FIVE_HOUR_USED to "")
        assertFalse(ClinePassUsageRenderer.hasUsage(metadata))
    }

    @Test
    fun `hasUsage returns true for five-hour used value`() {
        val metadata = mapOf(ClineProviderClient.METADATA_FIVE_HOUR_USED to "50")
        assertTrue(ClinePassUsageRenderer.hasUsage(metadata))
    }

    @Test
    fun `hasUsage returns true for weekly used value`() {
        val metadata = mapOf(ClineProviderClient.METADATA_WEEKLY_USED to "30")
        assertTrue(ClinePassUsageRenderer.hasUsage(metadata))
    }

    @Test
    fun `hasUsage returns true for monthly used value`() {
        val metadata = mapOf(ClineProviderClient.METADATA_MONTHLY_USED to "15")
        assertTrue(ClinePassUsageRenderer.hasUsage(metadata))
    }

    @Test
    fun `buildRows returns empty string for null metadata`() {
        assertEquals("", ClinePassUsageRenderer.buildRows(null))
    }

    @Test
    fun `buildRows returns empty string when no ClinePass usage available`() {
        assertEquals("", ClinePassUsageRenderer.buildRows(emptyMap()))
    }

    @Test
    fun `buildRows renders a ClinePass section header when usage is available`() {
        val metadata = mapOf(ClineProviderClient.METADATA_FIVE_HOUR_USED to "10")
        val html = ClinePassUsageRenderer.buildRows(metadata)

        assertTrue(
            html.contains("ClinePass"),
            "Expected ClinePass section header in output, got: $html"
        )
        assertTrue(
            html.contains("<b>ClinePass</b>"),
            "Expected bold ClinePass header, got: $html"
        )
    }

    @Test
    fun `buildRows does not render a ClinePass section header when usage is absent`() {
        assertFalse(ClinePassUsageRenderer.buildRows(emptyMap()).contains("ClinePass"))
        assertFalse(ClinePassUsageRenderer.buildRows(null).contains("ClinePass"))
    }

    @Test
    fun `buildRows renders 5-hour and weekly when only those keys exist`() {
        val metadata = mapOf(
            ClineProviderClient.METADATA_FIVE_HOUR_USED to "50",
            ClineProviderClient.METADATA_WEEKLY_USED to "20"
        )
        val html = ClinePassUsageRenderer.buildRows(metadata)

        assertTrue(html.contains("5-hour"), "Expected 5-hour row in output, got: $html")
        assertTrue(html.contains("Weekly"), "Expected Weekly row in output, got: $html")
        assertFalse(html.contains("Monthly"), "Did not expect Monthly row, got: $html")
    }

    @Test
    fun `buildRows includes monthly row when monthly metadata exists`() {
        val metadata = mapOf(
            ClineProviderClient.METADATA_FIVE_HOUR_USED to "10",
            ClineProviderClient.METADATA_WEEKLY_USED to "20",
            ClineProviderClient.METADATA_MONTHLY_USED to "15"
        )
        val html = ClinePassUsageRenderer.buildRows(metadata)

        assertTrue(html.contains("5-hour"), html)
        assertTrue(html.contains("Weekly"), html)
        assertTrue(html.contains("Monthly"), html)
    }

    @Test
    fun `buildRows embeds reset timestamp when present and omits it when absent`() {
        val resetTimestamp = "2026-07-06T16:12:41Z"
        val withReset = mapOf(
            ClineProviderClient.METADATA_FIVE_HOUR_USED to "50",
            ClineProviderClient.METADATA_FIVE_HOUR_RESETS_AT to resetTimestamp
        )
        val withoutReset = mapOf(
            ClineProviderClient.METADATA_FIVE_HOUR_USED to "50"
        )
        val withResetHtml = ClinePassUsageRenderer.buildRows(withReset)
        val withoutResetHtml = ClinePassUsageRenderer.buildRows(withoutReset)

        // When the reset timestamp is present it should be embedded in the
        // rendered usage row (the UI branch's buildUsageSection shows it
        // inline as a small gray span next to the percentage).
        assertTrue(
            withResetHtml.contains(resetTimestamp),
            "Expected reset timestamp to be present in rendered HTML, got: $withResetHtml"
        )
        assertFalse(
            withoutResetHtml.contains(resetTimestamp),
            "Reset timestamp should not appear when not provided, got: $withoutResetHtml"
        )
    }

    @Test
    fun `buildRows omits reset text when resets key is blank`() {
        val withBlankReset = mapOf(
            ClineProviderClient.METADATA_FIVE_HOUR_USED to "50",
            ClineProviderClient.METADATA_FIVE_HOUR_RESETS_AT to "   "
        )
        val html = ClinePassUsageRenderer.buildRows(withBlankReset)
        assertFalse(
            html.contains("Resets:") || html.contains("2026") || html.contains("  "),
            "Blank reset timestamp must not appear in rendered HTML, got: $html"
        )
    }

    @Test
    fun `buildRows skips used key when value is not a valid number`() {
        val metadata = mapOf(
            ClineProviderClient.METADATA_FIVE_HOUR_USED to "not-a-number",
            ClineProviderClient.METADATA_WEEKLY_USED to "20"
        )
        val html = ClinePassUsageRenderer.buildRows(metadata)

        assertFalse(html.contains("5-hour"), "Invalid five-hour value must be skipped, got: $html")
        assertTrue(html.contains("Weekly"), html)
    }

    @Test
    fun `buildRows keeps a single ClinePass section header even when multiple limits present`() {
        val metadata = mapOf(
            ClineProviderClient.METADATA_FIVE_HOUR_USED to "10",
            ClineProviderClient.METADATA_WEEKLY_USED to "20",
            ClineProviderClient.METADATA_MONTHLY_USED to "15"
        )
        val html = ClinePassUsageRenderer.buildRows(metadata)
        val headerCount = Regex("""<b>ClinePass</b>""").findAll(html).count()
        assertEquals(1, headerCount, "Expected exactly one ClinePass section header, got: $html")
    }
}
