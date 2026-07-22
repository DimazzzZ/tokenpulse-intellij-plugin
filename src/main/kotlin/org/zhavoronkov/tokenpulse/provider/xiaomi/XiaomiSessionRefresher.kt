package org.zhavoronkov.tokenpulse.provider.xiaomi

import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.zhavoronkov.tokenpulse.utils.TokenPulseLogger

/**
 * Performs a headless "silent re-login" for the Xiaomi MiMo platform, mirroring
 * what the browser does when the short-lived platform session expires.
 *
 * ## Why this exists
 * The cookies scoped to `platform.xiaomimimo.com` (`api-platform_serviceToken`,
 * `api-platform_slh`, `api-platform_ph`) expire after ~1 day. The browser keeps
 * working for days because it also holds a long-lived Xiaomi Passport cookie
 * (`passToken`) on `account.xiaomi.com`. When the platform session dies, the SPA
 * calls `genLoginUrl`, which 302s to `account.xiaomi.com/pass/serviceLogin`; the
 * passport cookies silently mint a fresh platform session via the `/sts?sign=...`
 * callback. This class reproduces that redirect chain with OkHttp.
 *
 * ## Flow
 * 1. `GET {platform}/api/v1/genLoginUrl?currentPath=/console/balance` (no redirect
 *    follow) -> read the `account.xiaomi.com/pass/serviceLogin?...` callback URL
 *    (from the `Location` header on a 302, or a `location` field in a 200 body).
 * 2. `GET {account}/pass/serviceLogin?...&_json=true` with the passport cookies ->
 *    body is prefixed with `&&&START&&&`; the JSON must contain `location` (the
 *    signed `/sts` callback) and `ssecurity` (proves silent SSO succeeded, i.e. no
 *    captcha / password / 2FA needed).
 * 3. `GET` the returned `location` (`{platform}/sts?sign=...`) -> the response
 *    `Set-Cookie`s a fresh `api-platform_serviceToken` (+ `_slh`, `_ph`).
 *
 * Returns a new [XiaomiProviderClient.XiaomiSession] with the refreshed platform
 * cookies and the preserved passport cookies, or `null` if the silent re-login
 * could not complete (caller then surfaces an AuthError telling the user to
 * reconnect).
 *
 * The absolute Xiaomi URLs returned by the redirect chain are re-hosted onto the
 * injected [platformBaseUrl] / [accountBaseUrl] so the flow is testable against a
 * single mock server while behaving identically in production.
 */
