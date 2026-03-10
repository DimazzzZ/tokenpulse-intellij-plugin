package org.zhavoronkov.tokenpulse.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import org.zhavoronkov.tokenpulse.provider.openai.chatgpt.ChatGptOAuthManager
import org.zhavoronkov.tokenpulse.utils.TokenPulseLogger
import java.util.concurrent.CompletableFuture
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.SwingUtilities

/**
 * Dialog for connecting ChatGPT subscription via OAuth.
 *
 * Uses the OpenAI Codex OAuth flow (same as Cline):
 * 1. Clicks "Sign in with ChatGPT" button
 * 2. Browser opens to OpenAI's auth page
 * 3. User signs in with their ChatGPT account
 * 4. OpenAI redirects back to localhost:1455
 * 5. Plugin exchanges code for tokens
 * 6. Tokens are stored and auto-refreshed
 */
class ChatGptConnectDialog : DialogWrapper(true) {

    companion object {
        const val CHATGPT_URL = "https://chatgpt.com"

        private const val STATUS_READY =
            "<html><i>Click the button below to sign in with your ChatGPT account.</i></html>"
        private const val STATUS_WAITING =
            "<html><font color='blue'>Waiting for sign-in... Check your browser.</font></html>"
        private const val STATUS_SUCCESS =
            "<html><font color='green'><b>✓ Successfully connected!</b></font></html>"
        private const val STATUS_ERROR =
            "<html><font color='red'>Authentication failed. Please try again.</font></html>"
        private const val STATUS_ALREADY_CONNECTED =
            "<html><font color='green'><b>✓ Already connected</b></font></html>"
    }

    /** Returns true if authentication completed successfully. */
    var isConnected: Boolean = false
        private set

    /** The email of the connected account (if available). */
    var connectedEmail: String? = null
        private set

    private val oauthManager = ChatGptOAuthManager.getInstance()
    private var authFuture: CompletableFuture<ChatGptOAuthManager.ChatGptCredentials>? = null

    private val statusLabel = JBLabel(STATUS_READY)

    private val signInButton = JButton("Sign in with ChatGPT →").apply {
        addActionListener { startOAuthFlow() }
    }

    private val disconnectButton = JButton("Disconnect").apply {
        isVisible = false
        addActionListener { disconnect() }
    }

    init {
        title = "Connect ChatGPT Subscription"
        setOKButtonText("Done")

        // Check if we have a valid session (not just stored credentials)
        // This ensures we don't show stale "connected as <email>" for expired tokens
        if (oauthManager.hasValidSession()) {
            connectedEmail = oauthManager.getEmail()
            isConnected = true
            statusLabel.text = if (connectedEmail != null) {
                "<html><font color='green'><b>✓ Connected as $connectedEmail</b></font></html>"
            } else {
                STATUS_ALREADY_CONNECTED
            }
            signInButton.isVisible = false
            disconnectButton.isVisible = true
            isOKActionEnabled = true
        } else {
            // Clear any stale credentials that might exist
            if (oauthManager.isAuthenticated()) {
                oauthManager.clearCredentials()
            }
            isOKActionEnabled = false
        }

        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            label("<html><b>ChatGPT Connection</b></html>")
        }
        row {
            label(
                """<html>
                Sign in with your ChatGPT account to track subscription usage.
                Uses official OAuth flow (secure).
                </html>
                """.trimIndent()
            )
        }
        separator()
        row {
            cell(signInButton)
            cell(disconnectButton)
        }
        row {
            cell(statusLabel).align(AlignX.FILL).resizableColumn()
        }
        row {
            label(
                """<html>
                <p style="color:gray;font-size:small">
                Works with ChatGPT Plus, Pro, and Team subscriptions.
                </p>
                </html>
                """.trimIndent()
            )
        }
    }

    private fun startOAuthFlow() {
        signInButton.isEnabled = false
        statusLabel.text = STATUS_WAITING

        try {
            authFuture = oauthManager.startAuthorizationFlow()

            // Handle completion on EDT
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val credentials = authFuture?.get()
                    SwingUtilities.invokeLater {
                        if (credentials != null) {
                            isConnected = true
                            connectedEmail = credentials.email
                            statusLabel.text = if (credentials.email != null) {
                                "<html><font color='green'><b>✓ Connected as ${credentials.email}</b></font></html>"
                            } else {
                                STATUS_SUCCESS
                            }
                            signInButton.isVisible = false
                            disconnectButton.isVisible = true
                            isOKActionEnabled = true
                        } else {
                            statusLabel.text = STATUS_ERROR
                            signInButton.isEnabled = true
                        }
                    }
                } catch (e: Exception) {
                    TokenPulseLogger.Provider.error("OAuth flow failed", e)
                    SwingUtilities.invokeLater {
                        statusLabel.text = "<html><font color='red'>Error: ${e.message}</font></html>"
                        signInButton.isEnabled = true
                    }
                }
            }
        } catch (e: Exception) {
            TokenPulseLogger.Provider.error("Failed to start OAuth flow", e)
            statusLabel.text = "<html><font color='red'>Failed to start: ${e.message}</font></html>"
            signInButton.isEnabled = true
        }
    }

    private fun disconnect() {
        // Clear credentials off EDT to avoid SlowOperations error
        ApplicationManager.getApplication().executeOnPooledThread {
            oauthManager.clearCredentials()
            SwingUtilities.invokeLater {
                isConnected = false
                connectedEmail = null
                statusLabel.text = STATUS_READY
                signInButton.isVisible = true
                signInButton.isEnabled = true
                disconnectButton.isVisible = false
                isOKActionEnabled = false
            }
        }
    }

    override fun doCancelAction() {
        // Cancel any pending OAuth flow
        oauthManager.cancelAuthorizationFlow()
        super.doCancelAction()
    }
}
