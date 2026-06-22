package org.zhavoronkov.tokenpulse.provider

import com.google.gson.Gson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SessionParserTest {

    private data class TestSession(
        val token: String? = null,
        val userId: String? = null
    )

    private val gson = Gson()

    @Test
    fun `parse returns session for valid JSON`() {
        val json = """{"token":"abc123","userId":"user1"}"""
        val result = SessionParser.parse(
            secret = json,
            sessionClass = TestSession::class.java,
            validator = { !it.token.isNullOrBlank() },
            providerName = "Test",
            gson = gson
        )
        assertNotNull(result)
        assertEquals("abc123", result?.token)
        assertEquals("user1", result?.userId)
    }

    @Test
    fun `parse returns null for invalid JSON`() {
        val result = SessionParser.parse(
            secret = "not-valid-json",
            sessionClass = TestSession::class.java,
            validator = { true },
            providerName = "Test",
            gson = gson
        )
        assertNull(result)
    }

    @Test
    fun `parse returns null when validator fails`() {
        val json = """{"token":"","userId":"user1"}"""
        val result = SessionParser.parse(
            secret = json,
            sessionClass = TestSession::class.java,
            validator = { !it.token.isNullOrBlank() },
            providerName = "Test",
            gson = gson
        )
        assertNull(result)
    }

    @Test
    fun `parse returns null for blank token`() {
        val json = """{"token":"   ","userId":"user1"}"""
        val result = SessionParser.parse(
            secret = json,
            sessionClass = TestSession::class.java,
            validator = { !it.token.isNullOrBlank() },
            providerName = "Test",
            gson = gson
        )
        assertNull(result)
    }

    @Test
    fun `parse handles empty JSON object`() {
        val json = """{}"""
        val result = SessionParser.parse(
            secret = json,
            sessionClass = TestSession::class.java,
            validator = { true },
            providerName = "Test",
            gson = gson
        )
        assertNotNull(result)
        assertNull(result?.token)
        assertNull(result?.userId)
    }
}
