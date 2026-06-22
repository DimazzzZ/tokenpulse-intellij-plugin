package org.zhavoronkov.tokenpulse.provider

import org.zhavoronkov.tokenpulse.model.ProviderResult

/**
 * Maps HTTP error codes to provider-specific failure results.
 * Shared across session-based provider clients (Nebius, Xiaomi, etc.)
 */
object HttpErrorHandler {

    private const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_FORBIDDEN = 403
    private const val HTTP_TOO_MANY_REQUESTS = 429

    /**
     * Map an HTTP status code to a [ProviderResult.Failure].
     *
     * @param code HTTP status code
     * @param providerName Display name for error messages (e.g., "Nebius", "Xiaomi")
     * @return Appropriate failure result
     */
    fun mapHttpError(code: Int, providerName: String): ProviderResult.Failure = when (code) {
        HTTP_UNAUTHORIZED, HTTP_FORBIDDEN ->
            ProviderResult.Failure.AuthError("$providerName session expired. Please reconnect.")
        HTTP_TOO_MANY_REQUESTS ->
            ProviderResult.Failure.RateLimited("$providerName rate limit exceeded")
        else ->
            ProviderResult.Failure.NetworkError("$providerName error: HTTP $code")
    }
}
