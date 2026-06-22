package org.zhavoronkov.tokenpulse.provider

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.zhavoronkov.tokenpulse.model.ProviderResult

class HttpErrorHandlerTest {

    @Test
    fun `mapHttpError returns AuthError for 401`() {
        val result = HttpErrorHandler.mapHttpError(401, "TestProvider")
        assertTrue(result is ProviderResult.Failure.AuthError)
        assertEquals("TestProvider session expired. Please reconnect.", result.message)
    }

    @Test
    fun `mapHttpError returns AuthError for 403`() {
        val result = HttpErrorHandler.mapHttpError(403, "TestProvider")
        assertTrue(result is ProviderResult.Failure.AuthError)
        assertEquals("TestProvider session expired. Please reconnect.", result.message)
    }

    @Test
    fun `mapHttpError returns RateLimited for 429`() {
        val result = HttpErrorHandler.mapHttpError(429, "TestProvider")
        assertTrue(result is ProviderResult.Failure.RateLimited)
        assertEquals("TestProvider rate limit exceeded", result.message)
    }

    @Test
    fun `mapHttpError returns NetworkError for 500`() {
        val result = HttpErrorHandler.mapHttpError(500, "TestProvider")
        assertTrue(result is ProviderResult.Failure.NetworkError)
        assertEquals("TestProvider error: HTTP 500", result.message)
    }

    @Test
    fun `mapHttpError returns NetworkError for 502`() {
        val result = HttpErrorHandler.mapHttpError(502, "TestProvider")
        assertTrue(result is ProviderResult.Failure.NetworkError)
        assertEquals("TestProvider error: HTTP 502", result.message)
    }

    @Test
    fun `mapHttpError uses provider name in error message`() {
        val result = HttpErrorHandler.mapHttpError(401, "Xiaomi")
        assertEquals("Xiaomi session expired. Please reconnect.", result.message)
    }
}
