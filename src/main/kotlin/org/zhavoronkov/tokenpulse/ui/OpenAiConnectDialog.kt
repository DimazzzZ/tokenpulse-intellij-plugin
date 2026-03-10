package org.zhavoronkov.tokenpulse.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import org.zhavoronkov.tokenpulse.utils.Constants.FONT_SIZE_SMALL
import org.zhavoronkov.tokenpulse.utils.Constants.OPENAI_ADMIN_KEY_PREFIX
import org.zhavoronkov.tokenpulse.utils.Constants.OPENAI_BEARER_PREFIX
import org.zhavoronkov.tokenpulse.utils.Constants.PASSWORD_FIELD_COLUMNS
import java.awt.Font
import javax.swing.JButton
import javax.swing.JComponent

/**
 * Dialog for connecting an OpenAI Admin API Key.
 *
 * **Only Admin API Keys (`sk-admin-...`) are accepted.**
 * Regular project/personal keys (`sk-proj-...`, `sk-...`) do NOT have access to the
 * Organization Usage API or Organization Costs API and will be rejected.
 *
 * To create an Admin API Key:
 * 1. Go to https://platform.openai.com/settings/organization/admin-keys
 * 2. Create a new key and ensure "Usage API Scope" is set to **read**.
 * 3. Copy the key (starts with `sk-admin-...`) and paste it here.
 */
class OpenAiConnectDialog : DialogWrapper(true) {

    companion object {
        const val OPENAI_URL = "https://platform.openai.com/settings/organization/admin-keys"

        private const val STATUS_WAITING = "<html><i>Paste your Admin API key (sk-admin-...)</i></html>"
        private const val STATUS_SUCCESS = "<html><font color='green'><b>✓ Key captured!</b></font></html>"
        private const val STATUS_EMPTY = "<html><font color='red'>Please paste a key first.</font></html>"
        private const val STATUS_INVALID = "<html><font color='red'>" +
            "Invalid format. Only Admin keys starting with \"sk-admin-\" are accepted." +
            "</font></html>"
    }

    var capturedApiKey: String? = null
        private set

    private val statusLabel = JBLabel(STATUS_WAITING)
    private val keyTypeLabel = JBLabel("")

    private val keyField = JBPasswordField().apply {
        columns = PASSWORD_FIELD_COLUMNS
        font = Font(Font.MONOSPACED, Font.PLAIN, FONT_SIZE_SMALL)
        toolTipText = "Paste your OpenAI API key here (starts with 'sk-...')"
        addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyReleased(e: java.awt.event.KeyEvent) {
                updateKeyTypeHint()
            }
        })
    }

    private val connectButton = JButton("Capture Key").apply {
        addActionListener { attemptCapture() }
    }

    private val openBrowserButton = JButton("Open API Key Page").apply {
        addActionListener { BrowserUtil.browse(OPENAI_URL) }
    }

    init {
        title = "Connect OpenAI API Key"
        setOKButtonText("Connect")
        isOKActionEnabled = false
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            label("<html><b>OpenAI Admin Key Connection</b></html>")
        }
        row {
            label("<html>1. Open the OpenAI Admin keys page (org level).</html>")
        }
        row {
            cell(openBrowserButton)
        }
        row {
            label("<html>2. Create key with <b>Usage API Scope = read</b>.</html>")
        }
        row {
            label("<html>3. Paste key (sk-admin-...) below:</html>")
        }
        row {
            cell(keyField).align(AlignX.FILL)
        }
        row {
            cell(keyTypeLabel).align(AlignX.FILL)
        }
        row {
            cell(connectButton)
            cell(statusLabel).align(AlignX.FILL)
        }
    }

    override fun getPreferredFocusedComponent() = keyField

    private fun attemptCapture() {
        val raw = String(keyField.password).trim()
        if (raw.isEmpty()) {
            statusLabel.text = STATUS_EMPTY
            return
        }

        val apiKey = normalizeApiKey(raw)
        if (!isValidApiKey(apiKey)) {
            statusLabel.text = STATUS_INVALID
            return
        }

        capturedApiKey = apiKey
        statusLabel.text = STATUS_SUCCESS
        isOKActionEnabled = true
    }

    private fun updateKeyTypeHint() {
        val raw = String(keyField.password).trim()
        val apiKey = normalizeApiKey(raw)

        if (apiKey.startsWith(OPENAI_ADMIN_KEY_PREFIX)) {
            keyTypeLabel.text = "<html><font color='green'><b>✓ Admin API Key detected</b></font></html>"
        } else if (apiKey.isNotEmpty()) {
            keyTypeLabel.text = "<html><font color='red'>" +
                "Only Admin keys starting with \"sk-admin-\" are accepted.</font></html>"
        } else {
            keyTypeLabel.text = ""
        }
    }

    private fun normalizeApiKey(raw: String): String {
        return raw.removePrefix(OPENAI_BEARER_PREFIX).trim()
    }

    private fun isValidApiKey(apiKey: String): Boolean {
        return apiKey.startsWith(OPENAI_ADMIN_KEY_PREFIX)
    }
}
