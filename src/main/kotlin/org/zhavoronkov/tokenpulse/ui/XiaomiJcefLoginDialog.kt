package org.zhavoronkov.tokenpulse.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefCookie
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import org.zhavoronkov.tokenpulse.utils.TokenPulseLogger
import java.awt.BorderLayout
import java.awt.Dimension
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * One-click Xiaomi sign-in via an embedded Chromium (JCEF) browser.
 *
 * The user logs in normally inside a real Chromium window inside the IDE.
 * Captcha / 2FA / passkeys all work as in a regular browser. When the browser
 * lands back on `platform.xiaomimimo.com/console/...`, we harvest cookies from
 * both `platform.xiaomimimo.com` (short-lived session) AND `account.xiaomi.com`
 * (long-lived `passToken` for silent re-login).
 *
 * JCEF's cookie manager can read HttpOnly cookies (unlike page JS or a hosted
 * "proxy page"), which is the key enabler: `passToken` is HttpOnly on
 * `Domain=.xiaomi.com`, so this is the only zero-install way to capture it.
 *
 * On IDE builds where [JBCefApp.isSupported] is false, this dialog cannot be
 * used — callers must gate on that flag and fall back to the cURL paste flow
 * ([XiaomiConnectDialog]).
 */
class XiaomiJcefLoginDialog : DialogWrapper(true) {

    /** Session JSON, populated on successful login. Null if the user cancelled. */
    var capturedSessionJson: String? = null
        private set

    private val browser: JBCefBrowser = JBCefBrowser.createBuilder()
        .setUrl(START_URL)
        .setEnableOpenDevToolsMenuItem(false)
        .build()

    private val statusLabel = JBLabel(STATUS_WAITING)
    private val captureButton = JButton("Capture session").apply {
        addActionListener { triggerHarvest("manual button") }
    }

    private val harvestInProgress = AtomicBoolean(false)
    private val captured = AtomicBoolean(false)

    init {
        title = "Sign in to Xiaomi"
        setOKButtonText("Done")
        isOKActionEnabled = false
        // Dispose the JCEF browser + client whenever this dialog is closed.
        Disposer.register(disposable, browser)
        installLoadHandler()
        init()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout())

        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JBLabel("<html><b>Log in to your Xiaomi account below.</b></html>"))
            add(
                JBLabel(
                    "<html>The session is captured automatically once the console loads. " +
                        "If it doesn't, click <b>Capture session</b>.</html>"
                )
            )
        }
        root.add(header, BorderLayout.NORTH)

        val browserComponent = browser.component.apply {
            preferredSize = Dimension(BROWSER_WIDTH, BROWSER_HEIGHT)
        }
        root.add(browserComponent, BorderLayout.CENTER)

        val footer = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(statusLabel)
            add(captureButton)
        }
        root.add(footer, BorderLayout.SOUTH)

        return root
    }

    private fun installLoadHandler() {
        // Auto-detect: whenever the top frame finishes loading a platform console URL,
        // harvest cookies. Runs on the CEF UI thread — dispatch work to a pool thread.
        val handler = object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain != true) return
                val url = browser?.url.orEmpty()
                if (looksLikeConsoleUrl(url)) {
                    triggerHarvest("auto-detect: $url")
                }
            }
        }
        browser.jbCefClient.addLoadHandler(handler, browser.cefBrowser)
    }

    private fun looksLikeConsoleUrl(url: String): Boolean {
        if (!url.startsWith("https://platform.xiaomimimo.com")) return false
        // Platform uses a hash router (#/console/balance), but we accept any path
        // under the host once the top-level document is at platform.xiaomimimo.com.
        return url.contains("/console") || url.contains("#/console") ||
            url.trimEnd('/').endsWith("platform.xiaomimimo.com")
    }

    private fun triggerHarvest(reason: String) {
        if (captured.get() || !harvestInProgress.compareAndSet(false, true)) return
        TokenPulseLogger.Provider.debug("Xiaomi JCEF: harvesting cookies ($reason)")
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val platform = readCookies(PLATFORM_URL)
                val passport = readCookies(ACCOUNT_URL)
                val sessionJson = XiaomiCookieHarvest.buildSessionJson(platform, passport)
                SwingUtilities.invokeLater { onHarvestFinished(sessionJson, platform, passport) }
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                TokenPulseLogger.Provider.debug("Xiaomi JCEF: harvest failed: ${e.message}")
                SwingUtilities.invokeLater { onHarvestFinished(null, emptyMap(), emptyMap()) }
            } finally {
                harvestInProgress.set(false)
            }
        }
    }

    /**
     * Read cookies for [url] from the browser's cookie manager, INCLUDING
     * HttpOnly cookies. The future-returning overload is used explicitly
     * because the synchronous overloads warn about freezes when called from
     * a browser callback.
     */
    private fun readCookies(url: String): Map<String, String> {
        val future = browser.jbCefCookieManager.getCookies(url, true) ?: return emptyMap()
        val cookies: List<JBCefCookie> = try {
            future.get(COOKIE_TIMEOUT_MS, TimeUnit.MILLISECONDS).orEmpty()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            TokenPulseLogger.Provider.debug("Xiaomi JCEF: getCookies($url) failed: ${e.message}")
            return emptyMap()
        }
        return cookies.associate { it.name to it.value }
    }

    private fun onHarvestFinished(
        sessionJson: String?,
        platform: Map<String, String>,
        passport: Map<String, String>
    ) {
        if (sessionJson == null) {
            statusLabel.text = STATUS_INCOMPLETE
            TokenPulseLogger.Provider.debug(
                "Xiaomi JCEF: incomplete cookies platform=${platform.keys} passport=${passport.keys}"
            )
            return
        }
        capturedSessionJson = sessionJson
        captured.set(true)
        statusLabel.text = if (passport["passToken"].isNullOrBlank()) STATUS_SUCCESS_NO_REFRESH else STATUS_SUCCESS
        isOKActionEnabled = true
    }

    companion object {
        // Landing on the console URL redirects through Xiaomi Passport when not authed,
        // and back to /console/balance once the user completes login.
        const val START_URL = "https://platform.xiaomimimo.com/#/console/balance"
        private const val PLATFORM_URL = "https://platform.xiaomimimo.com/"
        private const val ACCOUNT_URL = "https://account.xiaomi.com/"

        private const val BROWSER_WIDTH = 900
        private const val BROWSER_HEIGHT = 640
        private const val COOKIE_TIMEOUT_MS = 5_000L

        private const val STATUS_WAITING = "<html><i>Waiting for you to sign in…</i></html>"
        private const val STATUS_SUCCESS = "<html><font color='green'><b>✓ Session captured!</b></font></html>"
        private const val STATUS_SUCCESS_NO_REFRESH =
            "<html><font color='green'><b>✓ Session captured</b></font> " +
                "<font color='#B87333'>" +
                "(no passToken — auto-refresh disabled; you'll re-connect when it expires)" +
                "</font></html>"
        private const val STATUS_INCOMPLETE =
            "<html><font color='red'>Not signed in yet.</font> " +
                "Finish the Xiaomi login above, then click <b>Capture session</b>.</html>"

        /** True if JCEF is usable on this IDE build (JBR + platform support). */
        fun isSupported(): Boolean = JBCefApp.isSupported()
    }
}
