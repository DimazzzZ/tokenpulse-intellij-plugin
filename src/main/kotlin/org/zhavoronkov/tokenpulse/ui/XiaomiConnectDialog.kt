package org.zhavoronkov.tokenpulse.ui

import com.google.gson.Gson
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import org.zhavoronkov.tokenpulse.utils.Constants.TEXT_AREA_COLUMNS
import org.zhavoronkov.tokenpulse.utils.Constants.TEXT_AREA_ROWS
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.JTextArea

/**
 * Dialog for capturing Xiaomi platform session via cURL.
 *
 * The Xiaomi platform (`platform.xiaomimimo.com`) requires Xiaomi account login
 * to access balance and Token Plan usage data. This dialog guides users through
 * capturing the session cookies from their browser.
 *
 * Flow:
 * 1. Open Xiaomi platform in browser and log in.
 * 2. DevTools → Network tab → filter by "balance" or "tokenPlan".
 * 3. Refresh the page to trigger the request.
 * 4. Right-click the request → "Copy as cURL".
 * 5. Paste the cURL into the text area below.
 */
class XiaomiConnectDialog : DialogWrapper(true) {

    companion object {
        const val XIAOMI_PLATFORM_URL = "https://platform.xiaomimimo.com/console/balance"
        private const val STATUS_WAITING = "<html><i>Waiting for session…</i></html>"
        private const val STATUS_SUCCESS = "<html><font color='green'><b>✓ Session captured!</b></font></html>"
        private const val STATUS_EMPTY = "<html><font color='red'>Please paste cURL first.</font></html>"
        private const val STATUS_PARSE_ERROR = "<html><font color='red'>Could not parse the pasted text. " +
            "Make sure you used \"Copy as cURL\" on a request to platform.xiaomimimo.com.</font></html>"
        private const val STATUS_MISSING_FIELDS = "<html><font color='red'>Incomplete — missing: %s. " +
            "Make sure you are logged in and copied the right request.</font></html>"
        private val gson = Gson()

        /**
         * Extract Xiaomi session cookies from a cURL command string.
         * Handles both single-quoted and double-quoted cookie values.
         */
        fun extractCookiesFromCurl(text: String): XiaomiSessionCookies? {
            val cookieString = extractQuotedValue(text, 'b')
                ?: extractQuotedValue(text, 'c')
                ?: return null

            val cookies = cookieString.split(";").map { it.trim() }

            var serviceToken: String? = null
            var userId: String? = null
            var slh: String? = null
            var ph: String? = null

            for (cookie in cookies) {
                val eqIndex = cookie.indexOf('=')
                if (eqIndex < 0) continue

                val name = cookie.substring(0, eqIndex).trim()
                val value = cookie.substring(eqIndex + 1).trim()
                    .removeSurrounding("\"")
                    .removeSurrounding("'")

                when (name) {
                    "api-platform_serviceToken" -> serviceToken = value
                    "userId" -> userId = value
                    "api-platform_slh" -> slh = value
                    "api-platform_ph" -> ph = value
                }
            }

            return XiaomiSessionCookies(serviceToken, userId, slh, ph)
        }

        /**
         * Extract a quoted value after a cURL flag (e.g., -b '...' or -b "...").
         * Tries single quotes first (allows double quotes inside), then double quotes.
         */
        private fun extractQuotedValue(text: String, flag: Char): String? {
            val singleQuotePattern = Regex("""-$flag\s+'([^']+)'""")
            singleQuotePattern.find(text)?.let { return it.groupValues[1] }

            val doubleQuotePattern = Regex("""-$flag\s+"([^"]+)"""")
            doubleQuotePattern.find(text)?.let { return it.groupValues[1] }

            return null
        }
    }

    var capturedSessionJson: String? = null
        private set

    private val statusLabel = JBLabel(STATUS_WAITING)

    private val pasteArea = JTextArea(TEXT_AREA_ROWS, TEXT_AREA_COLUMNS).apply {
        lineWrap = true
        wrapStyleWord = true
    }

    init {
        title = "Connect Xiaomi Account"
        setOKButtonText("Connect")
        isOKActionEnabled = false
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            label("<html><b>Xiaomi MiMo Account</b></html>")
        }

        row {
            comment(
                "Connect your Xiaomi account to track balance and Token Plan usage.<br>" +
                    "This is the same account you use on platform.xiaomimimo.com."
            )
        }

        separator()

        row {
            label("<html><b>Steps:</b></html>")
        }

        row {
            comment(
                "1. Open <a href=\"$XIAOMI_PLATFORM_URL\">Xiaomi Platform</a> and log in<br>" +
                    "2. Open DevTools → Network tab (F12)<br>" +
                    "3. Filter by <code>balance</code> or <code>tokenPlan</code><br>" +
                    "4. Refresh the page to trigger the request<br>" +
                    "5. Right-click the request → <b>Copy as cURL</b><br>" +
                    "6. Paste below"
            )
        }

        row {
            cell(
                JButton("Open Xiaomi Platform →").apply {
                    addActionListener { BrowserUtil.browse(XIAOMI_PLATFORM_URL) }
                }
            )
        }

        separator()

        row {
            cell(JBLabel("Paste cURL command:"))
        }

        row {
            cell(JScrollPane(pasteArea)).align(AlignX.FILL)
        }

        row {
            cell(statusLabel)
        }

        row {
            cell(
                JButton("Parse").apply {
                    addActionListener { attemptParse() }
                }
            )
        }
    }

    private fun attemptParse() {
        val text = pasteArea.text.trim()
        if (text.isEmpty()) {
            statusLabel.text = STATUS_EMPTY
            return
        }

        try {
            val cookies = extractCookiesFromCurl(text)
            if (cookies == null) {
                statusLabel.text = STATUS_PARSE_ERROR
                return
            }

            val missing = mutableListOf<String>()
            if (cookies.serviceToken.isNullOrBlank()) missing.add("serviceToken")
            if (cookies.userId.isNullOrBlank()) missing.add("userId")

            if (missing.isNotEmpty()) {
                statusLabel.text = STATUS_MISSING_FIELDS.format(missing.joinToString(", "))
                return
            }

            capturedSessionJson = gson.toJson(cookies)
            statusLabel.text = STATUS_SUCCESS
            isOKActionEnabled = true
        } catch (e: Exception) {
            statusLabel.text = STATUS_PARSE_ERROR
        }
    }

    data class XiaomiSessionCookies(
        val serviceToken: String? = null,
        val userId: String? = null,
        val slh: String? = null,
        val ph: String? = null
    )
}
