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
import org.zhavoronkov.tokenpulse.utils.Constants.FONT_SIZE_SMALL
import org.zhavoronkov.tokenpulse.utils.Constants.SCRIPT_PANEL_HEIGHT
import org.zhavoronkov.tokenpulse.utils.Constants.SCRIPT_PANEL_WIDTH
import org.zhavoronkov.tokenpulse.utils.Constants.TEXT_AREA_COLUMNS
import org.zhavoronkov.tokenpulse.utils.Constants.TEXT_AREA_ROWS
import java.awt.Dimension
import java.awt.Font
import java.awt.datatransfer.StringSelection
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.JTextArea

/**
 * Guides the user through connecting their Nebius AI Studio billing session.
 *
 * ## Method 1: Console Script (for non-HttpOnly cookies)
 * 1. Open Nebius in browser and log in.
 * 2. Open DevTools (F12) → Console tab.
 * 3. Click "Copy Script" below, then paste and run the script in the console.
 * 4. Copy the JSON output from the console and paste it into the text area below.
 *
 * ## Method 2: cURL (recommended — works with HttpOnly cookies)
 * 1. Open Nebius in browser and log in.
 * 2. DevTools → Network tab → filter by "getCurrentTrial".
 * 3. Refresh the page to trigger the request.
 * 4. Right-click the request → "Copy as cURL".
 * 5. Paste the cURL into the text area below.
 *
 * ## Method 3: Manual JSON
 * Also accepts a raw JSON blob: `{"appSession":"...","csrfCookie":"...","csrfToken":"...","parentId":"..."}`
 */
class NebiusConnectDialog : DialogWrapper(true) {

    companion object {
        const val NEBIUS_URL = "https://tokenfactory.nebius.com/"
        private const val STATUS_WAITING = "<html><i>Waiting for session…</i></html>"
        private const val STATUS_SUCCESS = "<html><font color='green'><b>✓ Session captured!</b></font></html>"
        private const val STATUS_EMPTY = "<html><font color='red'>Please paste cURL or JSON first.</font></html>"
        private const val STATUS_PARSE_ERROR = "<html><font color='red'>Could not parse the pasted text. " +
            "Make sure you used \"Copy as cURL\" on the getCurrentTrial request, " +
            "or paste a valid JSON blob.</font></html>"
        private const val STATUS_MISSING_FIELDS = "<html><font color='red'>Incomplete — missing: %s. " +
            "Make sure you are logged in and copied the right request.</font></html>"
        private val gson = Gson()
    }

    var capturedSessionJson: String? = null
        private set

    private val statusLabel = JBLabel(STATUS_WAITING)

    private val consoleScript = """
        // Nebius Billing Session Extractor
        // Run this in the Console tab of DevTools (F12) on tokenfactory.nebius.com
        // Note: This may fail if __Host-app_session is HttpOnly (common). Use cURL method instead.

        (function() {
            // 1. Extract cookies
            const cookies = document.cookie.split(';').reduce((acc, cookie) => {
                const [name, ...valueParts] = cookie.trim().split('=');
                acc[name] = valueParts.join('=');
                return acc;
            }, {});

            const appSession = cookies['__Host-app_session'];
            const csrfCookie = cookies['__Host-psifi.x-csrf-token'];

            if (!appSession || !csrfCookie) {
                console.error('Missing required cookies. Ensure you are logged in.');
                console.log('Tip: If cookies are HttpOnly, use the cURL method instead.');
                return null;
            }

            // 2. Derive csrfToken from cookie value (same value used in header)
            const csrfToken = csrfCookie;

            // 3. Extract parentId with multiple fallbacks
            let parentId = null;

            // Fallback A: URL path (/p/<parentId>/...)
            const pathMatch = window.location.pathname.match(/\/p\/([^/]+)(?:\/|$)/);
            if (pathMatch && pathMatch[1]) {
                parentId = pathMatch[1];
            }

            // Fallback B: URL query param ?parentId=...
            if (!parentId) {
                const urlParams = new URLSearchParams(window.location.search);
                parentId = urlParams.get('parentId');
            }

            // Fallback C: Script tag with contract ID
            if (!parentId) {
                const scripts = document.getElementsByTagName('script');
                for (let script of scripts) {
                    const text = script.textContent || '';
                    const match = text.match(/"contractId"\\s*:\\s*"([^"]+)"/);
                    if (match && match[1]) {
                        parentId = match[1];
                        break;
                    }
                }
            }

            // Fallback D: window.__NEBIUS__ or similar global
            if (!parentId && typeof window !== 'undefined') {
                const win = window;
                if (win.__NEBIUS__ && win.__NEBIUS__.contractId) {
                    parentId = win.__NEBIUS__.contractId;
                } else if (win.__INITIAL_STATE__ && win.__INITIAL_STATE__.contractId) {
                    parentId = win.__INITIAL_STATE__.contractId;
                }
            }

            // Fallback E: localStorage/sessionStorage
            if (!parentId) {
                try {
                    parentId = localStorage.getItem('parentId') || sessionStorage.getItem('parentId');
                } catch (e) {}
            }

            if (!parentId) {
                console.error('Could not extract parentId. Try navigating to a contract page first.');
                return null;
            }

            const payload = {
                appSession: appSession,
                csrfCookie: csrfCookie,
                csrfToken: csrfToken,
                parentId: parentId
            };

            const json = JSON.stringify(payload, null, 2);

            // Log to console
            console.log('Nebius Session Extracted:');
            console.log(json);

            // Attempt clipboard copy
            if (navigator.clipboard && navigator.clipboard.writeText) {
                navigator.clipboard.writeText(json).then(() => {
                    console.log('✓ Copied to clipboard');
                }).catch(err => {
                    console.warn('Clipboard copy failed:', err);
                });
            }

            return json;
        })();
    """.trimIndent()

