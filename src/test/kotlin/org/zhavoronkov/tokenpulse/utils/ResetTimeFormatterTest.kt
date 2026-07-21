package org.zhavoronkov.tokenpulse.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId

/**
 * Tests for [ResetTimeFormatter]. A fixed [now] and a UTC [zone] make the
 * relative-aware output deterministic regardless of the host clock/timezone.
 */
class ResetTimeFormatterTest {

    private val zone = ZoneId.of("UTC")

    // 2026-01-01T12:00:00Z — a Thursday.
    private val now = Instant.parse("2026-01-01T12:00:00Z")

    private fun fmt(iso: String?) = ResetTimeFormatter.formatReset(iso, now, zone)

    @Test
    fun `same day renders Today with time`() {
        assertEquals("Today 14:30", fmt("2026-01-01T14:30:00Z"))
    }

    @Test
    fun `next day renders Tomorrow with time`() {
        assertEquals("Tomorrow 09:00", fmt("2026-01-02T09:00:00Z"))
    }

    @Test
    fun `within a week renders weekday with time`() {
        // 2026-01-04 is a Sunday, 3 days ahead.
        assertEquals("Sun 09:00", fmt("2026-01-04T09:00:00Z"))
    }

    @Test
    fun `far future renders absolute date with time`() {
        assertEquals("Jan 11, 09:00", fmt("2026-01-11T09:00:00Z"))
    }

    @Test
    fun `past instant renders absolute date with time`() {
        assertEquals("Dec 25, 08:15", fmt("2025-12-25T08:15:00Z"))
    }

    @Test
    fun `offset form parses`() {
        // 2026-01-01T13:00:00+02:00 == 11:00Z, same UTC day as now.
        assertEquals("Today 11:00", fmt("2026-01-01T13:00:00+02:00"))
    }

    @Test
    fun `null blank and garbage return null (no raw leak)`() {
        assertNull(fmt(null))
        assertNull(fmt(""))
        assertNull(fmt("   "))
        assertNull(fmt("garbage"))
        assertNull(fmt("2026-13-99"))
    }

    @Test
    fun `parseable value never echoes the raw input`() {
        val raw = "2026-01-01T14:30:00Z"
        val out = fmt(raw)
        assertTrue(out != null)
        assertNotEquals(raw, out)
    }
}
