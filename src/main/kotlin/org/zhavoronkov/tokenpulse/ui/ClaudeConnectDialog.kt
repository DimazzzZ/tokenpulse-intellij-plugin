package org.zhavoronkov.tokenpulse.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.panel
import org.zhavoronkov.tokenpulse.provider.anthropic.claudecode.ClaudeCliExecutor
import java.awt.Font
import javax.swing.JButton
import javax.swing.JComponent

/**
 * Dialog to connect Claude Code account.
 *
 * Claude Code uses the Claude CLI which handles its own authentication.
 * This dialog checks if the CLI is installed and provides setup instructions.
 */
class ClaudeConnectDialog : DialogWrapper(true) {

    companion object {
        private const val CLAUDE_CLI_INSTALL_URL = "https://docs.anthropic.com/en/docs/claude-code/getting-started"

        private const val STATUS_DETECTING = "<html><i>Detecting Claude CLI...</i></html>"
        private const val STATUS_FOUND = "<html><font color='green'><b>✓ Claude CLI detected</b></font></html>"
        private const val STATUS_NOT_FOUND = "<html><font color='orange'><b>⚠ Claude CLI not found</b></font></html>"
    }

    /** Whether Claude CLI was detected successfully */
    var cliDetected: Boolean = false
        private set

    /** Claude CLI version if detected */
    var cliVersion: String? = null
        private set

    private val statusLabel = JBLabel(STATUS_DETECTING)
    private val versionLabel = JBLabel("")

    private val detectButton = JButton("Re-detect").apply {
        addActionListener { detectCli() }
    }

    private val installButton = JButton("Installation Guide →").apply {
        addActionListener { BrowserUtil.browse(CLAUDE_CLI_INSTALL_URL) }
    }

    init {
        title = "Connect Claude Code"
        setOKButtonText("Add Account")
        isOKActionEnabled = false
        init()

        // Detect CLI on dialog open
        detectCli()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            label("<html><b>Claude Code (Claude CLI)</b></html>")
        }

        row {
            comment(
                "<html>Claude Code uses the Claude CLI for authentication.<br>" +
                    "No API key required - the CLI handles login automatically.</html>"
            )
        }

        separator()

        row {
            label("Status:")
            cell(statusLabel)
        }

        row {
            cell(versionLabel)
        }

        separator()

        row {
            label("<html><b>Requirements:</b></html>")
        }

        row {
            comment(
                "<html>1. Install Claude CLI: <code>npm install -g @anthropic-ai/claude-code</code><br>" +
                    "2. Run <code>claude</code> in terminal once to log in<br>" +
                    "3. The plugin will use CLI to fetch usage data</html>"
            )
        }

        separator()

        row {
            cell(detectButton)
            cell(installButton)
        }
    }

    override fun getPreferredFocusedComponent() = detectButton

    private fun detectCli() {
        statusLabel.text = STATUS_DETECTING
        versionLabel.text = ""
        isOKActionEnabled = false

        // Check if CLI is available
        if (!ClaudeCliExecutor.isClaudeCliAvailable()) {
            statusLabel.text = STATUS_NOT_FOUND
            versionLabel.text = "<html><font color='gray'>" +
                "Install via: npm install -g @anthropic-ai/claude-code</font></html>"
            cliDetected = false
            cliVersion = null
            return
        }

        // Verify CLI works and get version
        val (works, version) = ClaudeCliExecutor.verifyClaudeCliWorks()

        if (works && version != null) {
            statusLabel.text = STATUS_FOUND
            versionLabel.text = "<html><font color='gray'>Version: $version</font></html>"
            versionLabel.font = versionLabel.font.deriveFont(Font.PLAIN)
            cliDetected = true
            cliVersion = version
            isOKActionEnabled = true
        } else {
            statusLabel.text = STATUS_NOT_FOUND
            val errorMsg = version ?: "unknown error"
            versionLabel.text = "<html><font color='gray'>" +
                "CLI found but not responding: $errorMsg</font></html>"
            cliDetected = false
            cliVersion = null
        }
    }
}
