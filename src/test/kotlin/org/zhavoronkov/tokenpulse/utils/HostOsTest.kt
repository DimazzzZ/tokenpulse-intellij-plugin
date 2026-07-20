package org.zhavoronkov.tokenpulse.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [HostOs] and [detectHostOs].
 */
class HostOsTest {

    @Test
    fun `detectHostOs returns valid OS`() {
        val os = detectHostOs()
        assertTrue(os in HostOs.entries)
    }

    @Test
    fun `detectHostOs matches current system`() {
        val os = detectHostOs()
        val osName = System.getProperty("os.name").lowercase()

        when {
            osName.contains("mac") -> assertEquals(HostOs.MACOS, os)
            osName.contains("windows") -> assertEquals(HostOs.WINDOWS, os)
            osName.contains("linux") -> assertEquals(HostOs.LINUX, os)
        }
    }

    @Test
    fun `HostOs enum has expected values`() {
        val values = HostOs.entries
        assertEquals(4, values.size)
        assertTrue(values.contains(HostOs.WINDOWS))
        assertTrue(values.contains(HostOs.MACOS))
        assertTrue(values.contains(HostOs.LINUX))
        assertTrue(values.contains(HostOs.UNKNOWN))
    }
}