    private val pasteArea = JTextArea(TEXT_AREA_ROWS, TEXT_AREA_COLUMNS).apply {
        lineWrap = true
        wrapStyleWord = true
        font = Font(Font.MONOSPACED, Font.PLAIN, FONT_SIZE_SMALL)
        toolTipText = "Paste JSON from console script OR cURL command here"
    }

    private val captureButton = JButton("Validate & Connect").apply {
        addActionListener { attemptCapture() }
    }

    private val openBrowserButton = JButton("Open Nebius →").apply {
        addActionListener { BrowserUtil.browse(NEBIUS_URL) }
    }

    private val copyScriptButton = JButton("Copy Script").apply {
        addActionListener {
            CopyPasteManager.getInstance().setContents(StringSelection(consoleScript))
            statusLabel.text = STATUS_SUCCESS
        }
    }

    private val scriptPanel = createScriptPanel()

    init {
        title = "Connect Nebius Billing Session"
        setOKButtonText("Connect")
        isOKActionEnabled = false
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            label("<html><b>Method 1: Console Script</b> (may fail if cookies are HttpOnly)</html>")
        }
        row { cell(openBrowserButton) }
        separator()
        row {
            label(
                "<html><b>Step 1.</b> Open DevTools (F12) → <b>Console</b> tab. " +
                    "Click \"Copy Script\" below, then paste and run it in the console:</html>"
            )
        }
        row {
            cell(copyScriptButton)
        }
        row {
            cell(
                JBLabel("<html><b>Script:</b></html>").apply {
                    font = Font(Font.SANS_SERIF, Font.BOLD, FONT_SIZE_SMALL)
                }
            )
        }
        row {
            cell(scriptPanel)
                .align(AlignX.FILL)
                .align(AlignY.FILL)
                .resizableColumn()
        }
        separator()
        row {
            label("<html><b>Method 2: cURL (Recommended)</b></html>")
        }
        row {
            label(
                "<html><b>Step 1.</b> DevTools → <b>Network</b> tab → filter by \"getCurrentTrial\".</html>"
            )
        }
        row {
            label("<html><b>Step 2.</b> Refresh page, right-click request → \"Copy as cURL\".</html>")
        }
        row {
            label("<html><b>Step 3.</b> Paste cURL or JSON below and click \"Validate & Connect\".</html>")
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

    private fun createScriptPanel(): JComponent {
        val scriptArea = JTextArea(consoleScript).apply {
            isEditable = false
            font = Font(Font.MONOSPACED, Font.PLAIN, FONT_SIZE_SMALL)
            lineWrap = true
            wrapStyleWord = true
            preferredSize = Dimension(SCRIPT_PANEL_WIDTH, SCRIPT_PANEL_HEIGHT)
            background = java.awt.Color(0x1E, 0x1E, 0x1E)
            foreground = java.awt.Color(0xD4, 0xD4, 0xD4)
            border = javax.swing.BorderFactory.createCompoundBorder(
                javax.swing.BorderFactory.createLineBorder(java.awt.Color(0x3C, 0x3C, 0x3C)),
                javax.swing.BorderFactory.createEmptyBorder(8, 8, 8, 8)
            )
        }
        return JScrollPane(scriptArea)
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
            statusLabel.text = STATUS_MISSING_FIELDS.format(missing.joinToString())
            return
        }

        capturedSessionJson = gson.toJson(session)
        statusLabel.text = STATUS_SUCCESS
        isOKActionEnabled = true
    }

