package org.zhavoronkov.tokenpulse.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import org.zhavoronkov.tokenpulse.provider.github.GitHubSecrets
import org.zhavoronkov.tokenpulse.utils.Constants.FONT_SIZE_SMALL
import org.zhavoronkov.tokenpulse.utils.Constants.PASSWORD_FIELD_COLUMNS
import java.awt.Font
import javax.swing.JButton
import javax.swing.JComponent

/**
 * Dialog for connecting GitHub Copilot personal billing (per-user usage).
 *
 * Captures a Personal Access Token (with billing read permission) and the
 * GitHub username. Both are required for the `/users/{username}/settings/billing/...`
 * endpoints.
 *
 * To create a PAT with billing read permission:
 * 1. Go to https://github.com/settings/tokens?type=beta
 * 2. Create a new fine-grained PAT with "Plan" (billing) read permission, or
 *    a classic PAT with `read:user` + billing scope.
 * 3. Enter your GitHub username and paste the PAT below.
 */
class GitHubCopilotConnectDialog : DialogWrapper(true) {

    companion object {
        private const val GITHUB_TOKENS_URL = "https://github.com/settings/tokens?type=beta"
        private const val STATUS_WAITING =
            "<html><i>Enter your GitHub username and PAT (fine-grained or classic)</i></html>"
        private const val STATUS_SUCCESS = "<html><font color='green'><b>✓ Credentials captured!</b></font></html>"
        private const val STATUS_EMPTY = "<html><font color='red'>Please fill in both username and PAT.</font></html>"
        private const val STEP2_HTML =
            "<html><b>Step 2:</b> Use fine-grained PAT with \"Plan\" read permission,<br>" +
                "or classic PAT with <code>read:user</code> + billing scope.</html>"
    }

    var capturedSecretJson: String? = null
        private set

    private val statusLabel = JBLabel(STATUS_WAITING)

    private val usernameField = JBTextField().apply {
        columns = 20
        toolTipText = "Your GitHub username (e.g., 'octocat')"
    }

    private val patField = JBPasswordField().apply {
        columns = PASSWORD_FIELD_COLUMNS
        font = Font(Font.MONOSPACED, Font.PLAIN, FONT_SIZE_SMALL)
        toolTipText = "Personal Access Token with billing read permission"
    }

    private val captureButton = JButton("Capture").apply {
        addActionListener { attemptCapture() }
    }

    private val openBrowserButton = JButton("Generate Token").apply {
        addActionListener { BrowserUtil.browse(GITHUB_TOKENS_URL) }
    }

    init {
        title = "Connect GitHub Copilot (Personal)"
        setOKButtonText("Connect")
        isOKActionEnabled = false
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            label("<html><b>GitHub Copilot Personal Billing</b></html>")
        }
        row {
            label("<html>Tracks your per-user Copilot premium-request spend.</html>")
        }
        row {
            label("<html><b>Step 1:</b> Create a Personal Access Token</html>")
        }
        row {
            cell(openBrowserButton)
        }
        row {
            label(STEP2_HTML)
        }
        row {
            label("<html><b>Step 3:</b> Enter your GitHub username and paste the PAT:</html>")
        }
        row("Username:") {
            cell(usernameField).align(AlignX.FILL)
        }
        row("PAT:") {
            cell(patField).align(AlignX.FILL)
        }
        row {
            cell(captureButton)
            cell(statusLabel).align(AlignX.FILL)
        }
    }

    override fun getPreferredFocusedComponent() = usernameField

    private fun attemptCapture() {
        val username = usernameField.text.trim()
        val pat = String(patField.password).trim()

        if (username.isEmpty() || pat.isEmpty()) {
            statusLabel.text = STATUS_EMPTY
            return
        }

        capturedSecretJson = GitHubSecrets.encodeCopilot(pat, username)
        statusLabel.text = STATUS_SUCCESS
        isOKActionEnabled = true
    }
}
