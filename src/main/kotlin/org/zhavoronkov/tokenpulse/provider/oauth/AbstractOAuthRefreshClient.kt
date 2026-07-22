package org.zhavoronkov.tokenpulse.provider.oauth

import org.zhavoronkov.tokenpulse.utils.TokenPulseLogger
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Shared control flow for exchanging an OAuth refresh token for fresh tokens.
 *
 * The `refresh()` skeleton — blank-token guard, `send`, status dispatch, and the
 * three network-failure catch clauses — was identical in shape across the Claude
 * and Codex refresh clients. Each provider keeps its OWN result type [R] and its
 * own request/status handling by implementing the abstract hooks; the base owns
 * only the parts that were genuinely the same.
 *
 * @param R the provider's refresh-result type (e.g. Claude's `RefreshResult`,
 *   Codex's `RefreshResult`). Deliberately not unified — the two carry different
 *   fields (Claude: expiresAt/scope; Codex: idToken + a failure-reason enum).
 */
abstract class AbstractOAuthRefreshClient<R>(
    connectSeconds: Long,
    private val logTag: String,
) {
    protected val httpClient = oauthHttpClient(connectSeconds)

    fun refresh(refreshToken: String): R {
        if (refreshToken.isBlank()) return emptyTokenResult()

        TokenPulseLogger.Provider.debug("[$logTag] Refreshing OAuth token")

        return try {
            val response = httpClient.send(buildRequest(refreshToken), HttpResponse.BodyHandlers.ofString())
            TokenPulseLogger.Provider.debug("[$logTag] Refresh response: ${response.statusCode()}")
            mapStatus(response.statusCode(), response.body())
        } catch (_: java.net.http.HttpTimeoutException) {
            TokenPulseLogger.Provider.warn("[$logTag] Refresh request timed out")
            transient("Token refresh timed out")
        } catch (e: java.net.ConnectException) {
            TokenPulseLogger.Provider.warn("[$logTag] Refresh connection failed: ${e.message}")
            transient("Cannot connect to token endpoint: ${e.message}")
        } catch (e: Exception) {
            TokenPulseLogger.Provider.error("[$logTag] Refresh request failed", e)
            transient("Token refresh failed: ${e.message}")
        }
    }

    /** Build the provider's token-refresh POST request. */
    protected abstract fun buildRequest(refreshToken: String): HttpRequest

    /** Map an HTTP status + body to the provider's result (success/auth/other). */
    protected abstract fun mapStatus(status: Int, body: String): R

    /** Result for a blank refresh token (no request is made). */
    protected abstract fun emptyTokenResult(): R

    /** Result for a transient network/server failure carrying [message]. */
    protected abstract fun transient(message: String): R

    protected fun clientId(env: String, default: String): String =
        System.getenv(env)?.takeIf { it.isNotBlank() } ?: default
}
