package org.zhavoronkov.tokenpulse.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.panel
import java.awt.Font
import javax.swing.JButton
import javax.swing.JComponent

/**
 * Immutable specification for a CLI connect dialog.
 * Passed via constructor to avoid reading open/abstract properties during base-class init.
 */
data class CliDialogSpec(
    val cliName: String,
    val installUrl: String,
    val headerHtml: String,
    val descriptionHtml: String,
    val requirementsHtml: String,
)

abstract class CliConnectDialog(
    private val spec: CliDialogSpec,
) : DialogWrapper(true) {

    var cliDetected: Boolean = false
        private set

    var cliVersion: String? = null
        private set

    protected class DetectionResult(
        val available: Boolean,
        val version: String?,
        val errorMessage: String
    )

    protected abstract fun performDetection(): DetectionResult

    private val statusLabel = JBLabel("")
    private val versionLabel = JBLabel("")

    private val detectButton = JButton("Re-detect").apply {
        addActionListener { detectCli() }
    }

    private val installButton = JButton("Installation Guide \u2192").apply {
        addActionListener { BrowserUtil.browse(spec.installUrl) }
    }

    init {
        title = "Connect ${spec.cliName}"
        setOKButtonText("Add Account")
        isOKActionEnabled = false
        init()
        // Detection is NOT started here — subclass must call startDetection()
        // after its own initialization is complete.
    }

    /**
     * Kicks off CLI detection on a pooled thread.
     * Must be called from the subclass [init] block (after super.init()).
     */
    protected fun startDetection() {
        detectCli()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            label(spec.headerHtml)
        }

        row {
            comment(spec.descriptionHtml)
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
            comment(spec.requirementsHtml)
        }

        separator()

        row {
            cell(detectButton)
            cell(installButton)
        }
    }

    override fun getPreferredFocusedComponent() = detectButton

    private fun detectCli() {
        statusLabel.text = "<html><i>Detecting ${spec.cliName}...</i></html>"
        versionLabel.text = ""
        isOKActionEnabled = false
        detectButton.isEnabled = false

        ApplicationManager.getApplication().executeOnPooledThread {
            val result = performDetection()

            ApplicationManager.getApplication().invokeLater {
                if (!result.available) {
                    statusLabel.text = "<html><font color='orange'><b>\u26a0 ${spec.cliName} not found</b></font></html>"
                    versionLabel.text = "<html><font color='gray'>${result.errorMessage}</font></html>"
                    cliDetected = false
                    cliVersion = null
                } else if (result.version != null) {
                    statusLabel.text = "<html><font color='green'><b>\u2713 ${spec.cliName} detected</b></font></html>"
                    versionLabel.text = "<html><font color='gray'>Version: ${result.version}</font></html>"
                    versionLabel.font = versionLabel.font.deriveFont(Font.PLAIN)
                    cliDetected = true
                    cliVersion = result.version
                    isOKActionEnabled = true
                } else {
                    statusLabel.text = "<html><font color='orange'><b>\u26a0 ${spec.cliName} not found</b></font></html>"
                    versionLabel.text = "<html><font color='gray'>${result.errorMessage}</font></html>"
                    cliDetected = false
                    cliVersion = null
                }
                detectButton.isEnabled = true
            }
        }
    }
}
