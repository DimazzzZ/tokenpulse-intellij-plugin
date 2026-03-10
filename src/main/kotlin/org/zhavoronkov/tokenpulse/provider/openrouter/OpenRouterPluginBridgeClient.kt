package org.zhavoronkov.tokenpulse.provider.openrouter

import com.intellij.ide.plugins.PluginManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.provider.ProviderClient
import org.zhavoronkov.tokenpulse.settings.Account
import org.zhavoronkov.tokenpulse.utils.TokenPulseLogger

/**
 * Provider client that bridges to the OpenRouter IntelliJ Plugin.
 *
 * This client uses reflection to access the OpenRouter plugin's services
 * and retrieve balance data without requiring a direct plugin dependency.
 *
 * Benefits:
 * - No need for user to configure API keys in TokenPulse
 * - Uses existing OpenRouter plugin authentication
 * - Stays synchronized with OpenRouter plugin's data
 *
 * Requirements:
 * - OpenRouter plugin (org.zhavoronkov.openrouter) must be installed and configured
 */
class OpenRouterPluginBridgeClient : ProviderClient {

    companion object {
        private const val OPENROUTER_PLUGIN_ID = "org.zhavoronkov.openrouter"
        private const val SETTINGS_SERVICE_CLASS = "org.zhavoronkov.openrouter.services.OpenRouterSettingsService"
        private const val SERVICE_CLASS = "org.zhavoronkov.openrouter.services.OpenRouterService"

        /**
         * Check if the OpenRouter plugin is installed.
         */
        fun isPluginInstalled(): Boolean {
            return try {
                val pluginId = PluginId.getId(OPENROUTER_PLUGIN_ID)
                PluginManager.isPluginInstalled(pluginId)
            } catch (e: Exception) {
                TokenPulseLogger.Provider.debug("OpenRouter plugin check failed: ${e.message}")
                false
            }
        }

        /**
         * Check if the OpenRouter plugin is installed AND configured with credentials.
         */
        fun isPluginConfigured(): Boolean {
            if (!isPluginInstalled()) return false

            return try {
                val hasProvisioningKey = getProvisioningKey()?.isNotBlank() == true
                val hasApiKey = getApiKey()?.isNotBlank() == true
                hasProvisioningKey || hasApiKey
            } catch (e: Exception) {
                TokenPulseLogger.Provider.debug("OpenRouter plugin config check failed: ${e.message}")
                false
            }
        }

        /**
         * Get the provisioning key from OpenRouter plugin settings.
         */
        fun getProvisioningKey(): String? {
            return try {
                val settingsService = getSettingsService() ?: return null
                val method = settingsService.javaClass.getMethod("getProvisioningKey")
                method.invoke(settingsService) as? String
            } catch (e: Exception) {
                TokenPulseLogger.Provider.debug("Failed to get OpenRouter provisioning key: ${e.message}")
                null
            }
        }

        /**
         * Get the API key from OpenRouter plugin settings.
         */
        fun getApiKey(): String? {
            return try {
                val settingsService = getSettingsService() ?: return null
                val method = settingsService.javaClass.getMethod("getApiKey")
                method.invoke(settingsService) as? String
            } catch (e: Exception) {
                TokenPulseLogger.Provider.debug("Failed to get OpenRouter API key: ${e.message}")
                null
            }
        }

        private fun getSettingsService(): Any? {
            return try {
                val serviceClass = Class.forName(SETTINGS_SERVICE_CLASS)
                val getInstanceMethod = serviceClass.getMethod("getInstance")
                getInstanceMethod.invoke(null)
            } catch (e: Exception) {
                TokenPulseLogger.Provider.debug("Failed to get OpenRouter settings service: ${e.message}")
                null
            }
        }

        private fun getService(): Any? {
            return try {
                val serviceClass = Class.forName(SERVICE_CLASS)
                ApplicationManager.getApplication().getService(serviceClass)
            } catch (e: Exception) {
                TokenPulseLogger.Provider.debug("Failed to get OpenRouter service: ${e.message}")
                null
            }
        }
    }

    override fun fetchBalance(account: Account, secret: String): ProviderResult {
        // Secret is ignored - we use the OpenRouter plugin's credentials
        return try {
            if (!isPluginInstalled()) {
                return ProviderResult.Failure.AuthError("OpenRouter plugin not installed")
            }

            val provisioningKey = getProvisioningKey()
            if (provisioningKey.isNullOrBlank()) {
                return ProviderResult.Failure.AuthError("OpenRouter plugin not configured with provisioning key")
            }

            // Use the regular OpenRouter provider client with the plugin's credentials
            val client = OpenRouterProviderClient()
            val result = client.fetchBalance(account, provisioningKey)

            // Wrap the result to indicate it came from the plugin bridge
            when (result) {
                is ProviderResult.Success -> {
                    // Add metadata indicating this is from the plugin
                    val snapshot = result.snapshot.copy(
                        metadata = result.snapshot.metadata + mapOf("source" to "openrouter-plugin")
                    )
                    ProviderResult.Success(snapshot)
                }
                else -> result
            }
        } catch (e: Exception) {
            TokenPulseLogger.Provider.warn("OpenRouter plugin bridge error: ${e.message}")
            ProviderResult.Failure.UnknownError("OpenRouter plugin bridge error: ${e.message}")
        }
    }

    override fun testCredentials(account: Account, secret: String): ProviderResult {
        return try {
            if (!isPluginInstalled()) {
                return ProviderResult.Failure.AuthError("OpenRouter plugin not installed")
            }

            if (!isPluginConfigured()) {
                return ProviderResult.Failure.AuthError("OpenRouter plugin not configured")
            }

            // Try to fetch balance as a test
            fetchBalance(account, secret)
        } catch (e: Exception) {
            ProviderResult.Failure.UnknownError("OpenRouter plugin test failed: ${e.message}")
        }
    }
}
