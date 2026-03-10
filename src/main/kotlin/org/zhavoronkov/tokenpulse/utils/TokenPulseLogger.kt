package org.zhavoronkov.tokenpulse.utils

import com.intellij.openapi.diagnostic.Logger

/**
 * Centralized logging utility for the TokenPulse plugin.
 * Provides configurable debug logging that can be enabled/disabled for development vs production.
 */
object TokenPulseLogger {

    private const val PLUGIN_NAME = "TokenPulse β"
    private const val LOG_PREFIX = "[$PLUGIN_NAME]"
    private const val DEBUG_PREFIX = "[$PLUGIN_NAME][DEBUG]"

    // Logger instances for different components - lazy to support test environments
    private val serviceLogger by lazy { createLogger("org.zhavoronkov.tokenpulse.service") }
    private val providerLogger by lazy { createLogger("org.zhavoronkov.tokenpulse.provider") }
    private val settingsLogger by lazy { createLogger("org.zhavoronkov.tokenpulse.settings") }
    private val uiLogger by lazy { createLogger("org.zhavoronkov.tokenpulse.ui") }
    private val coreLogger by lazy { createLogger("org.zhavoronkov.tokenpulse.core") }

    // Debug mode flag - can be controlled via system property or IDE debug category
    private val debugEnabled: Boolean
        get() = System.getProperty("tokenpulse.debug", "false").toBoolean() ||
            System.getProperty("idea.log.debug.categories", "").contains("org.zhavoronkov.tokenpulse")

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
     * Reusable logging facade that eliminates code duplication across logger categories.
     * Each instance wraps a specific Logger and provides consistent formatting.
     */
    class LoggerFacade(private val loggerProvider: () -> Logger?) {
        private val logger: Logger? get() = loggerProvider()

        fun info(message: String) {
            if (!testMode) logger?.info("$LOG_PREFIX $message")
        }

        fun warn(message: String) {
            if (!testMode) logger?.warn("$LOG_PREFIX $message")
        }

        fun warn(message: String, throwable: Throwable) {
            if (!testMode) logger?.warn("$LOG_PREFIX $message", throwable)
        }

        fun error(message: String) {
            if (!testMode) logger?.error("$LOG_PREFIX $message")
        }

        fun error(message: String, throwable: Throwable) {
            if (!testMode) logger?.error("$LOG_PREFIX $message", throwable)
        }

        fun debug(message: String) {
            if (debugEnabled) {
                logger?.info("$DEBUG_PREFIX $message")
            }
        }

        fun debug(message: String, throwable: Throwable) {
            if (debugEnabled) {
                logger?.info("$DEBUG_PREFIX $message", throwable)
            }
        }
    }

    /** Service-related logging (BalanceRefreshService, RefreshCoordinator, etc.) */
    val Service = LoggerFacade { serviceLogger }

    /** Provider-related logging (NebiusProviderClient, OpenRouterProviderClient, ClineProviderClient) */
    val Provider = LoggerFacade { providerLogger }

    /** Settings-related logging (TokenPulseSettingsService, CredentialsStore, etc.) */
    val Settings = LoggerFacade { settingsLogger }

    /** UI-related logging (Dialogs, Widgets, Configurable, etc.) */
    val UI = LoggerFacade { uiLogger }

    /** Core-related logging (OAuth, Auth, Usage providers in core package) */
    val Core = LoggerFacade { coreLogger }

    /**
     * Structured trace logging for multi-step operations.
     * Automatically redacts sensitive fields in the data map.
     * Skips non-string types and already redacted values to maintain diagnostic clarity.
     */
    fun trace(
        provider: String,
        accountId: String,
        traceId: String,
        stage: String,
        message: String,
        data: Map<String, Any?> = emptyMap()
    ) {
        if (!debugEnabled) return

        val redactedData = data.mapValues { (key, value) ->
            if (value is String) {
                val lowerKey = key.lowercase()
                val isSensitiveKey = lowerKey.contains("cookie") || lowerKey.contains("token") ||
                    lowerKey.contains("auth") || lowerKey.contains("session") ||
                    lowerKey.contains("secret") || lowerKey.contains("key")

                if (isSensitiveKey && !value.startsWith("[REDACTED")) {
                    "[REDACTED length=${value.length}]"
                } else {
                    value
                }
            } else {
                value
            }
        }

        val dataStr = if (redactedData.isEmpty()) {
            ""
        } else {
            " data={${redactedData.entries.joinToString { "${it.key}=${it.value}" }}}"
        }
        providerLogger?.info(
            "$LOG_PREFIX[TRACE] traceId=$traceId provider=$provider account=$accountId stage=$stage $message$dataStr"
        )
    }

    /**
     * Check if debug logging is enabled
     */
    fun isDebugEnabled(): Boolean = debugEnabled

    /**
     * Log debug information about the current logging configuration
     */
    fun logConfiguration() {
        serviceLogger?.info("$LOG_PREFIX Debug logging enabled: $debugEnabled")
        if (debugEnabled) {
            serviceLogger?.info("$LOG_PREFIX Debug logging configuration:")
            serviceLogger?.info(
                "$LOG_PREFIX   - tokenpulse.debug system property: ${System.getProperty(
                    "tokenpulse.debug",
                    "not set"
                )}"
            )
            serviceLogger?.info(
                "$LOG_PREFIX   - idea.log.debug.categories: ${System.getProperty(
                    "idea.log.debug.categories",
                    "not set"
                )}"
            )
        }
    }
}
