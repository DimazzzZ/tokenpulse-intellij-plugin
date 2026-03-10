package org.zhavoronkov.tokenpulse.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service

/**
 * Secure credential storage for API keys and OAuth tokens.
 *
 * Uses IntelliJ's PasswordSafe for secure storage.
 * PasswordSafe handles threading internally.
 */
@Service(Service.Level.APP)
class CredentialsStore {
    companion object {
        fun getInstance(): CredentialsStore = service()
    }

    fun saveApiKey(accountId: String, apiKey: String) {
        val attributes = createAttributes(accountId)
        PasswordSafe.instance.setPassword(attributes, apiKey)
    }

    fun getApiKey(accountId: String): String? {
        val attributes = createAttributes(accountId)
        return PasswordSafe.instance.getPassword(attributes)
    }

    fun removeApiKey(accountId: String) {
        val attributes = createAttributes(accountId)
        PasswordSafe.instance.setPassword(attributes, null)
    }

    private fun createAttributes(accountId: String): CredentialAttributes {
        return CredentialAttributes(
            serviceName = generateServiceName("TokenPulse", accountId),
            userName = accountId
        )
    }
}
