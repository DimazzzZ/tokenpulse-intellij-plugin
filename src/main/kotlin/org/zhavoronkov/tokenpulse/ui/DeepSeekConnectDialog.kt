package org.zhavoronkov.tokenpulse.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import org.zhavoronkov.tokenpulse.utils.Constants.FONT_SIZE_SMALL
import org.zhavoronkov.tokenpulse.utils.Constants.PASSWORD_FIELD_COLUMNS
import java.awt.Font
import javax.swing.JButton
import javax.swing.JComponent

/**
 * Dialog for connecting a DeepSeek API key.
 *
 * DeepSeek exposes a dedicated `GET /user/balance` endpoint that only requires
 * a Bearer API key for authentication.
 *
 * To create an API key:
 * 1. Go to https://platform.deepseek.com/api_keys
 * 2. Create a new API key.
 * 3. Copy the key and paste it here.
 */
class DeepSeekConnectDialog : DialogWrapper(true) {

    companion object {
        const val DEEPSEEK_KEYS_URL = "https://platform.deepseek.com/api_keys"

        private const val STATUS_WAITING = "<html><i>Paste your DeepSeek API key</i></html>"
        private const val STATUS_SUCCESS = "<html><font color='green'><b>✓ Key captured!</b></font></html>"
        private const val STATUS_EMPTY = "<html><font color='red'>Please paste a key first.</font></html>"
    }

    var capturedApiKey: String? = null
        private set

    private val statusLabel = JBLabel(STATUS_WAITING)

    private val keyField = JBPasswordField().apply {
        columns = PASSWORD_FIELD_COLUMNS
        font = Font(Font.MONOSPACED, Font.PLAIN, FONT_SIZE_SMALL)
        toolTipText = "Paste your DeepSeek API key here"
    }

    private val connectButton = JButton("Capture Key").apply {
        addActionListener { attemptCapture() }
    }

    private val openBrowserButton = JButton("Open API Key Page").apply {
        addActionListener { BrowserUtil.browse(DEEPSEEK_KEYS_URL) }
    }

    init {
        title = "Connect DeepSeek API Key"
        setOKButtonText("Connect")
        isOKActionEnabled = false
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            label("<html><b>DeepSeek API Key Connection</b></html>")
        }
        row {
            label("<html>1. Open the DeepSeek API keys page.</html>")
        }
        row {
            cell(openBrowserButton)
        }
        row {
            label("<html>2. Create a new API key.</html>")
        }
        row {
            label("<html>3. Paste the key below:</html>")
        }
        row {
            cell(keyField).align(AlignX.FILL)
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

        capturedApiKey = raw
        statusLabel.text = STATUS_SUCCESS
        isOKActionEnabled = true
    }
}
