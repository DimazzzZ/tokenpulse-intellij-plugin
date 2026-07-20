package org.zhavoronkov.tokenpulse.provider.anthropic.claudecode

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.zhavoronkov.tokenpulse.utils.TokenPulseLogger
import java.io.File

/**
 * Reads existing Claude Code credentials from local storage.
 *
 * Claude Code stores OAuth credentials in:
 * - macOS: Keychain with service name "Claude Code-credentials" (or a suffixed
 *   variant when CLAUDE_CONFIG_DIR is non-default)
 * - Other platforms: <CLAUDE_CONFIG_DIR ?? ~/.claude>/.credentials.json
 *   (plaintext, mode 0600)
 *
 * The credential structure contains a `claudeAiOauth` entry with:
 * - accessToken: the OAuth access token
 * - refreshToken: for refreshing expired tokens
 * - expiresAt: token expiration timestamp
 * - scopes: OAuth scopes
 *
 * This reader is strictly read-only. TokenPulse never writes back to Claude's
 * credential store: when a token is expired we refresh it and hold the new
 * access token in memory for the current call only, leaving the `claude` CLI
 * to own its own credential lifecycle.
 */
class ClaudeCredentialReader {

    /**
     * Read the OAuth access token from Claude Code's credential store.
     *
     * @return The access token, or null if credentials not found or unreadable
     */
    fun readAccessToken(): String? {
        return try {
            val credentials = readCredentials() ?: return null
            val oauthData = credentials.getAsJsonObject("claudeAiOauth") ?: return null
            val token = oauthData.get("accessToken")?.asString

            if (token.isNullOrBlank()) {
                TokenPulseLogger.Provider.debug("[ClaudeCredentialReader] accessToken is null or blank")
                null
            } else {
                TokenPulseLogger.Provider.debug("[ClaudeCredentialReader] Successfully read accessToken (length=${token.length})")
                token
            }
        } catch (e: Exception) {
            TokenPulseLogger.Provider.warn("[ClaudeCredentialReader] Failed to read access token: ${e.message}")
            null
        }
    }

    /**
     * Read the refresh token from Claude Code's credential store.
     *
     * @return The refresh token, or null if not found
     */
    fun readRefreshToken(): String? {
        return try {
            val credentials = readCredentials() ?: return null
            val oauthData = credentials.getAsJsonObject("claudeAiOauth") ?: return null
            oauthData.get("refreshToken")?.asString?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            TokenPulseLogger.Provider.warn("[ClaudeCredentialReader] Failed to read refresh token: ${e.message}")
            null
        }
    }

    /**
     * Read the token expiration timestamp.
     *
     * @return Unix timestamp in milliseconds, or null if not found
     */
    fun readExpiresAt(): Long? {
        return try {
            val credentials = readCredentials() ?: return null
            val oauthData = credentials.getAsJsonObject("claudeAiOauth") ?: return null
            oauthData.get("expiresAt")?.asLong
        } catch (e: Exception) {
            TokenPulseLogger.Provider.warn("[ClaudeCredentialReader] Failed to read expiresAt: ${e.message}")
            null
        }
    }

    /**
     * Check if the access token has expired.
     *
     * @return true if expired or expiration unknown, false if still valid
     */
    fun isTokenExpired(): Boolean {
        val expiresAt = readExpiresAt() ?: return true
        val now = System.currentTimeMillis()
        val expired = now >= expiresAt
        if (expired) {
            TokenPulseLogger.Provider.debug("[ClaudeCredentialReader] Token expired (expiresAt=$expiresAt, now=$now)")
        }
        return expired
    }

    /**
     * Read credentials from the appropriate platform-specific store.
     */
    private fun readCredentials(): JsonObject? {
        val os = ClaudeCliExecutor.getOsType()
        TokenPulseLogger.Provider.debug("[ClaudeCredentialReader] Reading credentials for OS: $os")

        return when (os) {
            ClaudeCliExecutor.OsType.MACOS -> readFromKeychain()
            else -> readFromFile()
        }
    }

