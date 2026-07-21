package org.zhavoronkov.tokenpulse.provider.anthropic.claudecode

import org.zhavoronkov.tokenpulse.settings.isAutoNamedClaudeOrg
import org.zhavoronkov.tokenpulse.utils.TokenPulseLogger
import java.io.File

/**
 * One Claude Code account the plugin could offer to add.
 *
 * @property configDir The `CLAUDE_CONFIG_DIR` string for this account, or
 *   `null` when it is the default (`~/.claude`).
 * @property isDefault True when [configDir] represents the default dir.
 * @property label A user-facing label (email/org, else displayName, else the
 *   config dir basename).
 * @property identity The parsed `oauthAccount` from `.claude.json`, if any.
 * @property hasValidCreds True when a credential probe returned a token.
 */
data class DiscoveredClaudeAccount(
    val configDir: String?,
    val isDefault: Boolean,
    val label: String,
    val identity: ClaudeAccountIdentity?,
    val hasValidCreds: Boolean,
)

/**
 * Enumerates candidate Claude config dirs on the local machine and probes
 * each for a working credential store and identity.
 *
 * There is no on-disk registry of `CLAUDE_CONFIG_DIR` values a user has used,
 * so discovery is a scan+probe: list dirs under `$HOME` that look like Claude
 * config dirs (`~/.claude`, `~/.claude-*`, `~/.config/claude*`), read each
 * one's identity JSON, and ask a [ClaudeCredentialReader] whether valid tokens
 * exist for that dir. All heavy I/O is intended to run off the EDT.
 */
object ClaudeAccountDiscovery {

    /**
     * Candidate directories that might be Claude config dirs. Absolute paths,
     * de-duplicated. Missing HOME yields an empty list.
     */
    fun candidateDirs(home: String = System.getProperty("user.home")): List<String> {
        val out = linkedSetOf<String>()
        val homeDir = File(home)

        homeDir.listFiles { f ->
            f.isDirectory && (f.name == ".claude" || f.name.startsWith(".claude-"))
        }?.forEach { out.add(it.absolutePath) }

        File(homeDir, ".config").listFiles { f ->
            f.isDirectory && f.name.startsWith("claude")
        }?.forEach { out.add(it.absolutePath) }

        return out.toList()
    }

    /**
     * Discover Claude accounts on the local machine.
     *
     * @param home Override HOME (tests).
     * @param credProbe Injected probe: given the `configDir` we would store on
     *   the [org.zhavoronkov.tokenpulse.settings.Account], return true when the
     *   store contains a usable access token. Default probe constructs a
     *   [ClaudeCredentialReader] for the dir and calls `readAccessToken()`.
     */
    fun discover(
        home: String = System.getProperty("user.home"),
        credProbe: (String?) -> Boolean = ::defaultCredProbe,
    ): List<DiscoveredClaudeAccount> {
        val dirs = candidateDirs(home)
        TokenPulseLogger.Provider.debug("[ClaudeAccountDiscovery] Candidate dirs: $dirs")
        return dirs
            .map { dir -> probe(dir, home, credProbe) }
            .distinctBy { it.configDir }
    }

    /**
     * Probe an explicitly-supplied config dir (e.g. from the manual-add field
     * in the UI). The raw string is stored on the resulting account exactly
     * as passed in — do NOT canonicalize before calling if you want the
     * Keychain suffix hash to match how the user's alias set it.
     */
    fun probe(
        rawDir: String,
        home: String = System.getProperty("user.home"),
        credProbe: (String?) -> Boolean = ::defaultCredProbe,
    ): DiscoveredClaudeAccount {
        val isDefault = ClaudeConfigLocator.isDefault(rawDir, home)
        val storedDir = if (isDefault) null else rawDir
        val identity = ClaudeAccountIdentityReader.read(storedDir, home)
        return DiscoveredClaudeAccount(
            configDir = storedDir,
            isDefault = isDefault,
            label = labelFor(identity, rawDir),
            identity = identity,
            hasValidCreds = try {
                credProbe(storedDir)
            } catch (e: Exception) {
                TokenPulseLogger.Provider.warn("[ClaudeAccountDiscovery] Cred probe threw for $rawDir: ${e.message}")
                false
            },
        )
    }

    /**
     * Build a display label from an optional identity, falling back to the
     * config dir basename when identity is absent or empty.
     */
    fun labelFor(identity: ClaudeAccountIdentity?, dir: String): String {
        val email = identity?.emailAddress?.takeIf { it.isNotBlank() }
        val org = identity?.organizationName?.takeIf { it.isNotBlank() }
        val display = identity?.displayName?.takeIf { it.isNotBlank() }
        return when {
            // Anthropic auto-names a personal org "<email>'s Organization",
            // which would repeat the email in the label. Drop the org in that
            // case; keep it for real/custom org names.
            email != null && org != null && !isAutoNamedClaudeOrg(email, org) -> "$email • $org"
            email != null -> email
            display != null -> display
            else -> File(dir).name.ifBlank { dir }
        }
    }

    /** Default probe: does the credential store for [configDir] yield a token? */
    fun defaultCredProbe(configDir: String?): Boolean =
        ClaudeCredentialReader(configDir).readAccessToken() != null
}
