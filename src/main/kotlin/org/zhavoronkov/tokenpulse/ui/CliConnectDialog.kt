package org.zhavoronkov.tokenpulse.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.panel
import org.zhavoronkov.tokenpulse.utils.TokenPulseLogger
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
            comment(stripHtmlWrapper(spec.descriptionHtml))
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
            comment(stripHtmlWrapper(spec.requirementsHtml))
        }

        separator()

        row {
            cell(detectButton)
            cell(installButton)
        }
    }

    override fun getPreferredFocusedComponent() = detectButton

    // ── HTML helpers ────────────────────────────────────────────────────────

    /**
     * Strips outer <html>...</html> wrapper if present.
     * Needed because Row.comment() already wraps text in <html> internally.
     */
    private fun stripHtmlWrapper(text: String): String {
        val trimmed = text.trim()
        return if (trimmed.startsWith("<html>", ignoreCase = true) &&
            trimmed.endsWith("</html>", ignoreCase = true)
        ) {
            trimmed.substring(6, trimmed.length - 7)
        } else {
            trimmed
        }
    }

    private fun detectingStatusHtml() =
        "<html><i>Detecting ${spec.cliName}...</i></html>"

    private fun notFoundStatusHtml() =
        "<html><font color='orange'><b>\u26a0 ${spec.cliName} not found</b></font></html>"

    private fun detectedStatusHtml() =
        "<html><font color='green'><b>\u2713 ${spec.cliName} detected</b></font></html>"

    private fun errorMessageHtml(msg: String) =
        "<html><font color='gray'>$msg</font></html>"

    private fun versionHtml(version: String) =
        "<html><font color='gray'>Version: $version</font></html>"

    // ── Detection ───────────────────────────────────────────────────────────

    private fun detectCli() {
        TokenPulseLogger.UI.info("Detection started for ${spec.cliName}")
        statusLabel.text = detectingStatusHtml()
        versionLabel.text = ""
        isOKActionEnabled = false
        detectButton.isEnabled = false

        ApplicationManager.getApplication().executeOnPooledThread {
            val result = try {
                performDetection()
            } catch (e: Exception) {
                TokenPulseLogger.UI.info("Detection threw exception for ${spec.cliName}: ${e.message}")
                DetectionResult(
                    available = false,
                    version = null,
                    errorMessage = "Detection error: ${e.message ?: "unknown"}"
                )
            }

            TokenPulseLogger.UI.info(
                "Detection result for ${spec.cliName}: " +
                    "available=${result.available} version=${result.version} error=${result.errorMessage}"
            )

            // Use ModalityState.any() so the UI updates apply while the modal dialog is open.
            // Without this, invokeLater defers the callback until the dialog closes.
            ApplicationManager.getApplication().invokeLater({
                TokenPulseLogger.UI.info("Applying detection result for ${spec.cliName} on UI thread")
                if (!result.available) {
                    statusLabel.text = notFoundStatusHtml()
                    versionLabel.text = errorMessageHtml(result.errorMessage)
                    cliDetected = false
                    cliVersion = null
                } else if (result.version != null) {
                    statusLabel.text = detectedStatusHtml()
                    versionLabel.text = versionHtml(result.version)
                    versionLabel.font = versionLabel.font.deriveFont(Font.PLAIN)
                    cliDetected = true
                    cliVersion = result.version
                    isOKActionEnabled = true
                } else {
                    statusLabel.text = notFoundStatusHtml()
                    versionLabel.text = errorMessageHtml(result.errorMessage)
                    cliDetected = false
                    cliVersion = null
                }
                detectButton.isEnabled = true
            }, ModalityState.any())
        }
    }
}
