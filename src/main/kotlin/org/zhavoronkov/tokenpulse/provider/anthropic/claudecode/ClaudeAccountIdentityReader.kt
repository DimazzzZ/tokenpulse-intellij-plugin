package org.zhavoronkov.tokenpulse.provider.anthropic.claudecode

import com.google.gson.JsonParser
import org.zhavoronkov.tokenpulse.utils.TokenPulseLogger

/**
 * Human-readable identity for a Claude Code account.
 *
 * These fields live in `<configDir?:~>/.claude.json` under the `oauthAccount`
 * key. The credential blob (Keychain / `.credentials.json`) does NOT contain
 * them; it holds only tokens + subscription metadata.
 */
data class ClaudeAccountIdentity(
    val emailAddress: String?,
    val organizationName: String?,
    val displayName: String?,
) {
    /** True when at least one identifying field is non-blank. */
    fun hasAny(): Boolean =
        !emailAddress.isNullOrBlank() ||
            !organizationName.isNullOrBlank() ||
            !displayName.isNullOrBlank()
}

/**
 * Reads `oauthAccount` from claude-code's global config JSON. Null-safe:
 * missing file, missing `oauthAccount`, malformed JSON, or non-object roots
 * all return `null` without throwing.
 */
object ClaudeAccountIdentityReader {

    /**
     * Read the identity for a given config dir (null => default `~/.claude`).
     *
     * @param configDir The `CLAUDE_CONFIG_DIR` value for the account, or null.
     * @param home Override HOME for tests; defaults to `user.home`.
     */
    fun read(
        configDir: String?,
        home: String = System.getProperty("user.home"),
    ): ClaudeAccountIdentity? {
        val file = ClaudeConfigLocator.identityFile(configDir, home)
        if (!file.exists()) return null
        return try {
            val element = JsonParser.parseString(file.readText(Charsets.UTF_8))
            if (!element.isJsonObject) return null
            val oauth = element.asJsonObject.getAsJsonObject("oauthAccount") ?: return null
            ClaudeAccountIdentity(
                emailAddress = oauth.get("emailAddress")?.takeIf { !it.isJsonNull }?.asString,
                organizationName = oauth.get("organizationName")?.takeIf { !it.isJsonNull }?.asString,
                displayName = oauth.get("displayName")?.takeIf { !it.isJsonNull }?.asString,
            )
        } catch (e: Exception) {
            TokenPulseLogger.Provider.warn(
                "[ClaudeAccountIdentityReader] Failed to read ${file.absolutePath}: ${e.message}"
            )
            null
        }
    }
}
