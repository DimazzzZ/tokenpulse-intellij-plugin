package org.zhavoronkov.tokenpulse.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CliLocatorTest {

    @Test
    fun `findBinary returns path for known existing binary`() {
        val result = CliLocator.findBinary("ls", listOf("/bin/ls"))
        assertEquals("/bin/ls", result)
    }

    @Test
    fun `findBinary skips known locations that don't exist`() {
        val result = CliLocator.findBinary("ls", listOf("/nonexistent/path/ls"))
        assertNotNull(result)
    }

    @Test
    fun `findBinary falls back to which when known locations fail`() {
        val result = CliLocator.findBinary("ls", listOf("/nonexistent/path"))
        assertNotNull(result)
        assertTrue(result!!.contains("ls"))
    }

    @Test
    fun `findBinary returns null when binary not found anywhere`() {
        val result = CliLocator.findBinary("definitely_nonexistent_binary_xyz_12345")
        assertNull(result)
    }

    @Test
    fun `findBinary with empty knownLocations delegates entirely to which`() {
        val result = CliLocator.findBinary("ls", emptyList())
        assertNotNull(result)
    }

    @Test
    fun `findBinary returns first matching known location`() {
        val result = CliLocator.findBinary("ls", listOf("/bin/ls", "/usr/bin/ls"))
        assertEquals("/bin/ls", result)
    }

    @Test
    fun `verifyBinaryWorks returns true with version for valid binary`() {
        val (success, version) = CliLocator.verifyBinaryWorks("/bin/echo")
        assertTrue(success)
        assertNotNull(version)
    }

    @Test
    fun `verifyBinaryWorks returns false when binary exits non-zero`() {
        val (success, version) = CliLocator.verifyBinaryWorks("/bin/ls", "--nonexistent-flag-xyz")
        assertFalse(success)
    }

    @Test
    fun `verifyBinaryWorks returns false when binary throws exception`() {
        val (success, version) = CliLocator.verifyBinaryWorks("/nonexistent/binary")
        assertFalse(success)
    }

    @Test
    fun `verifyBinaryWorks returns true with output for echo`() {
        val (success, version) = CliLocator.verifyBinaryWorks("/bin/echo", "--version")
        assertTrue(success)
        assertNotNull(version)
        assertTrue(version!!.isNotBlank())
    }
}
