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
        // Constructed via the Java shim so the emitted bytecode binds to the plain
        // CredentialAttributes(String, String) JVM constructor. A Kotlin-side call would
        // route through the default-args synthetic ctor that 2026.1 (build 261) marks
        // @Deprecated(ERROR); see CredentialAttributesFactory for details.
        return CredentialAttributesFactory.create(generateServiceName("TokenPulse", accountId), accountId)
    }
}
