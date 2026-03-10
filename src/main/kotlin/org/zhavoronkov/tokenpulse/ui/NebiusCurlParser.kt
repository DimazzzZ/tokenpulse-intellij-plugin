package org.zhavoronkov.tokenpulse.ui

import org.zhavoronkov.tokenpulse.provider.nebius.NebiusProviderClient
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Parses cURL commands copied from browser DevTools into Nebius session data.
 * This handles the "Copy as cURL" format from Chrome and Firefox DevTools.
 */
object NebiusCurlParser {

    /**
     * Parse a "Copy as cURL" string into a [NebiusProviderClient.NebiusSession].
     * Returns null if parsing fails.
     */
    fun parseCurl(curl: String): NebiusProviderClient.NebiusSession? {
        return try {
            val normalized = normalizeCurl(curl)
            val cookieStr = extractCookies(normalized)
            val (appSession, csrfCookie) = extractSessionCookies(cookieStr)
            val csrfToken = extractCsrfToken(normalized, csrfCookie)
            val parentId = extractParentId(normalized)
            val headers = extractAllHeaders(normalized)
            val body = extractRequestBody(normalized)
            val path = extractRequestPath(normalized)

            NebiusProviderClient.NebiusSession(
                appSession = appSession,
                csrfCookie = csrfCookie,
                csrfToken = csrfToken,
                parentId = parentId,
                rawCookieHeader = cookieStr,
                capturedHeaders = headers,
                rawBody = body,
                rawPath = path
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun extractRequestPath(normalized: String): String? {
        // Extract URL from curl 'https://...' or curl "https://..."
        val pattern = """curl\s+['"]([^'"]+)['"]"""
        val match = Regex(pattern).find(normalized)
        return match?.groupValues?.get(1)?.let { url ->
            try {
                java.net.URI(url).path
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun extractAllHeaders(normalized: String): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        // Match -H 'Header: Value' or -H "Header: Value"
        val patterns = listOf(
            """-H\s+'([^':]+):\s*([^']+)'""",
            """-H\s+"([^":]+):\s*([^"]+)"""",
            """--header\s+'([^':]+):\s*([^']+)'""",
            """--header\s+"([^":]+):\s*([^"]+)""""
        )

        patterns.forEach { pattern ->
            Regex(pattern).findAll(normalized).forEach { match ->
                val name = match.groupValues[1].trim()
                val value = match.groupValues[2].trim()
                // Don't capture host or cookie headers here, they are handled separately or by OkHttp
                val lowerName = name.lowercase()
                if (lowerName != "cookie" && lowerName != "host" && lowerName != "content-length") {
                    headers[name] = value
                }
            }
        }
        return headers
    }

    /**
     * Check if the input looks like a cURL command.
     */
    fun isCurlInput(input: String): Boolean {
        return input.startsWith("curl ") || input.startsWith("-H ") ||
            input.startsWith("-b ") || input.startsWith("--") ||
            input.contains("--data-raw") || input.contains("-d ") ||
            input.contains("-H 'cookie") || input.contains("-H \"cookie")
    }

    /**
     * Normalize the cURL command by removing line continuations.
     */
    private fun normalizeCurl(curl: String): String {
        // Remove backslash-newline continuations (both Unix and Windows style)
        return curl
            .replace("\\\r\n", " ")
            .replace("\\\n", " ")
            .replace("\r\n", " ")
            .replace("\n", " ")
    }

    private fun extractCookies(normalized: String): String {
        // Try -b or --cookie flag first (preferred for Chrome "Copy as cURL")
        extractCookiesFromBFlag(normalized)?.let { return it }

        // Then try -H "Cookie: ..." header
        extractCookiesFromHeader(normalized)?.let { return it }

        return ""
    }

    /**
     * Extract cookies from -b 'cookies' or --cookie 'cookies' format.
     * Chrome uses: -b 'cookie1=val1; cookie2=val2'
     */
    private fun extractCookiesFromBFlag(normalized: String): String? {
        // Match -b 'cookies' or -b "cookies" or --cookie 'cookies'
        val patterns = listOf(
            """-b\s+'([^']+)'""",
            """-b\s+"([^"]+)"""",
            """--cookie\s+'([^']+)'""",
            """--cookie\s+"([^"]+)""""
        )

        for (pattern in patterns) {
            val match = Regex(pattern).find(normalized)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        return null
    }

    /**
     * Extract cookies from -H "Cookie: ..." header format.
     */
    private fun extractCookiesFromHeader(normalized: String): String? {
        val patterns = listOf(
            """-H\s+'[Cc]ookie:\s*([^']+)'""",
            """-H\s+"[Cc]ookie:\s*([^"]+)"""",
            """--header\s+'[Cc]ookie:\s*([^']+)'""",
            """--header\s+"[Cc]ookie:\s*([^"]+)""""
        )

        for (pattern in patterns) {
            val match = Regex(pattern).find(normalized)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        return null
    }

    private fun extractSessionCookies(cookieStr: String): Pair<String?, String?> {
        // Extract __Host-app_session cookie value
        val appSession = Regex("""__Host-app_session=([^;]+)""")
            .find(cookieStr)?.groupValues?.get(1)?.trim()

        // Extract __Host-psifi.x-csrf-token cookie value (KEEP RAW for the cookie header)
        val csrfCookie = Regex("""__Host-psifi\.x-csrf-token=([^;]+)""")
            .find(cookieStr)?.groupValues?.get(1)?.trim()

        return Pair(appSession, csrfCookie)
    }

    /**
     * Extract CSRF token from x-csrf-token header.
     * Falls back to the cookie value if header not found.
     */
    private fun extractCsrfToken(normalized: String, csrfCookie: String?): String {
        // Try to extract from x-csrf-token header first
        val headerToken = extractXCsrfTokenHeader(normalized)

        if (!headerToken.isNullOrBlank()) {
            return headerToken
        }

        // Fall back to cookie value (decode first to see the | separator)
        val decoded = csrfCookie?.let { urlDecode(it) }

        return if (!decoded.isNullOrBlank() && decoded.contains("|")) {
            decoded.substringBefore("|")
        } else {
            decoded ?: ""
        }
    }

    /**
     * Extract x-csrf-token header value.
     */
    private fun extractXCsrfTokenHeader(normalized: String): String? {
        val patterns = listOf(
            """-H\s+'[Xx]-[Cc]srf-[Tt]oken:\s*([^']+)'""",
            """-H\s+"[Xx]-[Cc]srf-[Tt]oken:\s*([^"]+)"""",
            """--header\s+'[Xx]-[Cc]srf-[Tt]oken:\s*([^']+)'""",
            """--header\s+"[Xx]-[Cc]srf-[Tt]oken:\s*([^"]+)""""
        )

        for (pattern in patterns) {
            val match = Regex(pattern).find(normalized)
            if (match != null) {
                return match.groupValues[1].trim()
            }
        }
        return null
    }

    /**
     * Extract parentId from the request body.
     * Handles both --data-raw '{"parentId":"..."}' and -d '{"parentId":"..."}'
     */
    private fun extractParentId(normalized: String): String? {
        // First try to extract from --data-raw or -d argument
        val bodyJson = extractRequestBody(normalized)

        if (!bodyJson.isNullOrBlank()) {
            // Parse the JSON body to extract parentId
            val parentIdFromBody = extractParentIdFromJson(bodyJson)
            if (!parentIdFromBody.isNullOrBlank()) {
                return parentIdFromBody
            }
        }

        // Fallback: try to find parentId pattern anywhere in the string
        val fallbackMatch = Regex(""""parentId"\s*:\s*"([^"]+)"""").find(normalized)
        return fallbackMatch?.groupValues?.get(1)
    }

    /**
     * Extract the request body from --data-raw, --data, or -d arguments.
     */
    private fun extractRequestBody(normalized: String): String? {
        // First try single-quoted patterns (safer, no escaping issues)
        val singleQuotePatterns = listOf(
            """--data-raw\s+'([^']+)'""",
            """--data\s+'([^']+)'""",
            """-d\s+'([^']+)'"""
        )

        for (pattern in singleQuotePatterns) {
            val match = Regex(pattern).find(normalized)
            if (match != null) {
                return match.groupValues[1]
            }
        }

        // Then try double-quoted patterns (may contain escaped quotes like \")
        val doubleQuotePatterns = listOf(
            """--data-raw\s+"((?:[^"\\]|\\.)*)"""",
            """--data\s+"((?:[^"\\]|\\.)*)"""",
            """-d\s+"((?:[^"\\]|\\.)*)""""
        )

        for (pattern in doubleQuotePatterns) {
            val match = Regex(pattern).find(normalized)
            if (match != null) {
                // Unescape the escaped quotes
                return match.groupValues[1].replace("\\\"", "\"")
            }
        }

        return null
    }

    /**
     * Extract parentId from a JSON string.
     */
    private fun extractParentIdFromJson(json: String): String? {
        return try {
            val match = Regex(""""parentId"\s*:\s*"([^"]+)"""").find(json)
            match?.groupValues?.get(1)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * URL-decode a string, handling any encoding issues gracefully.
     */
    private fun urlDecode(value: String): String {
        return try {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        } catch (e: Exception) {
            value // Return original if decode fails
        }
    }
}
