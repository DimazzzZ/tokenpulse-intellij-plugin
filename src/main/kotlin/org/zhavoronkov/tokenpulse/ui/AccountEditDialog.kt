package org.zhavoronkov.tokenpulse.ui

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import org.zhavoronkov.tokenpulse.model.ProviderId
import org.zhavoronkov.tokenpulse.settings.Account
import org.zhavoronkov.tokenpulse.settings.AuthType
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent

class AccountEditDialog(
    private val account: Account?,
    private val existingApiKey: String?
) : DialogWrapper(true) {

    private val nameField = JBTextField(account?.name ?: "")
    private val providerCombo = com.intellij.openapi.ui.ComboBox(DefaultComboBoxModel(ProviderId.values()))
    private val authTypeCombo = com.intellij.openapi.ui.ComboBox(DefaultComboBoxModel(AuthType.values()))
    private val apiKeyField = JBPasswordField().apply { text = existingApiKey ?: "" }

    init {
        title = if (account == null) "Add Account" else "Edit Account"
        
        providerCombo.addActionListener {
            updateAuthTypes()
        }
        
        if (account != null) {
            providerCombo.selectedItem = account.providerId
            updateAuthTypes()
            authTypeCombo.selectedItem = account.authType
        } else {
            updateAuthTypes()
        }
        
        init()
    }

    private fun updateAuthTypes() {
        val selectedProvider = providerCombo.selectedItem as ProviderId
        val types = AuthType.values().filter { it.name.startsWith(selectedProvider.name) }
        authTypeCombo.model = DefaultComboBoxModel(types.toTypedArray())
    }

    override fun createCenterPanel(): JComponent {
        return panel {
            row("Name:") {
                cell(nameField).columns(COLUMNS_MEDIUM)
            }
            row("Provider:") {
                cell(providerCombo)
            }
            row("Auth Type:") {
                cell(authTypeCombo)
            }
            row("API/Provisioning Key:") {
                cell(apiKeyField).columns(COLUMNS_MEDIUM)
            }
        }
    }

    fun getAccountName(): String = nameField.text.trim()
    fun getProvider(): ProviderId = providerCombo.selectedItem as ProviderId
    fun getAuthType(): AuthType = authTypeCombo.selectedItem as AuthType
    fun getApiKey(): String = String(apiKeyField.password)

    override fun createActions(): Array<javax.swing.Action> {
        val testAction = object : javax.swing.AbstractAction("Test Connection") {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                val apiKey = getApiKey()
                val providerId = getProvider()
                val authType = getAuthType()
                val name = getAccountName()
                
                // Create a temporary account for testing
                val tempAccount = Account(
                    id = account?.id ?: "test-account",
                    name = name.ifBlank { "Test" },
                    providerId = providerId,
                    authType = authType
                )
                
                com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
                    val client = org.zhavoronkov.tokenpulse.provider.ProviderFactory.getClient(providerId)
                    val result = client.testCredentials(tempAccount, apiKey)
                    
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                        when (result) {
                            is org.zhavoronkov.tokenpulse.model.ProviderResult.Success -> {
                                com.intellij.openapi.ui.Messages.showInfoMessage(rootPane, "Connection successful!", "Test Connection")
                            }
                            is org.zhavoronkov.tokenpulse.model.ProviderResult.Failure -> {
                                com.intellij.openapi.ui.Messages.showErrorDialog(rootPane, "Connection failed: ${result.message}", "Test Connection")
                            }
                        }
                    }
                }
            }
        }
        return arrayOf(testAction, okAction, cancelAction)
    }
}
