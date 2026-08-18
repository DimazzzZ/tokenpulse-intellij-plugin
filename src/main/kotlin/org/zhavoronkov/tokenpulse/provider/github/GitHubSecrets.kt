package org.zhavoronkov.tokenpulse.provider.github

import com.google.gson.Gson
import com.google.gson.JsonObject

/**
 * Parsed personal-billing secret for [GitHubCopilotProviderClient].
 *
 * Serialized form (stored in PasswordSafe): {"pat":"...","username":"..."}
 */
internal data class GitHubCopilotSecret(val pat: String, val username: String)

/**
 * Parsed org-billing secret for [GitHubCopilotBudgetProviderClient].
 *
 * Serialized form (stored in PasswordSafe): {"pat":"...","org":"..."}
 */
internal data class GitHubOrgBudgetSecret(val pat: String, val org: String)

/**
 * Serialize/parse helpers for the GitHub connect-dialog secret blobs.
 *
 * The connect dialogs pack the PAT together with the username/org (both
 * required to build the REST endpoint path) into a small JSON object. The
 * provider clients unpack it here. Parsing is defensive: a missing or blank
 * field yields null so the caller can surface a clean auth error rather than
 * throwing.
 */
internal object GitHubSecrets {
    private val gson = Gson()

    fun encodeCopilot(pat: String, username: String): String =
        gson.toJson(mapOf("pat" to pat, "username" to username))

    fun encodeOrgBudget(pat: String, org: String): String =
        gson.toJson(mapOf("pat" to pat, "org" to org))

    fun parseCopilot(secret: String): GitHubCopilotSecret? {
        val obj = parseObject(secret) ?: return null
        val pat = obj.stringOrNull("pat") ?: return null
        val username = obj.stringOrNull("username") ?: return null
        return GitHubCopilotSecret(pat, username)
    }

    fun parseOrgBudget(secret: String): GitHubOrgBudgetSecret? {
        val obj = parseObject(secret) ?: return null
        val pat = obj.stringOrNull("pat") ?: return null
        val org = obj.stringOrNull("org") ?: return null
        return GitHubOrgBudgetSecret(pat, org)
    }

    private fun parseObject(secret: String): JsonObject? =
        try {
            gson.fromJson(secret, JsonObject::class.java)
        } catch (_: Exception) {
            null
        }

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
}
