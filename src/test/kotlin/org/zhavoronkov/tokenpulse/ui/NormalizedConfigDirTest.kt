package org.zhavoronkov.tokenpulse.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Tests for [normalizedConfigDir] — the dedup key used when adding Claude Code
 * accounts. null/blank collapse to null (the default account); real paths are
 * canonicalized so equivalent spellings dedup to one row.
 */
class NormalizedConfigDirTest {

    @Test
    fun `null stays null`() {
        assertNull(normalizedConfigDir(null))
    }

    @Test
    fun `blank and whitespace collapse to null`() {
        assertNull(normalizedConfigDir(""))
        assertNull(normalizedConfigDir("   "))
    }

    @Test
    fun `equivalent spellings canonicalize to the same value`() {
        val plain = normalizedConfigDir("/tmp/foo/bar")
        val dotted = normalizedConfigDir("/tmp/foo/./bar")
        assertEquals(plain, dotted)
    }

    @Test
    fun `canonical path matches File canonicalPath`() {
        val input = "/tmp/foo/./bar"
        assertEquals(File(input).canonicalPath, normalizedConfigDir(input))
    }
}
