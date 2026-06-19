package org.zhavoronkov.tokenpulse.provider.openai.chatgpt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CodexAppServerClientTest {

    private val client = CodexAppServerClient()

    // === AccountInfo.isAuthenticated ===

    @Test
    fun `isAuthenticated returns true when type is chatgpt`() {
        val info = CodexAppServerClient.AccountInfo(type = "chatgpt")
        assertTrue(info.isAuthenticated)
    }

    @Test
    fun `isAuthenticated returns true when email is present`() {
        val info = CodexAppServerClient.AccountInfo(email = "user@example.com")
        assertTrue(info.isAuthenticated)
    }

    @Test
    fun `isAuthenticated returns false when both type and email are null`() {
        val info = CodexAppServerClient.AccountInfo()
        assertFalse(info.isAuthenticated)
    }

    @Test
    fun `isAuthenticated returns false when email is blank string`() {
        val info = CodexAppServerClient.AccountInfo(email = "  ")
        assertFalse(info.isAuthenticated)
    }

    // === classifyErrorCode ===

    @Test
    fun `classifyErrorCode returns token_expired for direct keyword`() {
        assertEquals("token_expired", client.classifyErrorCode("token_expired"))
    }

    @Test
    fun `classifyErrorCode returns token_expired for natural language`() {
        assertEquals("token_expired", client.classifyErrorCode("The token is expired"))
    }

    @Test
    fun `classifyErrorCode returns token_expired for authentication token expired`() {
        assertEquals("token_expired", client.classifyErrorCode("authentication token is expired"))
    }

    @Test
    fun `classifyErrorCode returns token_expired for nested JSON code`() {
        val msg = """{"error": {"code": "token_expired", "message": "expired"}}"""
        assertEquals("token_expired", client.classifyErrorCode(msg))
    }

    @Test
    fun `classifyErrorCode returns refresh_token_reused for direct keyword`() {
        assertEquals("refresh_token_reused", client.classifyErrorCode("refresh_token_reused"))
    }

    @Test
    fun `classifyErrorCode returns refresh_token_reused for nested JSON code`() {
        val msg = """{"error": {"code": "refresh_token_reused"}}"""
        assertEquals("refresh_token_reused", client.classifyErrorCode(msg))
    }

    @Test
    fun `classifyErrorCode returns unauthorized for direct keyword`() {
        assertEquals("unauthorized", client.classifyErrorCode("unauthorized access"))
    }

    @Test
    fun `classifyErrorCode returns unauthorized for 401 without rate`() {
        assertEquals("unauthorized", client.classifyErrorCode("HTTP 401 error"))
    }

    @Test
    fun `classifyErrorCode does not return unauthorized for 401 with rate`() {
        val result = client.classifyErrorCode("HTTP 401 rate limit exceeded")
        assert(result != "unauthorized")
    }

    @Test
    fun `classifyErrorCode returns limits_refresh_pending for refresh requested`() {
        assertEquals("limits_refresh_pending", client.classifyErrorCode("refresh requested"))
    }

    @Test
    fun `classifyErrorCode returns limits_refresh_pending for try again shortly`() {
        assertEquals("limits_refresh_pending", client.classifyErrorCode("try again shortly"))
    }

    @Test
    fun `classifyErrorCode returns limits_refresh_pending for rate limits unavailable`() {
        assertEquals("limits_refresh_pending", client.classifyErrorCode("rate limits unavailable"))
    }

    @Test
    fun `classifyErrorCode returns unknown for unrecognized message`() {
        assertEquals("unknown", client.classifyErrorCode("something went wrong"))
    }

    // === RateLimits bucket resolution ===

    @Test
    fun `fiveHourBucket returns primary when primary has 300 min window`() {
        val primary = CodexAppServerClient.RateLimitBucket(usedPercent = 50f, windowDurationMins = 300)
        val rateLimits = CodexAppServerClient.RateLimits(primary = primary)
        assertNotNull(rateLimits.fiveHourBucket)
        assertEquals(50f, rateLimits.fiveHourBucket!!.usedPercent)
    }

    @Test
    fun `fiveHourBucket returns secondary when secondary has 300 min window`() {
        val secondary = CodexAppServerClient.RateLimitBucket(usedPercent = 30f, windowDurationMins = 300)
        val rateLimits = CodexAppServerClient.RateLimits(secondary = secondary)
        assertNotNull(rateLimits.fiveHourBucket)
        assertEquals(30f, rateLimits.fiveHourBucket!!.usedPercent)
    }

    @Test
    fun `fiveHourBucket scans bucketsById excluding code-review buckets`() {
        val regular = CodexAppServerClient.RateLimitBucket(
            limitId = "regular",
            usedPercent = 40f,
            windowDurationMins = 300
        )
        val codeReview = CodexAppServerClient.RateLimitBucket(
            limitId = "code-review-5h",
            limitName = "code review",
            usedPercent = 90f,
            windowDurationMins = 300
        )
        val rateLimits = CodexAppServerClient.RateLimits(
            bucketsById = mapOf("regular" to regular, "review" to codeReview)
        )
        assertNotNull(rateLimits.fiveHourBucket)
        assertEquals("regular", rateLimits.fiveHourBucket!!.limitId)
    }

    @Test
    fun `weeklyBucket returns primary when primary has 10080 min window`() {
        val primary = CodexAppServerClient.RateLimitBucket(usedPercent = 20f, windowDurationMins = 10080)
        val rateLimits = CodexAppServerClient.RateLimits(primary = primary)
        assertNotNull(rateLimits.weeklyBucket)
        assertEquals(20f, rateLimits.weeklyBucket!!.usedPercent)
    }

    @Test
    fun `weeklyBucket excludes code-review bucket from bucketsById scan`() {
        val codeReview = CodexAppServerClient.RateLimitBucket(
            limitId = "review-weekly",
            limitName = "code review weekly",
            usedPercent = 80f,
            windowDurationMins = 10080
        )
        val rateLimits = CodexAppServerClient.RateLimits(
            bucketsById = mapOf("review" to codeReview)
        )
        assertNull(rateLimits.weeklyBucket)
    }

    @Test
    fun `fiveHourBucket returns null when no matching bucket exists`() {
        val rateLimits = CodexAppServerClient.RateLimits()
        assertNull(rateLimits.fiveHourBucket)
    }

    @Test
    fun `weeklyBucket returns null when only code-review weekly bucket exists`() {
        val codeReview = CodexAppServerClient.RateLimitBucket(
            limitId = "review",
            limitName = "code review",
            usedPercent = 50f,
            windowDurationMins = 10080
        )
        val rateLimits = CodexAppServerClient.RateLimits(
            bucketsById = mapOf("review" to codeReview)
        )
        assertNull(rateLimits.weeklyBucket)
    }

    // === CodexStartupResult ===

    @Test
    fun `CodexStartupResult success with version`() {
        val result = CodexStartupResult(success = true, codexVersion = "0.1.0")
        assertTrue(result.success)
        assertEquals("0.1.0", result.codexVersion)
        assertNull(result.errorMessage)
    }

    @Test
    fun `CodexStartupResult failure with diagnostics`() {
        val result = CodexStartupResult(
            success = false,
            errorMessage = "exit code 1",
            stderrPreview = "error: something",
            exitCode = 1,
            codexVersion = "0.1.0"
        )
        assertFalse(result.success)
        assertEquals("exit code 1", result.errorMessage)
        assertEquals("error: something", result.stderrPreview)
        assertEquals(1, result.exitCode)
    }

    // === CodexRpcException ===

    @Test
    fun `CodexRpcException carries error code`() {
        val ex = CodexRpcException("token expired", "token_expired")
        assertEquals("token_expired", ex.code)
        assertTrue(ex.message!!.contains("token expired"))
    }
}
