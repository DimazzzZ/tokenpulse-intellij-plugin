package org.zhavoronkov.tokenpulse.ui

import com.google.gson.Gson
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import org.zhavoronkov.tokenpulse.utils.Constants.TEXT_AREA_COLUMNS
import org.zhavoronkov.tokenpulse.utils.Constants.TEXT_AREA_ROWS
import org.zhavoronkov.tokenpulse.utils.CurlCookieExtractor
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
 *
 * Optionally, the user also pastes a cURL from an `account.xiaomi.com` request so
 * the plugin can capture the long-lived `passToken` and silently re-login when the
 * short-lived platform session expires (see XiaomiSessionRefresher).
 */
class XiaomiConnectDialog : DialogWrapper(true) {

    companion object {
        const val XIAOMI_PLATFORM_URL = "https://platform.xiaomimimo.com/console/balance"
        const val XIAOMI_ACCOUNT_URL = "https://account.xiaomi.com/"
        private const val STATUS_WAITING = "<html><i>Waiting for session…</i></html>"
        private const val STATUS_SUCCESS = "<html><font color='green'><b>✓ Session captured!</b></font></html>"
        private const val STATUS_SUCCESS_NO_REFRESH = "<html><font color='green'><b>✓ Session captured</b></font> " +
            "<font color='#B87333'>(no passToken — auto-refresh disabled; you'll re-connect when it expires)</font></html>"
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
            val cookieString = CurlCookieExtractor.extractCookieString(text) ?: return null
            val cookies = CurlCookieExtractor.parseCookieString(cookieString)

            return XiaomiSessionCookies(
                serviceToken = cookies["api-platform_serviceToken"],
                userId = cookies["userId"],
                slh = cookies["api-platform_slh"],
                ph = cookies["api-platform_ph"]
            )
        }

        /**
         * Extract the long-lived Xiaomi Passport cookies from a cURL command copied
         * from an `account.xiaomi.com` request. Returns null if no cookie header /
         * no `passToken` is present.
         */
        fun extractPassportFromCurl(text: String): XiaomiPassportCookies? {
            val cookieString = CurlCookieExtractor.extractCookieString(text) ?: return null
            val cookies = CurlCookieExtractor.parseCookieString(cookieString)
            val passToken = cookies["passToken"]
            if (passToken.isNullOrBlank()) return null
            return XiaomiPassportCookies(
                passToken = passToken,
                userId = cookies["userId"],
                cUserId = cookies["cUserId"]
            )
        }
    }

    var capturedSessionJson: String? = null
        private set

    private val statusLabel = JBLabel(STATUS_WAITING)

    private val pasteArea = JTextArea(TEXT_AREA_ROWS, TEXT_AREA_COLUMNS).apply {
        lineWrap = true
        wrapStyleWord = true
    }

    private val accountPasteArea = JTextArea(TEXT_AREA_ROWS, TEXT_AREA_COLUMNS).apply {
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

        separator()

        row {
            label("<html><b>Enable auto-refresh (recommended):</b></html>")
        }

        row {
            comment(
                "To let TokenPulse silently re-login when the session expires, also " +
                    "capture your Xiaomi Passport cookie:<br>" +
                    "1. In DevTools → Network, filter by <code>account.xiaomi.com</code> " +
                    "(or <code>serviceLogin</code>)<br>" +
                    "2. Right-click any request to that host → <b>Copy as cURL</b> and paste below.<br>" +
                    "<i>Optional — leave blank to reconnect manually when the session expires.</i>"
            )
        }

        row {
            cell(JScrollPane(accountPasteArea)).align(AlignX.FILL)
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

            val passport = accountPasteArea.text.trim()
                .takeIf { it.isNotEmpty() }
                ?.let { extractPassportFromCurl(it) }

            val session = XiaomiSessionCookies(
                serviceToken = cookies.serviceToken,
                userId = cookies.userId,
                slh = cookies.slh,
                ph = cookies.ph,
                passToken = passport?.passToken,
                cUserId = passport?.cUserId
            )

            capturedSessionJson = gson.toJson(session)
            statusLabel.text = if (passport?.passToken.isNullOrBlank()) {
                STATUS_SUCCESS_NO_REFRESH
            } else {
                STATUS_SUCCESS
            }
            isOKActionEnabled = true
        } catch (e: Exception) {
            statusLabel.text = STATUS_PARSE_ERROR
        }
    }

    data class XiaomiSessionCookies(
        val serviceToken: String? = null,
        val userId: String? = null,
        val slh: String? = null,
        val ph: String? = null,
        val passToken: String? = null,
        val cUserId: String? = null
    )

    data class XiaomiPassportCookies(
        val passToken: String? = null,
        val userId: String? = null,
        val cUserId: String? = null
    )
}
