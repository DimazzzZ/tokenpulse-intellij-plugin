package org.zhavoronkov.tokenpulse.utils

/**
 * Extracts cookies from cURL commands copied from browser DevTools.
 * Handles both single-quoted and double-quoted cookie values.
 * Shared across session-based provider dialogs (Nebius, Xiaomi, etc.)
 */
object CurlCookieExtractor {

    /**
     * Extract a quoted value after a cURL flag (e.g., -b '...' or -b "...").
     * Tries single quotes first (allows double quotes inside), then double quotes.
     *
     * @param text The cURL command text
     * @param flag The flag character (e.g., 'b' for -b, 'c' for -c)
     * @return The extracted value or null if not found
     */
    fun extractQuotedValue(text: String, flag: Char): String? {
        val singleQuotePattern = Regex("""-$flag\s+'([^']+)'""")
        singleQuotePattern.find(text)?.let { return it.groupValues[1] }

        val doubleQuotePattern = Regex("""-$flag\s+"([^"]+)"""")
        doubleQuotePattern.find(text)?.let { return it.groupValues[1] }

        return null
    }

    /**
     * Extract cookies from -b or --cookie flag in a cURL command.
     * Returns the raw cookie string (e.g., "cookie1=val1; cookie2=val2").
     *
     * @param text The cURL command text
     * @return The cookie string or null if not found
     */
    fun extractCookieString(text: String): String? {
        // Try -b flag first
        extractQuotedValue(text, 'b')?.let { return it }

        // Try --cookie flag or -H "Cookie: ..." header
        val patterns = listOf(
            """--cookie\s+'([^']+)'""",
            """--cookie\s+"([^"]+)"""",
            """-H\s+'[Cc]ookie:\s*([^']+)'""",
            """-H\s+"[Cc]ookie:\s*([^"]+)""""
        )

        for (pattern in patterns) {
            Regex(pattern).find(text)?.let { return it.groupValues[1] }
        }

        return null
    }

    /**
     * Parse a cookie string into a map of cookie name-value pairs.
     * Handles quoted values (removes surrounding quotes).
     *
     * @param cookieString The raw cookie string (e.g., "cookie1=val1; cookie2=val2")
     * @return Map of cookie names to values
     */
    fun parseCookieString(cookieString: String): Map<String, String> {
        val cookies = mutableMapOf<String, String>()

        for (cookie in cookieString.split(";")) {
            val eqIndex = cookie.indexOf('=')
            if (eqIndex < 0) continue

            val name = cookie.substring(0, eqIndex).trim()
            val value = cookie.substring(eqIndex + 1).trim()
                .removeSurrounding("\"")
                .removeSurrounding("'")

            if (name.isNotEmpty()) {
                cookies[name] = value
            }
        }

        return cookies
    }
}
