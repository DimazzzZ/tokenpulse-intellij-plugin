package org.zhavoronkov.tokenpulse.provider.oauth

import org.zhavoronkov.tokenpulse.utils.TokenPulseLogger
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Shared HTTP send/status/catch skeleton for OAuth usage GETs.
 *
 * The usage endpoints differ in URL, extra headers, response shape, and result
 * taxonomy — the two clients keep those provider-specific. What was identical is
 * the `send` + `when(status)` + three-catch-clauses flow, which lives here.
 *
 * A subclass builds its own request in its `fetch(...)` public method (with a
 * provider-shaped signature) and calls [execute] to perform the round-trip.
 */
abstract class AbstractOAuthUsageClient<R>(
    connectSeconds: Long,
    private val logTag: String,
) {
    protected val httpClient = oauthHttpClient(connectSeconds)

    protected fun execute(request: HttpRequest): R {
        TokenPulseLogger.Provider.debug("[$logTag] Fetching usage")
        return try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            TokenPulseLogger.Provider.debug("[$logTag] Usage response: ${response.statusCode()}")
            mapStatus(response.statusCode(), response.body())
        } catch (_: java.net.http.HttpTimeoutException) {
            TokenPulseLogger.Provider.warn("[$logTag] Usage request timed out")
            transient("Usage request timed out")
        } catch (e: java.net.ConnectException) {
            TokenPulseLogger.Provider.warn("[$logTag] Usage connection failed: ${e.message}")
            transient("Cannot connect to usage API: ${e.message}")
        } catch (e: Exception) {
            TokenPulseLogger.Provider.error("[$logTag] Usage request failed", e)
            transient("Usage API request failed: ${e.message}")
        }
    }

    /** Map an HTTP status + body to the provider's result (success / auth / rate-limited / …). */
    protected abstract fun mapStatus(status: Int, body: String): R

    /** Result for a transient network/server failure carrying [message]. */
    protected abstract fun transient(message: String): R
}
