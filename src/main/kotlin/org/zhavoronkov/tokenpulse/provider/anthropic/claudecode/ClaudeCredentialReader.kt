package org.zhavoronkov.tokenpulse.provider.anthropic.claudecode

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.zhavoronkov.tokenpulse.provider.oauth.OAuthCredentialStore
import org.zhavoronkov.tokenpulse.provider.oauth.isTokenExpired
import org.zhavoronkov.tokenpulse.utils.HostOs
import org.zhavoronkov.tokenpulse.utils.TokenPulseLogger
import org.zhavoronkov.tokenpulse.utils.detectHostOs
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
 * The reader also supports best-effort WRITE-BACK ([writeTokens]): after
 * TokenPulse refreshes an expired OAuth token, it persists the rotated tokens
 * back into Claude's credential store so the running `claude` CLI keeps a
 * valid refresh token and does not get logged out. Write failures never block
 * using the fresh token in-memory for the current call.
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
                logDebug("accessToken is null or blank")
                null
            } else {
                logDebug("Successfully read accessToken (length=${token.length})")
                token
            }
        } catch (e: Exception) {
            logWarn("Failed to read access token: ${e.message}")
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
            logWarn("Failed to read refresh token: ${e.message}")
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
            logWarn("Failed to read expiresAt: ${e.message}")
            null
        }
    }

    /**
     * Check if the access token has expired.
     *
     * @return true only when we can prove the token is expired (a parseable
     *   `expiresAt` in the past, minus a small skew buffer). When `expiresAt`
     *   is missing or unparseable we return false ("unknown => assume usable")
     *   and let the usage API's real 401 be the authoritative signal — this
     *   avoids reporting a logged-in user as expired on a schema/parse hiccup.
     */
    fun isTokenExpired(): Boolean {
        val expiresAt = readExpiresAt()
        val now = System.currentTimeMillis()
        val expired = isClaudeTokenExpired(expiresAt, now)
        if (expired) {
            logDebug("Token expired (expiresAt=$expiresAt, now=$now, skewMs=$CLAUDE_TOKEN_EXPIRY_SKEW_MS)")
        }
        return expired
    }

    /**
     * Persist rotated tokens back into Claude's credential store so the running
     * `claude` CLI keeps a valid refresh token and does not get logged out.
     *
     * Non-destructive: the full credential JSON is round-tripped and only the
     * rotating fields (`accessToken`, `refreshToken`, `expiresAt`) are mutated,
     * preserving every other key Claude Code sets. Best-effort: write failures
     * are logged and reported via the return value but never throw.
     *
     * Targets the SAME store the read resolved from. On macOS this means the
     * resolved Keychain entry AND the plaintext `.credentials.json` if it also
     * exists; off-macOS it is the plaintext file only.
     *
     * @return true iff at least one target was written successfully.
     */
    fun writeTokens(accessToken: String, refreshToken: String?, expiresAt: Long?): Boolean {
        val readResult = readCredentialsWithSource()
        if (readResult == null) {
            logWarn("Cannot write back: existing credentials unreadable")
            return false
        }
        val (root, source) = readResult
        val oauth = root.getAsJsonObject("claudeAiOauth")
            ?: JsonObject().also { root.add("claudeAiOauth", it) }
        oauth.addProperty("accessToken", accessToken)
        refreshToken?.takeIf { it.isNotBlank() }?.let { oauth.addProperty("refreshToken", it) }
        expiresAt?.let { oauth.addProperty("expiresAt", it) }
        val json = root.toString()

        return when (osType) {
            HostOs.MACOS -> writeMacOs(json, source)
            else -> runCatching { writeToFile(json) }
                .onFailure { logWarn("File write-back failed: ${it.message}") }
                .isSuccess
        }
    }

    /**
     * macOS write-back: update the resolved Keychain entry, and also the
     * plaintext file when one exists. Each target is independent and
     * best-effort; success is true if either write succeeds.
     */
    private fun writeMacOs(json: String, source: CredentialSource): Boolean {
        val serviceName = (source as? CredentialSource.Keychain)?.serviceName
            ?: ClaudeConfigLocator.keychainServiceName(configDir)
        var wrote = runCatching { writeToKeychain(serviceName, json) }
            .onFailure { logWarn("Keychain write-back failed: ${it.message}") }
            .isSuccess
        if (credentialsFile().exists()) {
            runCatching { writeToFile(json) }
                .onFailure { logWarn("Plaintext write-back failed: ${it.message}") }
                .onSuccess { wrote = true }
        }
        return wrote
    }

    /**
     * Write the credential blob to a macOS Keychain entry. Recipe mirrors
     * claude-code's `secureStorage/macOsKeychainStorage.ts`: pipe an
     * `add-generic-password -U -a <user> -s <service> -X <hex>` command to
     * `security -i` on stdin, where `<hex>` is the UTF-8 JSON hex-encoded. The
     * `-U` flag updates the existing entry in place.
     */
    private fun writeToKeychain(serviceName: String, json: String) {
        val hex = json.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
        val stdin = "add-generic-password -U -a \"${getUsername()}\" -s \"$serviceName\" -X \"$hex\"\n"
        val process = ProcessBuilder("security", "-i").redirectErrorStream(true).start()
        process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(stdin) }
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            error("security exit $exitCode: ${output.take(200)}")
        }
        logDebug("Persisted refreshed tokens to Keychain service: $serviceName")
    }

    /**
     * Write the credential blob to the plaintext `.credentials.json` file
     * atomically (temp + move), with owner-only (0600) permissions.
     */
    private fun writeToFile(json: String) {
        val target = credentialsFile()
        OAuthCredentialStore.writeAtomic(target, json, tmpPrefix = ".credentials")
        logDebug("Persisted refreshed tokens to ${target.absolutePath}")
    }

    /**
     * Read credentials from the appropriate platform-specific store.
     */
    private fun readCredentials(): JsonObject? {
        return readCredentialsWithSource()?.first
    }

    /**
     * Read credentials AND record where they resolved from, so a subsequent
     * [writeTokens] can target the same store/entry (e.g. a default account
     * whose token lives in a suffixed Keychain entry via the fallback scan).
     */
    private fun readCredentialsWithSource(): Pair<JsonObject, CredentialSource>? {
        logDebug("Reading credentials for OS: $osType")

        return when (osType) {
            HostOs.MACOS -> readFromKeychainWithSource()
            else -> readFromFile()?.let { it to CredentialSource.PlaintextFile }
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
    private fun readFromKeychainWithSource(): Pair<JsonObject, CredentialSource>? {
        return try {
            val username = getUsername()
            val serviceName = ClaudeConfigLocator.keychainServiceName(configDir)
            logDebug("Reading Keychain service: $serviceName")

            val targeted = readKeychainEntry(serviceName, username)
            if (targeted != null && hasValidTokens(targeted)) {
                return targeted to CredentialSource.Keychain(serviceName)
            }

            // Fallback scan is default-account-only; explicit dirs stay isolated.
            if (!ClaudeConfigLocator.isDefault(configDir)) {
                logDebug("No valid tokens for explicit dir entry: $serviceName")
                return null
            }

            logDebug("Base entry empty; scanning suffixed entries (default account)")
            for (entryName in findSuffixedKeychainEntries()) {
                val result = readKeychainEntry(entryName, username)
                if (result != null && hasValidTokens(result)) {
                    logDebug("Found valid tokens in suffixed entry: $entryName")
                    return result to CredentialSource.Keychain(entryName)
                }
            }

            logDebug("No Keychain entry with valid tokens found")
            null
        } catch (e: Exception) {
            logWarn("Keychain read failed: ${e.message}")
            null
        }
    }

    /**
     * Read a specific Keychain entry by service name (scoped to the current user).
     */
    private fun readKeychainEntry(serviceName: String, username: String): JsonObject? {
        return try {
            val process = ProcessBuilder(
                "security",
                "find-generic-password",
                "-a",
                username,
                "-s",
                serviceName,
                "-w",
            ).redirectErrorStream(true).start()

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
            val process = ProcessBuilder("security", "dump-keychain")
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
            logWarn("Failed to search Keychain entries: ${e.message}")
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
                logDebug("Credentials file not found: ${credFile.absolutePath}")
                return null
            }

            logDebug("Reading from file: ${credFile.absolutePath}")
            val content = credFile.readText(Charsets.UTF_8)
            parseCredentialJson(content)
        } catch (e: Exception) {
            logWarn("File read failed: ${e.message}")
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
                    logWarn("Unexpected JSON type: ${element.javaClass.simpleName}")
                    null
                }
            }
        } catch (e: Exception) {
            logWarn("JSON parse failed: ${e.message}")
            null
        }
    }

    private fun credentialsFile(): File = ClaudeConfigLocator.credentialsFile(configDir)

    private fun getUsername(): String {
        return System.getenv("USER")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("user.name")?.takeIf { it.isNotBlank() }
            ?: "claude-code-user"
    }

    private fun logDebug(msg: String) = TokenPulseLogger.Provider.debug("[ClaudeCredentialReader] $msg")
    private fun logWarn(msg: String) = TokenPulseLogger.Provider.warn("[ClaudeCredentialReader] $msg")

    /**
     * Where a successful credential read resolved from, so [writeTokens] can
     * target the same place rather than a recomputed (possibly wrong) entry.
     */
    private sealed interface CredentialSource {
        data class Keychain(val serviceName: String) : CredentialSource
        object PlaintextFile : CredentialSource
    }
}

/**
 * Grace buffer applied before an access token's expiry. We proactively refresh
 * up to this long before the real expiry to absorb minor clock skew between the
 * host and Anthropic and to avoid using a token in its final moments.
 */
internal const val CLAUDE_TOKEN_EXPIRY_SKEW_MS = 60_000L

/**
 * Pure expiry decision, extracted so it can be unit-tested without a live
 * credential store.
 *
 * - `expiresAt == null` (missing/unparseable) => `false`: we cannot prove the
 *   token is expired, so treat it as usable and let the usage API's 401 be the
 *   authoritative signal.
 * - Otherwise expired when `now >= expiresAt - [CLAUDE_TOKEN_EXPIRY_SKEW_MS]`.
 *
 * Both timestamps are Unix epoch milliseconds.
 */
internal fun isClaudeTokenExpired(expiresAt: Long?, now: Long): Boolean {
    return isTokenExpired(expiresAt, now, CLAUDE_TOKEN_EXPIRY_SKEW_MS)
}
