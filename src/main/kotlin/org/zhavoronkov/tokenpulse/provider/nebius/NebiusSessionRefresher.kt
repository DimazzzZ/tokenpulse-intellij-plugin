package org.zhavoronkov.tokenpulse.provider.nebius

import okhttp3.OkHttpClient
import okhttp3.Request
import org.zhavoronkov.tokenpulse.utils.TokenPulseLogger

/**
 * Cheap, always-safe CSRF-only refresh for a Nebius Token Factory session.
 *
 * ## Why this exists
 * The stored Nebius session carries two moving parts: the `__Host-app_session`
 * cookie (the actual login) and the `csrfToken` (`x-csrf-token` header + the
 * `__Host-psifi.x-csrf-token` cookie). The `csrfToken` rotates far more often
 * than the session cookie expires, so the single most common auth failure is a
 * stale token against a still-valid session — surfaced by the billing gateway
 * as an `EBADCSRFTOKEN` / 401 / 403 envelope.
 *
 * This refresher fixes exactly that case with a plain HTTP call (no browser): it
 * re-fetches the SPA landing page (`GET /`) with the current cookies and scrapes
 * the fresh `csrfToken` that the page embeds in `window.__DATA__`. If the page
 * also rotates the CSRF cookie via `Set-Cookie`, we adopt that too.
 *
 * ## Flow
 * 1. `GET {baseUrl}/` with the current cookies, WITHOUT following redirects.
 * 2. If the response is a 3xx (bounced to `/auth/redirect` → OAuth), the session
 *    cookie itself is dead — return `null` so the caller surfaces a reconnect
 *    prompt (the user must re-capture the session).
 * 3. If 200, read a bounded prefix of the HTML and require
 *    `"isAuthenticatedOnPageLoad":true`; otherwise the session is dead → `null`.
 * 4. Extract `"csrfToken":"<hex>"` from `window.__DATA__`. Return a copy of the
 *    session with the fresh token (and rotated CSRF cookie, if any).
 *
 * Returns `null` whenever the silent CSRF refresh cannot complete; the caller
 * then decides whether to attempt a heavier recovery or surface an AuthError.
 */
class NebiusSessionRefresher(
    private val httpClient: OkHttpClient,
    private val baseUrl: String = NEBIUS_BASE_URL
) {

    /**
     * Attempt a CSRF-only refresh. Requires a non-blank `appSession`; returns
     * `null` otherwise (nothing to authenticate the landing-page fetch with).
     */
    fun refreshCsrf(session: NebiusProviderClient.NebiusSession): NebiusProviderClient.NebiusSession? {
        if (session.appSession.isNullOrBlank()) {
            TokenPulseLogger.Provider.debug("Nebius CSRF refresh skipped: no appSession")
            return null
        }

        // A per-refresh client that does NOT auto-follow redirects: a 3xx here
        // means the session cookie is dead and we must not silently chase the
        // OAuth chain (which OkHttp cannot complete anyway).
        val stepClient = httpClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        return try {
            val request = Request.Builder()
                .url("$baseUrl/")
                .header("accept", "text/html,application/xhtml+xml")
                .header("cookie", cookieHeader(session))
                .build()

            stepClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    TokenPulseLogger.Provider.debug("Nebius CSRF refresh: landing page not 2xx (${response.code})")
                    return null
                }
                val html = response.body?.source()?.let { source ->
                    source.request(MAX_HTML_BYTES)
                    source.buffer.snapshot().utf8()
                }.orEmpty()

                if (!isAuthenticated(html)) {
                    TokenPulseLogger.Provider.debug("Nebius CSRF refresh: page not authenticated")
                    return null
                }
                val freshToken = extractCsrfToken(html) ?: run {
                    TokenPulseLogger.Provider.debug("Nebius CSRF refresh: no csrfToken in landing page")
                    return null
                }
                val rotatedCookie = extractRotatedCsrfCookie(response.headers("Set-Cookie"))
                session.copy(
                    csrfToken = freshToken,
                    csrfCookie = rotatedCookie ?: session.csrfCookie
                )
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            TokenPulseLogger.Provider.warn("Nebius CSRF refresh failed: ${e.message}")
            null
        }
    }

    private fun cookieHeader(session: NebiusProviderClient.NebiusSession): String {
        if (!session.rawCookieHeader.isNullOrBlank()) return session.rawCookieHeader
        return "__Host-app_session=${session.appSession}; __Host-psifi.x-csrf-token=${session.csrfCookie}"
    }

    private fun isAuthenticated(html: String): Boolean =
        html.contains("\"isAuthenticatedOnPageLoad\":true") ||
            html.contains("\"isAuthenticatedOnPageLoad\": true")

    private fun extractCsrfToken(html: String): String? =
        CSRF_TOKEN_REGEX.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    private fun extractRotatedCsrfCookie(setCookies: List<String>): String? {
        val prefix = "__Host-psifi.x-csrf-token="
        for (header in setCookies) {
            val first = header.substringBefore(';').trim()
            if (first.startsWith(prefix)) {
                return first.substring(prefix.length).takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    companion object {
        const val NEBIUS_BASE_URL = "https://tokenfactory.nebius.com"
        private const val MAX_HTML_BYTES = 262_144L
        private val CSRF_TOKEN_REGEX = Regex(""""csrfToken"\s*:\s*"([^"]+)"""")
    }
}