    private fun parseInput(input: String): NebiusProviderClient.NebiusSession? {
        return if (isCurlInput(input)) {
            parseCurl(input)
        } else {
            parseJson(input)
        }
    }

    private fun isCurlInput(input: String): Boolean {
        return input.startsWith("curl ") || input.startsWith("-H ") ||
            input.startsWith("-b ") || input.startsWith("--")
    }

    private fun String.format(vararg args: Any?): String = format(java.util.Locale.US, *args)

    private fun collectMissingFields(session: NebiusProviderClient.NebiusSession): List<String> {
        return buildList {
            if (session.appSession.isNullOrBlank()) add("appSession")
            if (session.csrfCookie.isNullOrBlank()) add("csrfCookie")
            if (session.csrfToken.isNullOrBlank()) add("csrfToken")
            if (session.parentId.isNullOrBlank()) add("parentId")
        }
    }

    /** Parse a "Copy as cURL" string into a [NebiusProviderClient.NebiusSession]. */
    private fun parseCurl(curl: String): NebiusProviderClient.NebiusSession? {
        return try {
            val normalized = normalizeCurl(curl)
            val cookieStr = extractCookies(normalized)
            val (appSession, csrfCookie) = extractSessionCookies(cookieStr)
            val csrfToken = extractCsrfToken(normalized, csrfCookie)
            val parentId = extractParentId(normalized)

            NebiusProviderClient.NebiusSession(
                appSession = appSession,
                csrfCookie = csrfCookie,
                csrfToken = csrfToken,
                parentId = parentId
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun normalizeCurl(curl: String): String {
        return curl.replace("\\\n".toRegex(), " ").replace("\\\r\n".toRegex(), " ")
    }

    private fun extractCookies(normalized: String): String {
        return extractQuotedArg(normalized, "-b\\s+".toRegex())
            ?: extractQuotedArg(normalized, "--cookie\\s+".toRegex())
            ?: extractQuotedArg(normalized, "-H\\s+['\"]?[Cc]ookie:\\s*".toRegex())
            ?: extractQuotedArg(normalized, "-H\\s+['\"]?[Cc]ookie\\s*:\\s*".toRegex())
            ?: ""
    }

    private fun extractSessionCookies(cookieStr: String): Pair<String?, String?> {
        val appSession = Regex("""__Host-app_session=([^;]+)""")
            .find(cookieStr)?.groupValues?.get(1)
        val csrfCookie = Regex("""__Host-psifi\.x-csrf-token=([^;]+)""")
            .find(cookieStr)?.groupValues?.get(1)
        return Pair(appSession?.trim(), csrfCookie?.trim())
    }

    private fun extractCsrfToken(normalized: String, csrfCookie: String?): String {
        val csrfToken = extractQuotedArg(normalized, "-H\\s+['\"][Xx]-[Cc]srf-[Tt]oken:\\s*".toRegex())
            ?: extractQuotedArg(normalized, "-H\\s+['\"][Xx]-[Cc]srf-[Tt]oken\\s*:\\s*".toRegex())
            ?: extractQuotedArg(normalized, "-H\\s+\"[Xx]-[Cc]srf-[Tt]oken:\\s*".toRegex())
            ?: extractQuotedArg(normalized, "-H\\s+\"[Xx]-[Cc]srf-[Tt]oken\\s*:\\s*".toRegex())
            ?: extractQuotedArg(normalized, "--header\\s+['\"][Xx]-[Cc]srf-[Tt]oken:\\s*".toRegex())
            ?: extractQuotedArg(normalized, "--header\\s+['\"][Xx]-[Cc]srf-[Tt]oken\\s*:\\s*".toRegex())
            ?: ""
        return csrfToken.ifBlank { csrfCookie ?: "" }
    }

    private fun extractParentId(normalized: String): String? {
        return Regex("""\s*"parentId"\s*:\s*"([^"]+)"\s*""")
            .find(normalized)?.groupValues?.get(1)
    }

    /**
     * Extract the quoted argument following a prefix.
     * Handles both single-quoted ('...') and double-quoted ("...") strings.
     */
    private fun extractQuotedArg(text: String, prefix: Regex): String? {
        return extractSingleQuoted(text, prefix) ?: extractDoubleQuoted(text, prefix)
    }

    private fun extractSingleQuoted(text: String, prefix: Regex): String? {
        val pattern = prefix.pattern + "'([^']+)'"
        return Regex(pattern).find(text)?.groupValues?.get(1)
    }

    private fun extractDoubleQuoted(text: String, prefix: Regex): String? {
        val pattern = prefix.pattern + "\"([^\"]+)\""
        return Regex(pattern).find(text)?.groupValues?.get(1)
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
