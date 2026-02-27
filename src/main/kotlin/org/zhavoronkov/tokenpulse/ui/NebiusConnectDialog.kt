package org.zhavoronkov.tokenpulse.ui

import com.google.gson.Gson
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.panel
import org.zhavoronkov.tokenpulse.provider.NebiusProviderClient
import java.awt.Dimension
import java.awt.Font
import java.awt.datatransfer.StringSelection
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.JTextArea

/**
 * Guides the user through connecting their Nebius AI Studio billing session via cURL capture.
 *
 * ## Flow
 * 1. Open Nebius in browser and log in.
 * 2. Open DevTools → Network tab, filter by "getCurrentTrial".
 * 3. Trigger a page load/refresh so the request appears.
 * 4. Right-click the request → "Copy as cURL".
 * 5. Paste the cURL into this dialog and click "Validate & Connect".
 *
 * ## Fallback
 * Also accepts a raw JSON blob: `{"appSession":"...","csrfCookie":"...","csrfToken":"...","parentId":"..."}`
 */
class NebiusConnectDialog : DialogWrapper(true) {

    private val gson = Gson()

    var capturedSessionJson: String? = null
        private set

    private val statusLabel = JBLabel("<html><i>Waiting for session…</i></html>")

    private val filterText = "getCurrentTrial"

    private val filterArea = JTextArea(filterText).apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        preferredSize = Dimension(300, 28)
        background = java.awt.Color(0x2B, 0x2B, 0x2B)
        foreground = java.awt.Color(0xA9, 0xB7, 0xC6)
        border = javax.swing.BorderFactory.createEmptyBorder(4, 8, 4, 8)
    }

    private val copyFilterButton = JButton("Copy Filter").apply {
        addActionListener {
            CopyPasteManager.getInstance().setContents(StringSelection(filterText))
        }
    }

    private val pasteArea = JTextArea(8, 50).apply {
        lineWrap = true
        wrapStyleWord = true
        font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        toolTipText = "Paste the cURL command (or raw JSON) here"
    }

    private val captureButton = JButton("Validate & Connect").apply {
        addActionListener { attemptCapture() }
    }

    private val openBrowserButton = JButton("Open Nebius →").apply {
        addActionListener { BrowserUtil.browse("https://tokenfactory.nebius.com/") }
    }

    init {
        title = "Connect Nebius Billing Session"
        setOKButtonText("Connect")
        isOKActionEnabled = false
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            label("<html><b>Step 1.</b> Open Nebius in your browser and log in.</html>")
        }
        row { cell(openBrowserButton) }
        separator()
        row {
            label(
                "<html><b>Step 2.</b> Open DevTools (F12) → <b>Network</b> tab. " +
                    "Filter by the text below, then refresh the page:</html>"
            )
        }
        row {
            cell(filterArea).align(AlignX.LEFT)
            cell(copyFilterButton)
        }
        separator()
        row {
            label(
                "<html><b>Step 3.</b> Right-click the <b>getCurrentTrial</b> request → " +
                    "<b>Copy as cURL</b>. Paste it below:</html>"
            )
        }
        row {
            cell(JScrollPane(pasteArea))
                .align(AlignX.FILL)
                .align(AlignY.FILL)
                .resizableColumn()
        }
        row {
            cell(captureButton)
            cell(statusLabel).align(AlignX.FILL).resizableColumn()
        }
    }

    override fun getPreferredFocusedComponent() = pasteArea

    private fun attemptCapture() {
        val raw = pasteArea.text.trim()
        if (raw.isEmpty()) {
            statusLabel.text = "<html><font color='red'>Please paste the cURL command first.</font></html>"
            return
        }

        val session = if (raw.startsWith("curl ")) {
            parseCurl(raw)
        } else {
            parseJson(raw)
        }

        if (session == null) {
            statusLabel.text = "<html><font color='red'>Could not parse the pasted text. " +
                "Make sure you used \"Copy as cURL\" on the getCurrentTrial request.</font></html>"
            return
        }

        val missing = buildList {
            if (session.appSession.isNullOrBlank()) add("appSession")
            if (session.csrfCookie.isNullOrBlank()) add("csrfCookie")
            if (session.csrfToken.isNullOrBlank()) add("csrfToken")
            if (session.parentId.isNullOrBlank()) add("parentId")
        }

        if (missing.isNotEmpty()) {
            statusLabel.text = "<html><font color='red'>Incomplete — missing: ${missing.joinToString()}. " +
                "Make sure you are logged in and copied the right request.</font></html>"
            return
        }

        capturedSessionJson = gson.toJson(session)
        statusLabel.text = "<html><font color='green'><b>✓ Session captured!</b></font></html>"
        isOKActionEnabled = true
    }

    /** Parse a "Copy as cURL" string into a [NebiusProviderClient.NebiusSession]. */
    private fun parseCurl(curl: String): NebiusProviderClient.NebiusSession? {
        return try {
            // Cookie header: -b '...' or --cookie '...' or -H 'cookie: ...'
            val cookieStr = Regex("""-b\s+'([^']+)'""").find(curl)?.groupValues?.get(1)
                ?: Regex("""--cookie\s+'([^']+)'""").find(curl)?.groupValues?.get(1)
                ?: Regex("""-H\s+'[Cc]ookie:\s*([^']+)'""").find(curl)?.groupValues?.get(1)
                ?: ""

            val appSession = Regex("""__Host-app_session=([^;]+)""").find(cookieStr)?.groupValues?.get(1)?.trim()
            val csrfCookie = Regex("""__Host-psifi\.x-csrf-token=([^;]+)""").find(cookieStr)?.groupValues?.get(1)?.trim()

            // x-csrf-token header
            val csrfToken = Regex("""-H\s+'[Xx]-[Cc]srf-[Tt]oken:\s*([^']+)'""").find(curl)?.groupValues?.get(1)?.trim()
                ?: Regex("""-H\s+"[Xx]-[Cc]srf-[Tt]oken:\s*([^"]+)"""").find(curl)?.groupValues?.get(1)?.trim()

            // parentId from --data-raw or -d
            val dataRaw = Regex("""--data-raw\s+'([^']+)'""").find(curl)?.groupValues?.get(1)
                ?: Regex("""-d\s+'([^']+)'""").find(curl)?.groupValues?.get(1)
                ?: ""
            val parentId = Regex(""""parentId"\s*:\s*"([^"]+)"""").find(dataRaw)?.groupValues?.get(1)?.trim()

            NebiusProviderClient.NebiusSession(
                appSession = appSession,
                csrfCookie = csrfCookie,
                csrfToken = csrfToken,
                parentId = parentId
            )
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            null
        }
    }

    /** Parse a raw JSON blob (fallback). */
    private fun parseJson(text: String): NebiusProviderClient.NebiusSession? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            gson.fromJson(text.substring(start, end + 1), NebiusProviderClient.NebiusSession::class.java)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            null
        }
    }
}
