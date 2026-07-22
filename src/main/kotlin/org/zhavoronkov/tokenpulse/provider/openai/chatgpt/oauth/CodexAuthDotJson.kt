package org.zhavoronkov.tokenpulse.provider.openai.chatgpt.oauth

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import org.zhavoronkov.tokenpulse.utils.TokenPulseLogger
import java.util.Base64

/**
 * Strongly-typed view of the fields in Codex's `auth.json` that the plugin
 * cares about. Unknown/unmodeled fields (e.g. `agent_identity`,
 * `personal_access_token`) are intentionally NOT captured here — write-back
 * preserves them by operating on the raw [com.google.gson.JsonObject]
 * ([CodexCredentialReader.writeAtomic]) rather than round-tripping this DTO.
 */
data class CodexAuthDotJson(
    @SerializedName("auth_mode") val authMode: String? = null,
    @SerializedName("OPENAI_API_KEY") val openaiApiKey: String? = null,
    val tokens: CodexTokens? = null,
    @SerializedName("last_refresh") val lastRefresh: String? = null,
)

data class CodexTokens(
    @SerializedName("id_token") val idToken: String? = null,
    @SerializedName("access_token") val accessToken: String? = null,
    @SerializedName("refresh_token") val refreshToken: String? = null,
    @SerializedName("account_id") val accountId: String? = null,
)

/**
 * Flat subset of the claims in an `id_token` JWT that the plugin uses:
 * the account email, the ChatGPT plan type, the workspace/account id, and the
 * FedRAMP routing flag. Mirrors codex's `IdTokenInfo`.
 */
data class IdTokenClaims(
    val email: String? = null,
    val chatgptPlanType: String? = null,
    val chatgptAccountId: String? = null,
    val isFedramp: Boolean = false,
) {
    companion object {
        private const val AUTH_CLAIM = "https://api.openai.com/auth"
        private const val PROFILE_CLAIM = "https://api.openai.com/profile"

        /**
         * Decode the interesting claims from an `id_token` JWT. Returns an
         * empty [IdTokenClaims] on any parse failure (missing parts, bad
         * base64, non-JSON payload) — callers treat absent claims as unknown.
         */
        fun parse(idToken: String?): IdTokenClaims {
            val payload = decodeJwtPayload(idToken) ?: return IdTokenClaims()
            return try {
                val auth = payload.getAsJsonObject(AUTH_CLAIM)
                val profile = payload.getAsJsonObject(PROFILE_CLAIM)
                IdTokenClaims(
                    email = payload.stringOrNull("email") ?: profile?.stringOrNull("email"),
                    chatgptPlanType = auth?.stringOrNull("chatgpt_plan_type"),
                    chatgptAccountId = auth?.stringOrNull("chatgpt_account_id"),
                    isFedramp = auth?.get("chatgpt_account_is_fedramp")
                        ?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
                )
            } catch (e: Exception) {
                TokenPulseLogger.Provider.debug("[IdTokenClaims] Failed to read claims: ${e.message}")
                IdTokenClaims()
            }
        }
    }
}

/**
 * The `exp` (expiry) claim of a JWT as epoch seconds, or null when the token
 * is malformed or carries no numeric `exp`. Used to decide whether the Codex
 * access token needs refreshing before we call the usage endpoint.
 */
internal fun jwtExpirationEpochSeconds(jwt: String?): Long? {
    val payload = decodeJwtPayload(jwt) ?: return null
    return payload.get("exp")?.takeIf { it.isJsonPrimitive }?.asLong
}

/**
 * Decode a JWT's middle segment (base64url, no padding) into a [JsonObject].
 * Returns null if the token is not a well-formed three-part JWT or the payload
 * is not JSON. No signature verification is performed — we only read claims
 * from a token the local Codex CLI already trusts.
 */
internal fun decodeJwtPayload(jwt: String?): JsonObject? {
    if (jwt.isNullOrBlank()) return null
    val parts = jwt.split('.')
    if (parts.size != 3 || parts.any { it.isEmpty() }) return null
    return try {
        val decoded = Base64.getUrlDecoder().decode(parts[1])
        val element = JsonParser.parseString(String(decoded, Charsets.UTF_8))
        if (element.isJsonObject) element.asJsonObject else null
    } catch (_: Exception) {
        null
    }
}

private fun JsonObject.stringOrNull(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

/** Shared Gson used to parse `auth.json` into [CodexAuthDotJson]. */
internal val codexAuthGson: Gson = Gson()
