package org.zhavoronkov.tokenpulse.provider

import com.google.gson.Gson
import org.zhavoronkov.tokenpulse.utils.TokenPulseLogger

/**
 * Generic session parser for provider clients that use session-based authentication.
 * Parses a JSON secret string into a typed session object and validates required fields.
 */
object SessionParser {

    /**
     * Parse a JSON secret string into a session object.
     *
     * @param secret The JSON string to parse
     * @param sessionClass The class to parse into
     * @param validator Function to validate the parsed session has required fields
     * @param providerName Provider name for debug logging
     * @return Parsed session or null if parsing/validation fails
     */
    fun <T> parse(
        secret: String,
        sessionClass: Class<T>,
        validator: (T) -> Boolean,
        providerName: String,
        gson: Gson = Gson()
    ): T? {
        return try {
            val session = gson.fromJson(secret, sessionClass)
            if (validator(session)) session else null
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            TokenPulseLogger.Provider.debug("Failed to parse $providerName session: ${e.message}")
            null
        }
    }
}
