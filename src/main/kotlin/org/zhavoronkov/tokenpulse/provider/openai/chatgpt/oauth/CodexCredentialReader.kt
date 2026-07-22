package org.zhavoronkov.tokenpulse.provider.openai.chatgpt.oauth

import com.google.gson.JsonObject
import org.zhavoronkov.tokenpulse.provider.oauth.OAuthCredentialStore
import org.zhavoronkov.tokenpulse.provider.oauth.isTokenExpired
import org.zhavoronkov.tokenpulse.utils.TokenPulseLogger
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Reads (and, on refresh, writes) the Codex CLI's `auth.json`.
 *
 * Unlike the read-only Claude reader, this reader can persist rotated tokens
 * back to disk — because Codex refresh tokens are single-use, leaving the
 * on-disk token stale would break the user's real `codex` login (reuse
 * detection). See [writeAtomic] for the safety measures.
 */
class CodexCredentialReader(
    private val authFile: File = CodexConfigLocator.authFile(),
) {

    /**
     * Parse `auth.json` into a [CodexAuthDotJson], or null if the file is
     * missing/unreadable/malformed.
     */
    fun read(): CodexAuthDotJson? {
        val json = readRawJson() ?: return null
        return try {
            codexAuthGson.fromJson(json, CodexAuthDotJson::class.java)
        } catch (e: Exception) {
            logWarn("Failed to deserialize auth.json: ${e.message}")
            null
        }
    }

    fun accessToken(): String? = read()?.tokens?.accessToken?.takeIf { it.isNotBlank() }

    fun refreshToken(): String? = read()?.tokens?.refreshToken?.takeIf { it.isNotBlank() }

    /**
     * The workspace/account id used for the `ChatGPT-Account-Id` header. Falls
     * back to the `chatgpt_account_id` claim in the id_token when the top-level
     * `tokens.account_id` is absent.
     */
    fun accountId(): String? {
        val tokens = read()?.tokens ?: return null
        tokens.accountId?.takeIf { it.isNotBlank() }?.let { return it }
        return IdTokenClaims.parse(tokens.idToken).chatgptAccountId
    }

    /**
     * Whether the stored access token is expired (or within [skewSeconds] of
     * expiring). A missing/unparseable `exp` returns false ("unknown => assume
     * usable"), leaving the usage endpoint's real 401 as the authoritative
     * signal — this avoids reporting a logged-in user as expired on a parse
     * hiccup.
     */
    fun isAccessTokenExpired(now: Instant = Instant.now(), skewSeconds: Long = EXPIRY_SKEW_SECONDS): Boolean {
        val exp = jwtExpirationEpochSeconds(accessToken()) ?: return false
        return isCodexTokenExpired(exp, now.epochSecond, skewSeconds)
    }

    /**
     * Atomically persist refreshed tokens back to `auth.json`, mirroring what
     * the `codex` CLI does after a refresh.
     *
     * Safety measures:
     * - Operates on the raw JSON tree, mutating only `tokens.id_token`,
     *   `tokens.access_token`, `tokens.refresh_token` and `last_refresh`, so
     *   any fields we don't model (`agent_identity`, `OPENAI_API_KEY`, future
     *   keys) are preserved verbatim.
     * - Writes to a sibling temp file, sets POSIX 0600 on it, then
     *   `ATOMIC_MOVE`s it over `auth.json` (falling back to a plain replace on
     *   filesystems without atomic move).
     * - Races with a concurrently-running `codex` CLI are unavoidable (codex
     *   takes no lock either); last writer wins and the loser sees a
     *   reuse-detection auth error on its next call.
     *
     * @return true on success; false if the file could not be read or written.
     */
    fun writeAtomic(
        idToken: String?,
        accessToken: String,
        refreshToken: String?,
        now: Instant = Instant.now(),
    ): Boolean {
        val root = readRawJson() ?: run {
            logWarn("Cannot write auth.json: existing file unreadable")
            return false
        }
        return try {
            val tokens = root.getAsJsonObject("tokens") ?: JsonObject().also { root.add("tokens", it) }
            idToken?.takeIf { it.isNotBlank() }?.let { tokens.addProperty("id_token", it) }
            tokens.addProperty("access_token", accessToken)
            refreshToken?.takeIf { it.isNotBlank() }?.let { tokens.addProperty("refresh_token", it) }
            root.addProperty("last_refresh", DateTimeFormatter.ISO_INSTANT.format(now))

            writeAtomicRaw(root.toString())
            logDebug("Persisted refreshed tokens to ${authFile.absolutePath}")
            true
        } catch (e: Exception) {
            logWarn("Failed to write auth.json: ${e.message}")
            false
        }
    }

    private fun writeAtomicRaw(content: String) {
        OAuthCredentialStore.writeAtomic(authFile, content, tmpPrefix = "auth")
    }

    private fun readRawJson(): JsonObject? {
        if (!authFile.exists()) {
            logDebug("auth.json not found: ${authFile.absolutePath}")
            return null
        }
        val json = OAuthCredentialStore.readJsonObject(authFile)
        if (json == null) {
            logWarn("Failed to read auth.json (malformed or not a JSON object)")
        }
        return json
    }

    private fun logDebug(msg: String) = TokenPulseLogger.Provider.debug("[CodexCredentialReader] $msg")
    private fun logWarn(msg: String) = TokenPulseLogger.Provider.warn("[CodexCredentialReader] $msg")

    companion object {
        /** Refresh this many seconds before the real expiry to absorb clock skew. */
        internal const val EXPIRY_SKEW_SECONDS = 60L
    }
}

/**
 * Pure expiry decision, extracted for unit testing. All timestamps are Unix
 * epoch seconds. A null `expEpochSeconds` returns false (unknown => usable).
 */
internal fun isCodexTokenExpired(expEpochSeconds: Long?, nowEpochSeconds: Long, skewSeconds: Long): Boolean {
    return isTokenExpired(expEpochSeconds, nowEpochSeconds, skewSeconds)
}
