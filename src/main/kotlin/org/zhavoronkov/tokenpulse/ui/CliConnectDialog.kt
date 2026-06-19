package org.zhavoronkov.tokenpulse.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.panel
import java.awt.Font
import javax.swing.JButton
import javax.swing.JComponent

abstract class CliConnectDialog : DialogWrapper(true) {

    protected abstract val cliName: String
    protected abstract val installUrl: String
    protected abstract val headerHtml: String
    protected abstract val descriptionHtml: String
    protected abstract val requirementsHtml: String

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
        addActionListener { BrowserUtil.browse(installUrl) }
    }

    init {
        title = "Connect $cliName"
        setOKButtonText("Add Account")
        isOKActionEnabled = false
        init()

        detectCli()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            label(headerHtml)
        }

        row {
            comment(descriptionHtml)
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
            comment(requirementsHtml)
        }

        separator()

        row {
            cell(detectButton)
            cell(installButton)
        }
    }

    override fun getPreferredFocusedComponent() = detectButton

    private fun detectCli() {
        statusLabel.text = "<html><i>Detecting $cliName...</i></html>"
        versionLabel.text = ""
        isOKActionEnabled = false
        detectButton.isEnabled = false

        ApplicationManager.getApplication().executeOnPooledThread {
            val result = performDetection()

            ApplicationManager.getApplication().invokeLater {
                if (!result.available) {
                    statusLabel.text = "<html><font color='orange'><b>\u26a0 $cliName not found</b></font></html>"
                    versionLabel.text = "<html><font color='gray'>${result.errorMessage}</font></html>"
                    cliDetected = false
                    cliVersion = null
                } else if (result.version != null) {
                    statusLabel.text = "<html><font color='green'><b>\u2713 $cliName detected</b></font></html>"
                    versionLabel.text = "<html><font color='gray'>Version: ${result.version}</font></html>"
                    versionLabel.font = versionLabel.font.deriveFont(Font.PLAIN)
                    cliDetected = true
                    cliVersion = result.version
                    isOKActionEnabled = true
                } else {
                    statusLabel.text = "<html><font color='orange'><b>\u26a0 $cliName not found</b></font></html>"
                    versionLabel.text = "<html><font color='gray'>${result.errorMessage}</font></html>"
                    cliDetected = false
                    cliVersion = null
                }
                detectButton.isEnabled = true
            }
        }
    }
}
