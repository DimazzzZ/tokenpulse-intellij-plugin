package org.zhavoronkov.tokenpulse.utils

import com.intellij.openapi.diagnostic.Logger

/**
 * Centralized logging utility for the TokenPulse plugin.
 * Provides configurable debug logging that can be enabled/disabled for development vs production.
 */
object TokenPulseLogger {

    private const val PLUGIN_NAME = "TokenPulse"

    // Logger instances for different components - lazy to support test environments
    private val serviceLogger by lazy { createLogger("org.zhavoronkov.tokenpulse.service") }
    private val providerLogger by lazy { createLogger("org.zhavoronkov.tokenpulse.provider") }
    private val settingsLogger by lazy { createLogger("org.zhavoronkov.tokenpulse.settings") }
    private val uiLogger by lazy { createLogger("org.zhavoronkov.tokenpulse.ui") }

    // Debug mode flag - can be controlled via system property
    private val debugEnabled: Boolean by lazy {
        System.getProperty("tokenpulse.debug", "false").toBoolean() ||
            System.getProperty("idea.log.debug.categories", "").contains("org.zhavoronkov.tokenpulse")
    }

    private val testMode: Boolean by lazy {
        System.getProperty("tokenpulse.testMode", "false").toBoolean()
    }

    private fun createLogger(category: String): Logger? {
        return try {
            Logger.getInstance(category)
        } catch (e: IllegalStateException) {
            // Test environment - Logger not available
            null
        } catch (e: NoClassDefFoundError) {
            // Test environment - Logger class missing
            null
        } catch (e: Exception) {
            // Any other exception - Logger unavailable
            null
        }
    }

    /**
     * Service-related logging (BalanceRefreshService, RefreshCoordinator, etc.)
     */
    object Service {
        fun info(message: String) {
            if (!testMode) serviceLogger?.info("[$PLUGIN_NAME] $message")
        }
        fun warn(message: String) {
            if (!testMode) serviceLogger?.warn("[$PLUGIN_NAME] $message")
        }

        fun warn(message: String, throwable: Throwable) {
            if (!testMode) serviceLogger?.warn("[$PLUGIN_NAME] $message", throwable)
        }

        fun error(message: String) {
            if (!testMode) serviceLogger?.error("[$PLUGIN_NAME] $message")
        }

        fun error(message: String, throwable: Throwable) {
            if (!testMode) serviceLogger?.error("[$PLUGIN_NAME] $message", throwable)
        }

        fun debug(message: String) {
            if (debugEnabled) {
                serviceLogger?.info("[$PLUGIN_NAME][DEBUG] $message")
            }
        }

        fun debug(message: String, throwable: Throwable) {
            if (debugEnabled) {
                serviceLogger?.info("[$PLUGIN_NAME][DEBUG] $message", throwable)
            }
        }
    }

    /**
     * Provider-related logging (NebiusProviderClient, OpenRouterProviderClient, ClineProviderClient)
     */
    object Provider {
        fun info(message: String) {
            if (!testMode) providerLogger?.info("[$PLUGIN_NAME] $message")
        }
        fun warn(message: String) {
            if (!testMode) providerLogger?.warn("[$PLUGIN_NAME] $message")
        }

        fun warn(message: String, throwable: Throwable) {
            if (!testMode) providerLogger?.warn("[$PLUGIN_NAME] $message", throwable)
        }

        fun error(message: String) {
            if (!testMode) providerLogger?.error("[$PLUGIN_NAME] $message")
        }

        fun error(message: String, throwable: Throwable) {
            if (!testMode) providerLogger?.error("[$PLUGIN_NAME] $message", throwable)
        }

        fun debug(message: String) {
            if (debugEnabled) {
                providerLogger?.info("[$PLUGIN_NAME][DEBUG] $message")
            }
        }

        fun debug(message: String, throwable: Throwable) {
            if (debugEnabled) {
                providerLogger?.info("[$PLUGIN_NAME][DEBUG] $message", throwable)
            }
        }
    }

    /**
     * Settings-related logging (TokenPulseSettingsService, CredentialsStore, etc.)
     */
    object Settings {
        fun info(message: String) {
            if (!testMode) settingsLogger?.info("[$PLUGIN_NAME] $message")
        }

        fun warn(message: String) {
            if (!testMode) settingsLogger?.warn("[$PLUGIN_NAME] $message")
        }

        fun warn(message: String, throwable: Throwable) {
            if (!testMode) settingsLogger?.warn("[$PLUGIN_NAME] $message", throwable)
        }

        fun error(message: String) {
            if (!testMode) settingsLogger?.error("[$PLUGIN_NAME] $message")
        }

        fun error(message: String, throwable: Throwable) {
            if (!testMode) settingsLogger?.error("[$PLUGIN_NAME] $message", throwable)
        }

        fun debug(message: String) {
            if (debugEnabled) {
                settingsLogger?.info("[$PLUGIN_NAME][DEBUG] $message")
            }
        }

        fun debug(message: String, throwable: Throwable) {
            if (debugEnabled) {
                settingsLogger?.info("[$PLUGIN_NAME][DEBUG] $message", throwable)
            }
        }
    }

    /**
     * UI-related logging (Dialogs, Widgets, Configurable, etc.)
     */
    object UI {
        fun info(message: String) {
            if (!testMode) uiLogger?.info("[$PLUGIN_NAME] $message")
        }
        fun warn(message: String) {
            if (!testMode) uiLogger?.warn("[$PLUGIN_NAME] $message")
        }
        fun warn(message: String, throwable: Throwable) {
            if (!testMode) uiLogger?.warn("[$PLUGIN_NAME] $message", throwable)
        }
        fun error(message: String) {
            if (!testMode) uiLogger?.error("[$PLUGIN_NAME] $message")
        }
        fun error(message: String, throwable: Throwable) {
            if (!testMode) uiLogger?.error("[$PLUGIN_NAME] $message", throwable)
        }

        fun debug(message: String) {
            if (debugEnabled) {
                uiLogger?.info("[$PLUGIN_NAME][DEBUG] $message")
            }
        }
    }

    /**
     * Check if debug logging is enabled
     */
    fun isDebugEnabled(): Boolean = debugEnabled

    /**
     * Log debug information about the current logging configuration
     */
    fun logConfiguration() {
        serviceLogger?.info("[$PLUGIN_NAME] Debug logging enabled: $debugEnabled")
        if (debugEnabled) {
            serviceLogger?.info("[$PLUGIN_NAME] Debug logging configuration:")
            serviceLogger?.info(
                "[$PLUGIN_NAME]   - tokenpulse.debug system property: ${System.getProperty(
                    "tokenpulse.debug",
                    "not set"
                )}"
            )
            serviceLogger?.info(
                "[$PLUGIN_NAME]   - idea.log.debug.categories: ${System.getProperty(
                    "idea.log.debug.categories",
                    "not set"
                )}"
            )
        }
    }
}
