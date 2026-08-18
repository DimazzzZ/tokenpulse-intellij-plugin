package org.zhavoronkov.tokenpulse.ui

import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import org.zhavoronkov.tokenpulse.model.ConnectionType
import javax.swing.JButton

/**
 * Helper for AccountEditDialog to manage GitHub Copilot provider UI state.
 *
 * Owns:
 * - Connect buttons and status labels for both personal and org billing
 * - Captured secret state for both types
 * - Dialog-opener and validation logic
 *
 * This extraction keeps AccountEditDialog under the function count threshold.
 */
internal class GitHubProviderPanel(private val existingSecret: String?) {

    val copilotConnectButton = JButton("Connect GitHub Copilot →").apply {
        addActionListener { openGitHubCopilotConnectDialog() }
    }

    val copilotStatusLabel = JBLabel(
        if (!existingSecret.isNullOrBlank()) {
            "<html><font color='green'>✓ Connected</font></html>"
        } else {
            "<html><i>Not connected</i></html>"
        }
    )

    val orgConnectButton = JButton("Connect GitHub Copilot Org →").apply {
        addActionListener { openGitHubCopilotOrgConnectDialog() }
    }

    val orgStatusLabel = JBLabel(
        if (!existingSecret.isNullOrBlank()) {
            "<html><font color='green'>✓ Connected</font></html>"
        } else {
            "<html><i>Not connected</i></html>"
        }
    )

    var capturedCopilotSecret: String? = existingSecret
    var capturedOrgSecret: String? = existingSecret

    fun getSecret(connectionType: ConnectionType): String = when (connectionType) {
        ConnectionType.GITHUB_COPILOT_PAT -> capturedCopilotSecret ?: ""
        ConnectionType.GITHUB_COPILOT_ORG_BUDGET -> capturedOrgSecret ?: ""
        else -> ""
    }

    fun validate(connectionType: ConnectionType): ValidationInfo? = when (connectionType) {
        ConnectionType.GITHUB_COPILOT_PAT -> validateCopilot()
        ConnectionType.GITHUB_COPILOT_ORG_BUDGET -> validateOrg()
        else -> null
    }

    private fun openGitHubCopilotConnectDialog() {
        val dialog = GitHubCopilotConnectDialog()
        if (dialog.showAndGet()) {
            val json = dialog.capturedSecretJson
            if (!json.isNullOrBlank()) {
                capturedCopilotSecret = json
                copilotStatusLabel.text =
                    "<html><font color='green'><b>✓ Connected</b></font></html>"
            }
        }
    }

    private fun openGitHubCopilotOrgConnectDialog() {
        val dialog = GitHubCopilotOrgConnectDialog()
        if (dialog.showAndGet()) {
            val json = dialog.capturedSecretJson
            if (!json.isNullOrBlank()) {
                capturedOrgSecret = json
                orgStatusLabel.text =
                    "<html><font color='green'><b>✓ Connected</b></font></html>"
            }
        }
    }

    private fun validateCopilot(): ValidationInfo? {
        if (capturedCopilotSecret.isNullOrBlank()) {
            return ValidationInfo(
                "Please connect your GitHub Copilot account first.",
                copilotConnectButton
            )
        }
        return null
    }

    private fun validateOrg(): ValidationInfo? {
        if (capturedOrgSecret.isNullOrBlank()) {
            return ValidationInfo(
                "Please connect your GitHub Copilot org first.",
                orgConnectButton
            )
        }
        return null
    }
}
