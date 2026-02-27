package org.zhavoronkov.tokenpulse.ui

import com.google.gson.Gson
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import org.zhavoronkov.tokenpulse.provider.OpenAiCodexUsageProviderClient
import java.awt.Dimension
import java.awt.Font
import java.awt.datatransfer.StringSelection
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.JTextArea

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
    }

    private val gson = Gson()

    var capturedApiKey: String? = null
        private set

    private val statusLabel = JBLabel("<html><i>Waiting for API key…</i></html>")

    private val CONSOLE_SCRIPT = """
        // ChatGPT API Key Setup
        // 1. Go to https://platform.openai.com/account/api-keys
        // 2. Create a new API key
        // 3. Copy the key (starts with "sk-...")
        // 4. Paste it into the text area below
        //
        // The key will be stored securely in IntelliJ's PasswordSafe.
        """.trimIndent()

    private val keyArea = JBPasswordField().apply {
        columns = 50
        font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        toolTipText = "Paste your ChatGPT API key here (starts with 'sk-...')"
    }

    private val connectButton = JButton("Validate & Connect").apply {
        addActionListener { attemptCapture() }
    }

    private val openBrowserButton = JButton("Open OpenAI →").apply {
        addActionListener { BrowserUtil.browse(OPENAI_URL) }
    }

    private val copyScriptButton = JButton("Copy Instructions").apply {
        addActionListener {
            CopyPasteManager.getInstance().setContents(StringSelection(CONSOLE_SCRIPT))
            statusLabel.text = "<html><font color='green'>✓ Instructions copied to clipboard!</font></html>"
        }
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
                "<html><b>Step 2.</b> Create or copy an API key from your OpenAI dashboard.</html>"
            )
        }
        row {
            label("<html><b>Step 3.</b> Paste the API key below and click \"Validate & Connect\".</html>")
        }
        row {
            cell(keyArea).align(AlignX.FILL).resizableColumn()
        }
        row {
            cell(connectButton)
            cell(statusLabel).align(AlignX.FILL).resizableColumn()
        }
    }

    override fun getPreferredFocusedComponent() = keyArea

    private fun attemptCapture() {
        val raw = String(keyArea.password).trim()
        if (raw.isEmpty()) {
            statusLabel.text = "<html><font color='red'>Please paste an API key first.</font></html>"
            return
        }

        // Validate API key format (should start with "sk-" or "Bearer sk-")
        val apiKey = if (raw.startsWith("Bearer ")) {
            raw.removePrefix("Bearer ").trim()
        } else {
            raw
        }

        if (!apiKey.startsWith("sk-")) {
            statusLabel.text = "<html><font color='red'>Invalid API key format. Expected key starting with \"sk-\".</font></html>"
            return
        }

        capturedApiKey = apiKey
        statusLabel.text = "<html><font color='green'><b>✓ API key validated!</b></font></html>"
        isOKActionEnabled = true
    }
}