class XiaomiSessionRefresher(
    private val httpClient: OkHttpClient,
    private val gson: Gson = Gson(),
    private val platformBaseUrl: String = XiaomiProviderClient.XIAOMI_PLATFORM_URL,
    private val accountBaseUrl: String = XIAOMI_ACCOUNT_URL
) {

    /**
     * Attempt a silent re-login. Requires [XiaomiProviderClient.XiaomiSession.passToken]
     * and [XiaomiProviderClient.XiaomiSession.userId]; returns `null` otherwise.
     */
    fun refresh(session: XiaomiProviderClient.XiaomiSession): XiaomiProviderClient.XiaomiSession? {
        if (session.passToken.isNullOrBlank() || session.userId.isNullOrBlank()) {
            TokenPulseLogger.Provider.debug("Xiaomi refresh skipped: no passToken/userId captured")
            return null
        }

        // A per-refresh client that does NOT auto-follow redirects, so we can drive
        // the cross-host chain step by step. The shared client is left untouched.
        val stepClient = httpClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        return try {
            val serviceLoginUrl = fetchServiceLoginUrl(stepClient, session) ?: return null
            val stsUrl = fetchStsCallbackUrl(stepClient, serviceLoginUrl, session) ?: return null
            val platformCookies = harvestPlatformCookies(stepClient, stsUrl, session) ?: return null
            session.copy(
                serviceToken = platformCookies.serviceToken,
                slh = platformCookies.slh ?: session.slh,
                ph = platformCookies.ph ?: session.ph
            )
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            TokenPulseLogger.Provider.warn("Xiaomi silent refresh failed: ${e.message}")
            null
        }
    }

    /** Step 1: call genLoginUrl and extract the account.xiaomi.com serviceLogin callback URL. */
    private fun fetchServiceLoginUrl(
        client: OkHttpClient,
        session: XiaomiProviderClient.XiaomiSession
    ): HttpUrl? {
        val request = Request.Builder()
            .url("$platformBaseUrl/api/v1/genLoginUrl?currentPath=%2Fconsole%2Fbalance")
            .header("Accept", "*/*")
            .header("Cookie", platformCookieHeader(session))
            .build()

        client.newCall(request).execute().use { response ->
            val location = response.header("Location")
                ?: extractJsonField(response.body?.string().orEmpty(), "location")
                ?: return null
            return rehost(location, accountBaseUrl)
        }
    }

    /** Step 2: call serviceLogin with the passport cookies and extract the signed /sts callback. */
    private fun fetchStsCallbackUrl(
        client: OkHttpClient,
        serviceLoginUrl: HttpUrl,
        session: XiaomiProviderClient.XiaomiSession
    ): HttpUrl? {
        val jsonUrl = serviceLoginUrl.newBuilder().setQueryParameter("_json", "true").build()
        val request = Request.Builder()
            .url(jsonUrl)
            .header("Accept", "*/*")
            .header("Cookie", passportCookieHeader(session))
            .build()

        client.newCall(request).execute().use { response ->
            val json = parsePassportJson(response.body?.string().orEmpty()) ?: return null
            // ssecurity present => already authenticated silently (no captcha/2FA).
            if (getString(json, "ssecurity").isNullOrBlank()) {
                TokenPulseLogger.Provider.debug("Xiaomi refresh: passport not silently authenticated (no ssecurity)")
                return null
            }
            val location = getString(json, "location") ?: return null
            return rehost(location, platformBaseUrl)
        }
    }

    /** Step 3: GET the /sts callback and harvest the fresh platform Set-Cookie values. */
    private fun harvestPlatformCookies(
        client: OkHttpClient,
        stsUrl: HttpUrl,
        session: XiaomiProviderClient.XiaomiSession
    ): PlatformCookies? {
        val request = Request.Builder()
            .url(stsUrl)
            .header("Accept", "*/*")
            .header("Cookie", passportCookieHeader(session))
            .build()

        client.newCall(request).execute().use { response ->
            val setCookies = response.headers("Set-Cookie")
            val serviceToken = cookieValue(setCookies, "api-platform_serviceToken") ?: return null
            return PlatformCookies(
                serviceToken = serviceToken,
                slh = cookieValue(setCookies, "api-platform_slh"),
                ph = cookieValue(setCookies, "api-platform_ph")
            )
        }
    }

    private fun platformCookieHeader(session: XiaomiProviderClient.XiaomiSession): String {
        val parts = mutableListOf<String>()
        session.serviceToken?.let { parts.add("api-platform_serviceToken=\"$it\"") }
        session.userId?.let { parts.add("userId=$it") }
        session.slh?.let { parts.add("api-platform_slh=\"$it\"") }
        session.ph?.let { parts.add("api-platform_ph=\"$it\"") }
        return parts.joinToString("; ")
    }

    private fun passportCookieHeader(session: XiaomiProviderClient.XiaomiSession): String {
        val parts = mutableListOf<String>()
        session.passToken?.let { parts.add("passToken=$it") }
        session.userId?.let { parts.add("userId=$it") }
        session.cUserId?.let { parts.add("cUserId=$it") }
        return parts.joinToString("; ")
    }

    /**
     * Re-host an absolute Xiaomi URL onto an injected base URL, preserving path and
     * query. This keeps production behavior (real Xiaomi hosts) while letting tests
     * route the whole chain through one mock server.
     */
    private fun rehost(rawUrl: String, base: String): HttpUrl? {
        val target = rawUrl.toHttpUrlOrNull() ?: return null
        val baseUrl = base.toHttpUrlOrNull() ?: return null
        return baseUrl.newBuilder()
            .encodedPath(target.encodedPath)
            .encodedQuery(target.encodedQuery)
            .build()
    }

    /** Strip the `&&&START&&&` prefix Xiaomi Passport prepends, then parse JSON. */
    private fun parsePassportJson(body: String): JsonObject? {
        val trimmed = body.trim().removePrefix(PASSPORT_JSON_PREFIX).trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) return null
        return try {
            gson.fromJson(trimmed, JsonObject::class.java)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            null
        }
    }

    private fun extractJsonField(body: String, field: String): String? {
        val json = try {
            gson.fromJson(body, JsonObject::class.java)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            return null
        }
        return getString(json, field)
    }

    private fun getString(json: JsonObject?, field: String): String? {
        val element = json?.get(field) ?: return null
        return if (element.isJsonPrimitive) element.asString else null
    }

    private fun cookieValue(setCookies: List<String>, name: String): String? {
        val prefix = "$name="
        for (header in setCookies) {
            val first = header.substringBefore(';').trim()
            if (first.startsWith(prefix)) {
                return first.substring(prefix.length).removeSurrounding("\"")
            }
        }
        return null
    }

    private data class PlatformCookies(
        val serviceToken: String,
        val slh: String?,
        val ph: String?
    )

    companion object {
        const val XIAOMI_ACCOUNT_URL = "https://account.xiaomi.com"
        private const val PASSPORT_JSON_PREFIX = "&&&START&&&"
    }
}
