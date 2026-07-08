package org.zhavoronkov.tokenpulse.ui

import org.zhavoronkov.tokenpulse.provider.openai.chatgpt.CodexCliExecutor

class CodexConnectDialog : CliConnectDialog(
    CliDialogSpec(
        cliName = "Codex CLI",
        installUrl = "https://github.com/openai/codex",
        headerHtml = "<html><b>Codex CLI (OpenAI Codex)</b></html>",
        descriptionHtml = "<html>Codex CLI uses the Codex CLI for authentication.<br>" +
            "No API key required - the CLI handles login automatically.</html>",
        requirementsHtml = "<html>1. Install Codex CLI: <code>npm install -g @openai/codex</code><br>" +
            "2. Run <code>codex login</code> in terminal once to log in<br>" +
            "3. The plugin will use CLI to fetch usage data</html>",
    )
) {

    init {
        startDetection()
    }

    override fun performDetection(): DetectionResult {
        val available = CodexCliExecutor.isCodexCliAvailable()
        val version = if (available) CodexCliExecutor.getCodexCliVersion() else null
        return DetectionResult(
            available = available,
            version = version,
            errorMessage = if (available) {
                "CLI found but version detection failed"
            } else {
                "Install via: npm install -g @openai/codex"
            }
        )
    }
}
