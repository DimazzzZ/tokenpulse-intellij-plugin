package org.zhavoronkov.tokenpulse.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import org.zhavoronkov.tokenpulse.utils.Constants.FONT_SIZE_SMALL
import org.zhavoronkov.tokenpulse.utils.Constants.OPENAI_API_KEY_PREFIX
import org.zhavoronkov.tokenpulse.utils.Constants.OPENAI_BEARER_PREFIX
import org.zhavoronkov.tokenpulse.utils.Constants.PASSWORD_FIELD_COLUMNS
import java.awt.Font
import javax.swing.JButton
import javax.swing.JComponent

/**
 * Dialog for connecting ChatGPT API key.
 *
 * This dialog guides users through obtaining a ChatGPT API key from the OpenAI platform.
 * The key is used to access the ChatGPT usage dashboard API.
 *
 * ## Steps
 * 1. Open the ChatGPT API keys page in your browser.
 * 2. Generate a new API key (or use an existing one).
 * 3. Copy the key and paste it into the text area below.
 * 4. Click "Validate & Connect" to store the key securely.
 *
 * Note: The API key is stored securely in IntelliJ's PasswordSafe.
 */
class OpenAiConnectDialog : DialogWrapper(true) {

    companion object {
        const val OPENAI_URL = "https://platform.openai.com/account/api-keys"
        private const val STATUS_WAITING = "<html><i>Waiting for API key…</i></html>"
        private const val STATUS_SUCCESS = "<html><font color='green'><b>✓ API key validated!</b></font></html>"
        private const val STATUS_EMPTY = "<html><font color='red'>Please paste an API key first.</font></html>"
        private const val STATUS_INVALID_FORMAT = "<html><font color='red'>Invalid API key format. Expected key starting with \"sk-\".</font></html>"
    }

    var capturedApiKey: String? = null
        private set

    private val statusLabel = JBLabel(STATUS_WAITING)

    private val keyField = JBPasswordField().apply {
        columns = PASSWORD_FIELD_COLUMNS
        font = Font(Font.MONOSPACED, Font.PLAIN, FONT_SIZE_SMALL)
        toolTipText = "Paste your ChatGPT API key here (starts with '$OPENAI_API_KEY_PREFIX...')"
    }

    private val connectButton = JButton("Validate & Connect").apply {
        addActionListener { attemptCapture() }
    }

    private val openBrowserButton = JButton("Open OpenAI →").apply {
        addActionListener { BrowserUtil.browse(OPENAI_URL) }
    }

    init {
        title = "Connect ChatGPT API Key"
        setOKButtonText("Connect")
        isOKActionEnabled = false
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            label("<html><b>Step 1.</b> Open the OpenAI API keys page:</html>")
        }
        row { cell(openBrowserButton) }
        separator()
        row {
            label(
                "<html><b>Step 2.</b> Create or copy an API key " +
                    "from your OpenAI dashboard.</html>"
            )
        }
        row {
            label("<html><b>Step 3.</b> Paste the API key below and click \"Validate & Connect\".</html>")
        }
        row {
            cell(keyField).align(AlignX.FILL).resizableColumn()
        }
        row {
            cell(connectButton)
            cell(statusLabel).align(AlignX.FILL).resizableColumn()
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
            statusLabel.text = STATUS_INVALID_FORMAT
            return
        }

        capturedApiKey = apiKey
        statusLabel.text = STATUS_SUCCESS
        isOKActionEnabled = true
    }

    private fun normalizeApiKey(raw: String): String {
        return raw.removePrefix(OPENAI_BEARER_PREFIX).trim()
    }

    private fun isValidApiKey(apiKey: String): Boolean {
        return apiKey.startsWith(OPENAI_API_KEY_PREFIX)
    }
}
