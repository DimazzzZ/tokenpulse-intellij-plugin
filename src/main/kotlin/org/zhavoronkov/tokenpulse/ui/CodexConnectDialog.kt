package org.zhavoronkov.tokenpulse.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.panel
import org.zhavoronkov.tokenpulse.provider.openai.chatgpt.CodexCliStatusExtractor
import java.awt.Font
import javax.swing.JButton
import javax.swing.JComponent

/**
 * Dialog to connect Codex CLI account.
 *
 * Codex CLI uses the Codex CLI which handles its own authentication.
 * This dialog checks if the CLI is installed and provides setup instructions.
 */
class CodexConnectDialog : DialogWrapper(true) {

    companion object {
        private const val CODEX_CLI_INSTALL_URL = "https://github.com/openai/codex"

        private const val STATUS_DETECTING = "<html><i>Detecting Codex CLI...</i></html>"
        private const val STATUS_FOUND = "<html><font color='green'><b>✓ Codex CLI detected</b></font></html>"
        private const val STATUS_NOT_FOUND = "<html><font color='orange'><b>⚠ Codex CLI not found</b></font></html>"
    }

    /** Whether Codex CLI was detected successfully */
    var cliDetected: Boolean = false
        private set

    /** Codex CLI version if detected */
    var cliVersion: String? = null
        private set

    private val statusLabel = JBLabel(STATUS_DETECTING)
    private val versionLabel = JBLabel("")

    private val detectButton = JButton("Re-detect").apply {
        addActionListener { detectCli() }
    }

    private val installButton = JButton("Installation Guide →").apply {
        addActionListener { BrowserUtil.browse(CODEX_CLI_INSTALL_URL) }
    }

    init {
        title = "Connect Codex CLI"
        setOKButtonText("Add Account")
        isOKActionEnabled = false
        init()

        // Detect CLI on dialog open
        detectCli()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            label("<html><b>Codex CLI (OpenAI Codex)</b></html>")
        }

        row {
            comment(
                "<html>Codex CLI uses the Codex CLI for authentication.<br>" +
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
                "<html>1. Install Codex CLI: <code>npm install -g @openai/codex</code><br>" +
                    "2. Run <code>codex login</code> in terminal once to log in<br>" +
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

        val extractor = CodexCliStatusExtractor()

        // Check if CLI is available
        if (!extractor.isCodexCliAvailable()) {
            statusLabel.text = STATUS_NOT_FOUND
            versionLabel.text = "<html><font color='gray'>" +
                "Install via: npm install -g @openai/codex</font></html>"
            cliDetected = false
            cliVersion = null
            return
        }

        // Get CLI version
        val version = extractor.getCodexCliVersion()

        if (version != null) {
            statusLabel.text = STATUS_FOUND
            versionLabel.text = "<html><font color='gray'>Version: $version</font></html>"
            versionLabel.font = versionLabel.font.deriveFont(Font.PLAIN)
            cliDetected = true
            cliVersion = version
            isOKActionEnabled = true
        } else {
            statusLabel.text = STATUS_NOT_FOUND
            versionLabel.text = "<html><font color='gray'>" +
                "CLI found but version detection failed</font></html>"
            cliDetected = false
            cliVersion = null
        }
    }
}
