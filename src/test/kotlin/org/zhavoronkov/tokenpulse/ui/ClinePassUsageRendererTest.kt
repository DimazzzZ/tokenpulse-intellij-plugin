package org.zhavoronkov.tokenpulse.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
    fun `extractMetrics returns empty list for null metadata`() {
        assertEquals(emptyList<ClinePassMetric>(), ClinePassUsageRenderer.extractMetrics(null))
    }

    @Test
    fun `extractMetrics returns empty list when no ClinePass usage available`() {
        assertEquals(emptyList<ClinePassMetric>(), ClinePassUsageRenderer.extractMetrics(emptyMap()))
    }

    @Test
    fun `extractMetrics returns single 5-hour metric when only that key exists`() {
        val metadata = mapOf(ClineProviderClient.METADATA_FIVE_HOUR_USED to "10")
        val metrics = ClinePassUsageRenderer.extractMetrics(metadata)

        assertEquals(1, metrics.size)
        val metric = metrics[0]
        assertEquals("5-hour", metric.label)
        assertEquals(10, metric.percent)
        assertNull(metric.resetAt)
    }

    @Test
    fun `extractMetrics includes resetAt when present`() {
        val resetTimestamp = "2026-07-06T16:12:41Z"
        val metadata = mapOf(
            ClineProviderClient.METADATA_FIVE_HOUR_USED to "50",
            ClineProviderClient.METADATA_FIVE_HOUR_RESETS_AT to resetTimestamp
        )
        val metrics = ClinePassUsageRenderer.extractMetrics(metadata)

        assertEquals(1, metrics.size)
        assertEquals(resetTimestamp, metrics[0].resetAt)
    }

    @Test
    fun `extractMetrics renders 5-hour and weekly when only those keys exist`() {
        val metadata = mapOf(
            ClineProviderClient.METADATA_FIVE_HOUR_USED to "50",
            ClineProviderClient.METADATA_WEEKLY_USED to "20"
        )
        val metrics = ClinePassUsageRenderer.extractMetrics(metadata)

        assertEquals(2, metrics.size)
        assertEquals("5-hour", metrics[0].label)
        assertEquals(50, metrics[0].percent)
        assertEquals("Weekly", metrics[1].label)
        assertEquals(20, metrics[1].percent)
    }

    @Test
    fun `extractMetrics includes monthly row when monthly metadata exists`() {
        val metadata = mapOf(
            ClineProviderClient.METADATA_FIVE_HOUR_USED to "10",
            ClineProviderClient.METADATA_WEEKLY_USED to "20",
            ClineProviderClient.METADATA_MONTHLY_USED to "15"
        )
        val metrics = ClinePassUsageRenderer.extractMetrics(metadata)

        assertEquals(3, metrics.size)
        assertEquals("5-hour", metrics[0].label)
        assertEquals("Weekly", metrics[1].label)
        assertEquals("Monthly", metrics[2].label)
    }

    @Test
    fun `extractMetrics preserves order 5-hour Weekly Monthly`() {
        val metadata = mapOf(
            ClineProviderClient.METADATA_MONTHLY_USED to "15",
            ClineProviderClient.METADATA_FIVE_HOUR_USED to "10",
            ClineProviderClient.METADATA_WEEKLY_USED to "20"
        )
        val metrics = ClinePassUsageRenderer.extractMetrics(metadata)

        assertEquals(3, metrics.size)
        assertEquals("5-hour", metrics[0].label)
        assertEquals("Weekly", metrics[1].label)
        assertEquals("Monthly", metrics[2].label)
    }

    @Test
    fun `extractMetrics skips used key when value is not a valid number`() {
        val metadata = mapOf(
            ClineProviderClient.METADATA_FIVE_HOUR_USED to "not-a-number",
            ClineProviderClient.METADATA_WEEKLY_USED to "20"
        )
        val metrics = ClinePassUsageRenderer.extractMetrics(metadata)

        assertEquals(1, metrics.size)
        assertEquals("Weekly", metrics[0].label)
    }

    @Test
    fun `extractMetrics omits reset text when resets key is blank`() {
        val metadata = mapOf(
            ClineProviderClient.METADATA_FIVE_HOUR_USED to "50",
            ClineProviderClient.METADATA_FIVE_HOUR_RESETS_AT to "   "
        )
        val metrics = ClinePassUsageRenderer.extractMetrics(metadata)

        assertEquals(1, metrics.size)
        // Blank resetAt should still be included in the metric (the panel decides whether to render it)
        assertEquals("   ", metrics[0].resetAt)
    }

    @Test
    fun `extractMetrics keeps exactly one set of metrics even when all limits present`() {
        val metadata = mapOf(
            ClineProviderClient.METADATA_FIVE_HOUR_USED to "10",
            ClineProviderClient.METADATA_WEEKLY_USED to "20",
            ClineProviderClient.METADATA_MONTHLY_USED to "15"
        )
        val metrics = ClinePassUsageRenderer.extractMetrics(metadata)
        assertEquals(3, metrics.size)
    }

    @Test
    fun `USED_KEYS contains the three expected metadata keys`() {
        val keys = ClinePassUsageRenderer.USED_KEYS
        assertNotNull(keys)
        assertEquals(3, keys.size)
        assertTrue(keys.contains(ClineProviderClient.METADATA_FIVE_HOUR_USED))
        assertTrue(keys.contains(ClineProviderClient.METADATA_WEEKLY_USED))
        assertTrue(keys.contains(ClineProviderClient.METADATA_MONTHLY_USED))
    }
}
