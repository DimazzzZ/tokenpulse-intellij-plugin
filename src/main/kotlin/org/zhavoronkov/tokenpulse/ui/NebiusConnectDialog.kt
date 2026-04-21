package org.zhavoronkov.tokenpulse.ui

import com.google.gson.Gson
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import org.zhavoronkov.tokenpulse.provider.nebius.NebiusProviderClient
import org.zhavoronkov.tokenpulse.utils.Constants.FONT_SIZE_SMALL
import org.zhavoronkov.tokenpulse.utils.Constants.TEXT_AREA_COLUMNS
import org.zhavoronkov.tokenpulse.utils.Constants.TEXT_AREA_ROWS
import java.awt.Font
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.JTextArea

/**
 * Guides the user through connecting their Nebius AI Studio billing session via cURL.
 *
 * 1. Open Nebius in browser and log in.
 * 2. DevTools → Network tab → filter by "getBalance".
 * 3. Refresh the page to trigger the request.
 * 4. Right-click the request → "Copy as cURL".
 * 5. Paste the cURL into the text area below.
 *
 * Also accepts a raw JSON blob: `{"appSession":"...","csrfCookie":"...","csrfToken":"...","parentId":"..."}`
 */
class NebiusConnectDialog : DialogWrapper(true) {

    companion object {
        const val NEBIUS_URL = "https://tokenfactory.nebius.com/"
        private const val STATUS_WAITING = "<html><i>Waiting for session…</i></html>"
        private const val STATUS_SUCCESS = "<html><font color='green'><b>✓ Session captured!</b></font></html>"
        private const val STATUS_EMPTY = "<html><font color='red'>Please paste cURL or JSON first.</font></html>"
        private const val STATUS_PARSE_ERROR = "<html><font color='red'>Could not parse the pasted text. " +
            "Make sure you used \"Copy as cURL\" on the getBalance request, " +
            "or paste a valid JSON blob.</font></html>"
        private const val STATUS_MISSING_FIELDS = "<html><font color='red'>Incomplete — missing: %s. " +
            "Make sure you are logged in and copied the right request.</font></html>"
        private val gson = Gson()
    }

    var capturedSessionJson: String? = null
        private set

    private val statusLabel = JBLabel(STATUS_WAITING)

    private val pasteArea = JTextArea(TEXT_AREA_ROWS, TEXT_AREA_COLUMNS).apply {
        lineWrap = true
        wrapStyleWord = true
        font = Font(Font.MONOSPACED, Font.PLAIN, FONT_SIZE_SMALL)
        toolTipText = "Paste cURL command OR JSON here"
    }

    private val captureButton = JButton("Validate & Connect").apply {
        addActionListener { attemptCapture() }
    }

    private val openBrowserButton = JButton("Open Nebius →").apply {
        addActionListener { BrowserUtil.browse(NEBIUS_URL) }
    }

    init {
        title = "Connect Nebius Billing Session"
        setOKButtonText("Connect")
        isOKActionEnabled = false
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            label("<html><b>Method: cURL (Required)</b></html>")
        }
        row {
            label("<html>1. Open Nebius, login, then DevTools → <b>Network</b>.</html>")
        }
        row {
            label("<html>2. Refresh, filter by \"getBalance\", right-click → <b>Copy as cURL</b>.</html>")
        }
        row {
            label("<html>3. Paste result below and click Validate.</html>")
        }
        row {
            cell(JScrollPane(pasteArea))
                .align(AlignX.FILL)
                .resizableColumn()
        }
        row {
            cell(captureButton)
            cell(statusLabel).align(AlignX.FILL)
        }

        row {
            cell(openBrowserButton)
        }
    }

    override fun getPreferredFocusedComponent() = pasteArea

    private fun attemptCapture() {
        val raw = pasteArea.text.trim()
        if (raw.isEmpty()) {
            statusLabel.text = STATUS_EMPTY
            return
        }

        val session = parseInput(raw)
        if (session == null) {
            statusLabel.text = STATUS_PARSE_ERROR
            return
        }

        val missing = collectMissingFields(session)
        if (missing.isNotEmpty()) {
            statusLabel.text = java.lang.String.format(
                java.util.Locale.US,
                STATUS_MISSING_FIELDS,
                missing.joinToString()
            )
            return
        }

        capturedSessionJson = gson.toJson(session)
        statusLabel.text = STATUS_SUCCESS
        isOKActionEnabled = true
    }

    private fun parseInput(input: String): NebiusProviderClient.NebiusSession? {
        return if (NebiusCurlParser.isCurlInput(input)) {
            NebiusCurlParser.parseCurl(input)
        } else {
            parseJson(input)
        }
    }

    private fun collectMissingFields(session: NebiusProviderClient.NebiusSession): List<String> {
        return buildList {
            if (session.appSession.isNullOrBlank()) add("appSession")
            if (session.csrfCookie.isNullOrBlank()) add("csrfCookie")
            if (session.csrfToken.isNullOrBlank()) add("csrfToken")
            if (session.parentId.isNullOrBlank()) add("parentId")
        }
    }

    /** Parse a raw JSON blob. */
    private fun parseJson(text: String): NebiusProviderClient.NebiusSession? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            gson.fromJson(text.substring(start, end + 1), NebiusProviderClient.NebiusSession::class.java)
        } catch (e: Exception) {
            null
        }
    }
}
