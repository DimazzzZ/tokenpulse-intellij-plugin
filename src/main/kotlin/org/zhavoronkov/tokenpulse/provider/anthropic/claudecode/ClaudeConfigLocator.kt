package org.zhavoronkov.tokenpulse.provider.anthropic.claudecode

import java.io.File
import java.security.MessageDigest
import java.text.Normalizer

/**
 * Pure, dependency-free helpers that translate a `CLAUDE_CONFIG_DIR` into the
 * concrete storage locations claude-code uses.
 *
 * The rules mirror the reference implementation at
 * `claude-code/src/utils/secureStorage/macOsKeychainHelpers.ts` and
 * `envUtils.ts` / `plainTextStorage.ts`:
 *
 * - Default config dir (env unset) => `~/.claude`.
 * - macOS Keychain service name = `Claude Code-credentials` for the default
 *   dir, else `Claude Code-credentials-<first 8 hex of sha256(NFC(rawDir))>`.
 *   The hash input is the RAW config-dir string (NFC-normalized only, NOT
 *   absolutized) — matching how claude-code hashed it when it wrote the entry.
 * - Credentials file: `<configDir ?: ~/.claude>/.credentials.json`.
 * - Identity file:
 *     - default => `<home>/.claude.json` (in HOME, NOT inside `~/.claude/`).
 *     - non-default => `<configDir>/.claude.json`.
 *
 * All methods accept an injectable `home` so unit tests can control HOME
 * without touching the process environment.
 */
object ClaudeConfigLocator {

    const val KEYCHAIN_BASE_SERVICE_NAME: String = "Claude Code-credentials"

    /** The default config dir path (`~/.claude`) as a plain string. */
    fun defaultConfigDir(home: String = System.getProperty("user.home")): String =
        "$home/.claude"

    /**
     * True when the account has no explicit config dir OR the given dir
     * resolves to `~/.claude` by canonical-path equality. Decides whether the
     * Keychain lookup uses the base (unsuffixed) service name.
     */
    fun isDefault(configDir: String?, home: String = System.getProperty("user.home")): Boolean {
        if (configDir.isNullOrBlank()) return true
        return canonicalize(configDir) == canonicalize(defaultConfigDir(home))
    }

    /**
     * macOS Keychain service name for the given config dir.
     *
     * The hash input is the RAW `configDir` string (NFC-normalized only).
     * Do NOT absolutize/canonicalize before hashing — claude-code hashes the
     * literal env value, so anything else would produce a mismatched suffix.
     */
    fun keychainServiceName(
        configDir: String?,
        home: String = System.getProperty("user.home"),
    ): String {
        if (isDefault(configDir, home)) return KEYCHAIN_BASE_SERVICE_NAME
        val nfc = Normalizer.normalize(configDir!!, Normalizer.Form.NFC)
        val digest = MessageDigest.getInstance("SHA-256").digest(nfc.toByteArray(Charsets.UTF_8))
        val hex8 = digest.joinToString("") { "%02x".format(it) }.substring(0, HASH_HEX_LENGTH)
        return "$KEYCHAIN_BASE_SERVICE_NAME-$hex8"
    }

    /** Plaintext credentials file (used off-macOS or as a mac fallback). */
    fun credentialsFile(
        configDir: String?,
        home: String = System.getProperty("user.home"),
    ): File = File(resolvedConfigDir(configDir, home), ".credentials.json")

    /**
     * Global identity JSON that holds `oauthAccount`.
     *
     * Default config dir places this in HOME (`~/.claude.json`), NOT inside
     * `~/.claude/`. Non-default places it inside the config dir.
     */
    fun identityFile(
        configDir: String?,
        home: String = System.getProperty("user.home"),
    ): File =
        if (isDefault(configDir, home)) File(home, ".claude.json")
        else File(configDir!!, ".claude.json")

    private fun resolvedConfigDir(configDir: String?, home: String): String =
        if (configDir.isNullOrBlank()) defaultConfigDir(home) else configDir

    private fun canonicalize(path: String): String {
        val trimmed = path.trimEnd('/')
        return try {
            File(trimmed).canonicalPath
        } catch (_: Exception) {
            trimmed
        }
    }

    private const val HASH_HEX_LENGTH = 8
}
