package org.zhavoronkov.tokenpulse.provider.openai.chatgpt.oauth

import java.io.File

/**
 * Resolves the on-disk location of the Codex CLI's `auth.json`.
 *
 * Codex stores its credentials at `$CODEX_HOME/auth.json`, where `CODEX_HOME`
 * defaults to `~/.codex`. There is no documented multi-account/multi-directory
 * discovery mechanism (unlike Claude Code's `CLAUDE_CONFIG_DIR` fan-out), so
 * the plugin resolves a single credential file — honoring `CODEX_HOME` when the
 * user sets it, otherwise `~/.codex`.
 */
object CodexConfigLocator {

    private const val CODEX_HOME_ENV = "CODEX_HOME"
    private const val DEFAULT_DIR_NAME = ".codex"
    private const val AUTH_FILE_NAME = "auth.json"

    /**
     * The Codex home directory: `$CODEX_HOME` if set and non-blank, else
     * `<userHome>/.codex`.
     */
    fun codexHome(
        env: Map<String, String> = System.getenv(),
        userHome: String = System.getProperty("user.home"),
    ): File {
        val override = env[CODEX_HOME_ENV]?.takeIf { it.isNotBlank() }
        return if (override != null) File(override) else File(userHome, DEFAULT_DIR_NAME)
    }

    /**
     * The `auth.json` file inside [codexHome].
     */
    fun authFile(
        env: Map<String, String> = System.getenv(),
        userHome: String = System.getProperty("user.home"),
    ): File = File(codexHome(env, userHome), AUTH_FILE_NAME)
}
