package org.zhavoronkov.tokenpulse.provider.oauth

/**
 * Pure OAuth access-token expiry decision shared by the providers.
 *
 * - `expiry == null` (missing/unparseable) => `false`: we cannot prove the
 *   token is expired, so treat it as usable and let the usage API's 401 be the
 *   authoritative signal.
 * - Otherwise expired when `now >= expiry - skew`.
 *
 * All three arguments must share the same unit (Claude uses epoch millis;
 * Codex uses epoch seconds) — [skew] is a grace buffer subtracted before the
 * real expiry to absorb clock skew.
 */
internal fun isTokenExpired(expiry: Long?, now: Long, skew: Long): Boolean {
    if (expiry == null) return false
    return now >= expiry - skew
}
