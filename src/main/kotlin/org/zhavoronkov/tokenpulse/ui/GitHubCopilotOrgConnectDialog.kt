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
 * Dialog for connecting GitHub Copilot organization billing (budgets).
 *
 * Captures a Personal Access Token (with org admin / billing-manager permissions)
 * and the GitHub organization name. Both are required for the
 * `/organizations/{org}/settings/billing/budgets` endpoint.
 *
 * To create a PAT with org admin/billing-manager permissions:
 * 1. Go to https://github.com/settings/tokens?type=beta
 * 2. Create a new fine-grained PAT with org admin or billing-manager role, or
 *    a classic PAT with `admin:org_hook` scope.
 * 3. Enter your org name and paste the PAT below.
 */
class GitHubCopilotOrgConnectDialog : DialogWrapper(true) {

    companion object {
        private const val GITHUB_TOKENS_URL = "https://github.com/settings/tokens?type=beta"
        private const val STATUS_WAITING = "<html><i>Enter your org name and an admin/billing-manager PAT</i></html>"
        private const val STATUS_SUCCESS = "<html><font color='green'><b>✓ Credentials captured!</b></font></html>"
        private const val STATUS_EMPTY = "<html><font color='red'>Please fill in both org name and PAT.</font></html>"
        private const val STEP2_HTML =
            "<html><b>Step 2:</b> Use fine-grained PAT with org admin/billing-manager role,<br>" +
                "or classic PAT with <code>admin:org_hook</code> scope.</html>"
    }

    var capturedSecretJson: String? = null
        private set

    private val statusLabel = JBLabel(STATUS_WAITING)

    private val orgField = JBTextField().apply {
        columns = 20
        toolTipText = "Your GitHub organization name (e.g., 'my-company')"
    }

    private val patField = JBPasswordField().apply {
        columns = PASSWORD_FIELD_COLUMNS
        font = Font(Font.MONOSPACED, Font.PLAIN, FONT_SIZE_SMALL)
        toolTipText = "Personal Access Token with org admin/billing-manager permissions"
    }

    private val captureButton = JButton("Capture").apply {
        addActionListener { attemptCapture() }
    }

    private val openBrowserButton = JButton("Generate Token").apply {
        addActionListener { BrowserUtil.browse(GITHUB_TOKENS_URL) }
    }

    init {
        title = "Connect GitHub Copilot (Organization)"
        setOKButtonText("Connect")
        isOKActionEnabled = false
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            label("<html><b>GitHub Copilot Organization Billing</b></html>")
        }
        row {
            label("<html>Tracks your org's Copilot budget limits and consumption.</html>")
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
            label("<html><b>Step 3:</b> Enter your org name and paste the PAT:</html>")
        }
        row("Org:") {
            cell(orgField).align(AlignX.FILL)
        }
        row("PAT:") {
            cell(patField).align(AlignX.FILL)
        }
        row {
            cell(captureButton)
            cell(statusLabel).align(AlignX.FILL)
        }
    }

    override fun getPreferredFocusedComponent() = orgField

    private fun attemptCapture() {
        val org = orgField.text.trim()
        val pat = String(patField.password).trim()

        if (org.isEmpty() || pat.isEmpty()) {
            statusLabel.text = STATUS_EMPTY
            return
        }

        capturedSecretJson = GitHubSecrets.encodeOrgBudget(pat, org)
        statusLabel.text = STATUS_SUCCESS
        isOKActionEnabled = true
    }
}
