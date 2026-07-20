package org.zhavoronkov.tokenpulse.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.panel
import org.zhavoronkov.tokenpulse.provider.anthropic.claudecode.ClaudeCliExecutor
import org.zhavoronkov.tokenpulse.utils.TokenPulseLogger
import javax.swing.JButton
import javax.swing.JComponent

/**
 * Dialog for connecting Claude Code.
 *
 * Fully automatic: detects installed Claude CLI and reads existing credentials.
 * No manual token input required — uses existing Claude Code login.
 */
class ClaudeConnectDialog : DialogWrapper(true) {

    companion object {
        private const val STATUS_DETECTING = "<html><i>Detecting Claude CLI...</i></html>"
        private const val STATUS_CLI_FOUND = "<html><font color='green'><b>✓ Claude CLI detected</b></font></html>"
        private const val STATUS_CLI_NOT_FOUND = "<html><font color='orange'><b>⚠ Claude CLI not found</b></font></html>"
    }

    /** Result of the dialog. */
    data class DialogResult(
        val cliDetected: Boolean = false,
        val cliVersion: String? = null,
    )

    var result: DialogResult? = null
        private set

    private val statusLabel = JBLabel(STATUS_DETECTING)
    private val detailsLabel = JBLabel("")

    private val detectButton = JButton("Re-detect").apply {
        addActionListener { detectCli() }
    }
    private val installButton = JButton("Installation Guide →").apply {
        addActionListener { BrowserUtil.browse("https://docs.anthropic.com/en/docs/claude-code/getting-started") }
    }

    // State
    private var cliDetected = false
    private var cliVersion: String? = null

    init {
        TokenPulseLogger.UI.info("[ClaudeConnectDialog] Initializing dialog")
        title = "Connect Claude Code"
        setOKButtonText("Add Account")
        isOKActionEnabled = false
        init()
    }

    override fun show() {
        TokenPulseLogger.UI.info("[ClaudeConnectDialog] show() called, starting CLI detection")
        super.show()
        detectCli()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            label("<html><b>Claude Code Connection</b></html>")
        }
        row {
            comment("Auto-detects installed Claude CLI and reads existing credentials.")
        }
        row {
            comment("Make sure you have run 'claude' at least once to log in.")
        }

        separator()

        row {
            label("Status:")
            cell(statusLabel)
        }
        row {
            cell(detailsLabel)
        }
        row {
            cell(detectButton)
            cell(installButton)
        }
    }

    override fun getPreferredFocusedComponent() = detectButton

    override fun doOKAction() {
        result = DialogResult(
            cliDetected = cliDetected,
            cliVersion = cliVersion,
        )
        super.doOKAction()
    }

    private fun detectCli() {
        TokenPulseLogger.UI.info("[ClaudeConnectDialog] detectCli() called")
        statusLabel.text = STATUS_DETECTING
        detailsLabel.text = ""
        isOKActionEnabled = false
        detectButton.isEnabled = false

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                TokenPulseLogger.UI.info("[ClaudeConnectDialog] Starting CLI detection on pooled thread")
                val available = ClaudeCliExecutor.isClaudeCliAvailable()
                TokenPulseLogger.UI.info("[ClaudeConnectDialog] Claude CLI available=$available")

                val version = if (available) {
                    TokenPulseLogger.UI.info("[ClaudeConnectDialog] Verifying Claude CLI works...")
                    val result = ClaudeCliExecutor.verifyClaudeCliWorks()
                    TokenPulseLogger.UI.info("[ClaudeConnectDialog] Claude CLI verifyResult: success=${result.first} output=${result.second}")
                    if (result.first) result.second else null
                } else {
                    TokenPulseLogger.UI.info("[ClaudeConnectDialog] Claude CLI not found in PATH or known locations")
                    null
                }

                cliDetected = available && version != null
                cliVersion = version
                TokenPulseLogger.UI.info("[ClaudeConnectDialog] Detection result: cliDetected=$cliDetected, cliVersion=$cliVersion")

                ApplicationManager.getApplication().invokeLater({
                    TokenPulseLogger.UI.info("[ClaudeConnectDialog] Updating UI with detection result")
                    updateStatus()
                    detectButton.isEnabled = true
                }, ModalityState.any())
            } catch (e: Exception) {
                TokenPulseLogger.UI.error("[ClaudeConnectDialog] Exception during CLI detection", e)
                ApplicationManager.getApplication().invokeLater({
                    statusLabel.text = "<html><font color='red'>Detection failed: ${e.message}</font></html>"
                    detailsLabel.text = ""
                    isOKActionEnabled = false
                    detectButton.isEnabled = true
                }, ModalityState.any())
            }
        }
    }

    private fun updateStatus() {
        if (cliDetected) {
            statusLabel.text = STATUS_CLI_FOUND
            detailsLabel.text = "<html><font color='gray'>Version: ${cliVersion ?: "unknown"}</font></html>"
            isOKActionEnabled = true
        } else {
            statusLabel.text = STATUS_CLI_NOT_FOUND
            detailsLabel.text = "<html><font color='gray'>Install via: npm install -g @anthropic-ai/claude-code</font></html>"
            isOKActionEnabled = false
        }
    }
}
