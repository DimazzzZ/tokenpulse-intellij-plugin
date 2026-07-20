package org.zhavoronkov.tokenpulse.provider.anthropic.claudecode

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.zhavoronkov.tokenpulse.utils.HostOs
import org.zhavoronkov.tokenpulse.utils.detectHostOs
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
 *
 * @param configDir The `CLAUDE_CONFIG_DIR` this reader targets. `null`/blank
 *   means the default dir (`~/.claude`, unsuffixed Keychain service). Each
 *   Claude Code account carries its own config dir, so the plugin constructs
 *   one reader per account rather than reading whatever is ambient.
 */
class ClaudeCredentialReader(
    private val configDir: String? = null,
    /**
     * OS whose credential store is consulted. Defaults to the real host OS;
     * tests override it (e.g. [HostOs.LINUX]) to force the
     * file-mode path and avoid the macOS Keychain subprocess.
     */
    private val osType: HostOs = detectHostOs(),
) {

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
        TokenPulseLogger.Provider.debug("[ClaudeCredentialReader] Reading credentials for OS: $osType")

        return when (osType) {
            HostOs.MACOS -> readFromKeychain()
            else -> readFromFile()
        }
    }

    /**
     * Read credentials from macOS Keychain for this reader's config dir.
     *
     * The service name is computed deterministically from the config dir
     * ([ClaudeConfigLocator.keychainServiceName]) so each account resolves to
     * its own entry. For the DEFAULT account only (`configDir` null/blank), if
     * the base entry is missing/empty we fall back to scanning all suffixed
     * `Claude Code-credentials-*` entries and taking the first valid one — this
     * preserves a pre-existing account whose entry happened to be suffixed
     * (e.g. an alias that set CLAUDE_CONFIG_DIR even for the primary login).
     */
    private fun readFromKeychain(): JsonObject? {
        return try {
            val username = getUsername()
            val serviceName = ClaudeConfigLocator.keychainServiceName(configDir)
            TokenPulseLogger.Provider.debug("[ClaudeCredentialReader] Reading Keychain service: $serviceName")

            val targeted = readKeychainEntry(serviceName, username)
            if (targeted != null && hasValidTokens(targeted)) {
                return targeted
            }

            // Fallback scan is default-account-only; explicit dirs stay isolated.
            if (!ClaudeConfigLocator.isDefault(configDir)) {
                TokenPulseLogger.Provider.debug("[ClaudeCredentialReader] No valid tokens for explicit dir entry: $serviceName")
                return null
            }

            TokenPulseLogger.Provider.debug("[ClaudeCredentialReader] Base entry empty; scanning suffixed entries (default account)")
            for (entryName in findSuffixedKeychainEntries()) {
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
        } catch (_: Exception) {
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

    private fun credentialsFile(): File = ClaudeConfigLocator.credentialsFile(configDir)

    private fun getUsername(): String {
        return System.getenv("USER")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("user.name")?.takeIf { it.isNotBlank() }
            ?: "claude-code-user"
    }
}
