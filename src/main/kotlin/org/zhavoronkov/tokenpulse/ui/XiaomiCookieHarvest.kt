package org.zhavoronkov.tokenpulse.ui

import com.google.gson.Gson

/**
 * Pure cookie-name/value -> Xiaomi session JSON mapping.
 *
 * Kept separate from [XiaomiJcefLoginDialog] so it can be unit-tested without
 * launching JCEF (which cannot run headless in unit tests). The output JSON
 * shape is identical to what [XiaomiConnectDialog.attemptParse] produces, so
 * downstream consumers (persistence, provider client) don't care whether the
 * source was a pasted cURL or an in-IDE JCEF login.
 *
 * platformCookies expected keys: api-platform_serviceToken, userId,
 * api-platform_slh, api-platform_ph.
 * passportCookies expected keys: passToken, userId, cUserId.
 *
 * passportCookies is optional. Without it, auto-refresh is disabled but the
 * session is still usable until it expires.
 */
object XiaomiCookieHarvest {

    private val gson = Gson()

    /**
     * Build the session JSON if the required platform cookies are present.
     * Returns null if serviceToken or userId is missing (session unusable).
     */
    fun buildSessionJson(
        platformCookies: Map<String, String>,
        passportCookies: Map<String, String> = emptyMap()
    ): String? {
        val cookies = buildSessionCookies(platformCookies, passportCookies) ?: return null
        return gson.toJson(cookies)
    }

    fun buildSessionCookies(
        platformCookies: Map<String, String>,
        passportCookies: Map<String, String> = emptyMap()
    ): XiaomiConnectDialog.XiaomiSessionCookies? {
        val serviceToken = platformCookies["api-platform_serviceToken"]?.takeIf { it.isNotBlank() }
        val userId = platformCookies["userId"]?.takeIf { it.isNotBlank() }
            ?: passportCookies["userId"]?.takeIf { it.isNotBlank() }
        if (serviceToken.isNullOrBlank() || userId.isNullOrBlank()) return null

        return XiaomiConnectDialog.XiaomiSessionCookies(
            serviceToken = serviceToken,
            userId = userId,
            slh = platformCookies["api-platform_slh"]?.takeIf { it.isNotBlank() },
            ph = platformCookies["api-platform_ph"]?.takeIf { it.isNotBlank() },
            passToken = passportCookies["passToken"]?.takeIf { it.isNotBlank() },
            cUserId = passportCookies["cUserId"]?.takeIf { it.isNotBlank() }
        )
    }

    /** True if we captured enough to enable silent re-login on expiry. */
    fun hasRefreshableSession(cookies: XiaomiConnectDialog.XiaomiSessionCookies?): Boolean =
        cookies?.passToken?.isNotBlank() == true && cookies.userId?.isNotBlank() == true
}