    /**
     * Read credentials from macOS Keychain.
     *
     * Claude Code uses suffixed Keychain service names like "Claude Code-credentials-6592cac2"
     * in addition to the base "Claude Code-credentials". This method tries the base name first,
     * then searches for suffixed entries if the base has empty tokens.
     */
    private fun readFromKeychain(): JsonObject? {
        return try {
            TokenPulseLogger.Provider.debug("[ClaudeCredentialReader] Reading from Keychain")
            val username = getUsername()

            // Try base service name first
            val baseResult = readKeychainEntry(KEYCHAIN_SERVICE_NAME, username)
            if (baseResult != null && hasValidTokens(baseResult)) {
                TokenPulseLogger.Provider.debug("[ClaudeCredentialReader] Found valid tokens in base Keychain entry")
                return baseResult
            }

            // Base entry has empty tokens or doesn't exist, search for suffixed entries
            TokenPulseLogger.Provider.debug("[ClaudeCredentialReader] Searching for suffixed Keychain entries")
            val suffixedEntries = findSuffixedKeychainEntries()

            for (entryName in suffixedEntries) {
                val result = readKeychainEntry(entryName, username)
                if (result != null && hasValidTokens(result)) {
                    TokenPulseLogger.Provider.debug("[ClaudeCredentialReader] Found valid tokens in suffixed entry: $entryName")
                    return result
                }
            }

            TokenPulseLogger.Provider.debug("[ClaudeCredentialReader] No Keychain entry with valid tokens found")
            null
        } catch (e: Exception) {
            TokenPulseLogger.Provider.warn("[ClaudeCredentialReader] Keychain read failed: ${e.message}")
            null
        }
    }

    /**
     * Read a specific Keychain entry by service name (scoped to the current user).
     */
    private fun readKeychainEntry(serviceName: String, username: String): JsonObject? {
        return try {
            val process = ProcessBuilder(
                "security", "find-generic-password",
                "-a", username,
                "-s", serviceName,
                "-w"
            )
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()

            if (exitCode != 0 || output.isBlank()) {
                return null
            }

            parseCredentialJson(output)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Find all suffixed Keychain entries matching "Claude Code-credentials-*".
     */
    private fun findSuffixedKeychainEntries(): List<String> {
        return try {
            val process = ProcessBuilder(
                "security", "dump-keychain"
            )
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            // Parse output to find service names matching "Claude Code-credentials-*"
            val pattern = Regex(""""Claude Code-credentials-[^"]*"""")
            pattern.findAll(output)
                .map { it.value.removeSurrounding("\"") }
                .distinct()
                .toList()
        } catch (e: Exception) {
            TokenPulseLogger.Provider.warn("[ClaudeCredentialReader] Failed to search Keychain entries: ${e.message}")
            emptyList()
        }
    }

    /**
     * Check if a credential JSON has valid (non-empty) tokens.
     */
    private fun hasValidTokens(credentials: JsonObject): Boolean {
        val oauthData = credentials.getAsJsonObject("claudeAiOauth") ?: return false
        val accessToken = oauthData.get("accessToken")?.asString
        return !accessToken.isNullOrBlank()
    }

    /**
     * Read credentials from plaintext file. Path matches claude-code's
     * `<CLAUDE_CONFIG_DIR ?? ~/.claude>/.credentials.json`.
     */
    private fun readFromFile(): JsonObject? {
        return try {
            val credFile = credentialsFile()

            if (!credFile.exists()) {
                TokenPulseLogger.Provider.debug("[ClaudeCredentialReader] Credentials file not found: ${credFile.absolutePath}")
                return null
            }

            TokenPulseLogger.Provider.debug("[ClaudeCredentialReader] Reading from file: ${credFile.absolutePath}")
            val content = credFile.readText(Charsets.UTF_8)
            parseCredentialJson(content)
        } catch (e: Exception) {
            TokenPulseLogger.Provider.warn("[ClaudeCredentialReader] File read failed: ${e.message}")
            null
        }
    }

    /**
     * Parse the credential JSON string.
     */
    private fun parseCredentialJson(json: String): JsonObject? {
        return try {
            val element = JsonParser.parseString(json)
            when {
                element.isJsonObject -> element.asJsonObject
                else -> {
                    TokenPulseLogger.Provider.warn("[ClaudeCredentialReader] Unexpected JSON type: ${element.javaClass.simpleName}")
                    null
                }
            }
        } catch (e: Exception) {
            TokenPulseLogger.Provider.warn("[ClaudeCredentialReader] JSON parse failed: ${e.message}")
            null
        }
    }

    private fun credentialsFile(): File {
        val configDir = System.getenv("CLAUDE_CONFIG_DIR")?.takeIf { it.isNotBlank() }
            ?: "${System.getProperty("user.home")}/.claude"
        return File(configDir, ".credentials.json")
    }

    private fun getUsername(): String {
        return System.getenv("USER")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("user.name")?.takeIf { it.isNotBlank() }
            ?: "claude-code-user"
    }

    companion object {
        /** macOS Keychain service name for Claude Code credentials. */
        private const val KEYCHAIN_SERVICE_NAME = "Claude Code-credentials"
    }
}
