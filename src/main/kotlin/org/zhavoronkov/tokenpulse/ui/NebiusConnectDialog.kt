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
    }

    private val gson = Gson()

    var capturedSessionJson: String? = null
        private set

    private val statusLabel = JBLabel("<html><i>Waiting for session…</i></html>")

    private val CONSOLE_SCRIPT = """
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

    private val pasteArea = JTextArea(12, 50).apply {
        lineWrap = true
        wrapStyleWord = true
        font = Font(Font.MONOSPACED, Font.PLAIN, 11)
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
            CopyPasteManager.getInstance().setContents(StringSelection(CONSOLE_SCRIPT))
            statusLabel.text = "<html><font color='green'>✓ Script copied to clipboard!</font></html>"
        }
    }

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
            cell(JBLabel("<html><b>Script:</b></html>").apply {
                font = Font(Font.SANS_SERIF, Font.BOLD, 11)
            })
        }
        row {
            val scriptArea = JTextArea(CONSOLE_SCRIPT).apply {
                isEditable = false
                font = Font(Font.MONOSPACED, Font.PLAIN, 11)
                lineWrap = true
                wrapStyleWord = true
                preferredSize = Dimension(560, 180)
                background = java.awt.Color(0x1E, 0x1E, 0x1E)
                foreground = java.awt.Color(0xD4, 0xD4, 0xD4)
                border = javax.swing.BorderFactory.createCompoundBorder(
                    javax.swing.BorderFactory.createLineBorder(java.awt.Color(0x3C, 0x3C, 0x3C)),
                    javax.swing.BorderFactory.createEmptyBorder(8, 8, 8, 8)
                )
            }
            cell(JScrollPane(scriptArea))
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

    override fun getPreferredFocusedComponent() = pasteArea

    private fun attemptCapture() {
        val raw = pasteArea.text.trim()
        if (raw.isEmpty()) {
            statusLabel.text = "<html><font color='red'>Please paste cURL or JSON first.</font></html>"
            return
        }

        val session = if (raw.startsWith("curl ") || raw.startsWith("-H ") || raw.startsWith("-b ") || raw.startsWith("--")) {
            parseCurl(raw)
        } else {
            parseJson(raw)
        }

        if (session == null) {
            statusLabel.text = "<html><font color='red'>Could not parse the pasted text. " +
                "Make sure you used \"Copy as cURL\" on the getCurrentTrial request, " +
                "or paste a valid JSON blob.</font></html>"
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
            // Normalize: remove line continuations and join into single line
            val normalized = curl.replace("\\\n".toRegex(), " ").replace("\\\r\n".toRegex(), " ")

            // Extract cookies from -b or --cookie (single or double quoted)
            val cookieStr = extractQuotedArg(normalized, "-b\\s+".toRegex())
                ?: extractQuotedArg(normalized, "--cookie\\s+".toRegex())
                ?: extractQuotedArg(normalized, "-H\\s+['\"]?[Cc]ookie:\\s*".toRegex())
                ?: extractQuotedArg(normalized, "-H\\s+['\"]?[Cc]ookie\\s*:\\s*".toRegex())
                ?: ""

            val appSession = Regex("""__Host-app_session=([^;]+)""").find(cookieStr)?.groupValues?.get(1)?.trim()
            val csrfCookie = Regex("""__Host-psifi\.x-csrf-token=([^;]+)""").find(cookieStr)?.groupValues?.get(1)?.trim()

            // Extract x-csrf-token header (single or double quoted)
            val csrfToken = extractQuotedArg(normalized, "-H\\s+['\"][Xx]-[Cc]srf-[Tt]oken:\\s*".toRegex())
                ?: extractQuotedArg(normalized, "-H\\s+['\"][Xx]-[Cc]srf-[Tt]oken\\s*:\\s*".toRegex())
                ?: extractQuotedArg(normalized, "-H\\s+\"[Xx]-[Cc]srf-[Tt]oken:\\s*".toRegex())
                ?: extractQuotedArg(normalized, "-H\\s+\"[Xx]-[Cc]srf-[Tt]oken\\s*:\\s*".toRegex())
                ?: extractQuotedArg(normalized, "--header\\s+['\"][Xx]-[Cc]srf-[Tt]oken:\\s*".toRegex())
                ?: extractQuotedArg(normalized, "--header\\s+['\"][Xx]-[Cc]srf-[Tt]oken\\s*:\\s*".toRegex())
                ?: ""

            // Fallback: if csrfToken is missing, use csrfCookie value (common pattern)
            val finalCsrfToken = csrfToken.ifBlank { csrfCookie }

            // Extract parentId from the entire normalized cURL (not just dataRaw)
            // This handles cases where parentId appears in --data-raw, -d, or other places
            val parentId = Regex("""\s*"parentId"\s*:\s*"([^"]+)"\s*""")
                .find(normalized)?.groupValues?.get(1)

            NebiusProviderClient.NebiusSession(
                appSession = appSession,
                csrfCookie = csrfCookie,
                csrfToken = finalCsrfToken,
                parentId = parentId
            )
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            null
        }
    }

    /**
     * Extract the quoted argument following a prefix.
     * Handles both single-quoted ('...') and double-quoted ("...") strings.
     */
    private fun extractQuotedArg(text: String, prefix: Regex): String? {
        // Try single quotes first
        val singlePattern = prefix.pattern + "'([^']+)'"
        val singleMatch = Regex(singlePattern).find(text)?.groupValues?.get(1)
        if (singleMatch != null) return singleMatch

        // Try double quotes
        val doublePattern = prefix.pattern + "\"([^\"]+)\""
        val doubleMatch = Regex(doublePattern).find(text)?.groupValues?.get(1)
        if (doubleMatch != null) return doubleMatch

        return null
    }

    /** Parse a raw JSON blob. */
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
