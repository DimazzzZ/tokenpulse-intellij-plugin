package org.zhavoronkov.tokenpulse.ui

import org.zhavoronkov.tokenpulse.provider.anthropic.claudecode.ClaudeCliExecutor
import org.zhavoronkov.tokenpulse.utils.TokenPulseLogger

class ClaudeConnectDialog : CliConnectDialog(
    CliDialogSpec(
        cliName = "Claude CLI",
        installUrl = "https://docs.anthropic.com/en/docs/claude-code/getting-started",
        headerHtml = "<html><b>Claude Code (Claude CLI)</b></html>",
        descriptionHtml = "<html>Claude Code uses the Claude CLI for authentication.<br>" +
            "No API key required - the CLI handles login automatically.</html>",
        requirementsHtml = "<html>1. Install Claude CLI: <code>npm install -g @anthropic-ai/claude-code</code><br>" +
            "2. Run <code>claude</code> in terminal once to log in<br>" +
            "3. The plugin will use CLI to fetch usage data</html>",
    )
) {

    init {
        startDetection()
    }

    override fun performDetection(): DetectionResult {
        val available = ClaudeCliExecutor.isClaudeCliAvailable()
        TokenPulseLogger.UI.info("Claude CLI available=$available")
        if (!available) {
            return DetectionResult(
                available = false,
                version = null,
                errorMessage = "Install via: npm install -g @anthropic-ai/claude-code"
            )
        }
        val claudePath = ClaudeCliExecutor.findClaudeCliPath()
        TokenPulseLogger.UI.info("Claude CLI path=$claudePath")
        val result = ClaudeCliExecutor.verifyClaudeCliWorks()
        TokenPulseLogger.UI.info("Claude CLI verifyResult: success=${result.first} output=${result.second}")
        val works = result.first
        val version = result.second
        return DetectionResult(
            available = available,
            version = if (works) version else null,
            errorMessage = if (works) "" else "CLI found but not responding: ${version ?: "unknown error"}"
        )
    }
}
