package org.zhavoronkov.tokenpulse.ui

object SecretRedactor {
    private val TOKEN_REGEX = Regex(
        """(?i)(Bearer\s+)?(sk-[a-zA-Z0-9]{20,}|or-[a-zA-Z0-9]{20,}|clnt-[a-zA-Z0-9]{20,})"""
    )

    /**
     * Redacts sensitive looking patterns from the given text.
     */
    fun redact(text: String?): String {
        if (text == null) return ""
        return text.replace(TOKEN_REGEX) { match ->
            val prefix = match.groups[1]?.value ?: ""
            "$prefix[REDACTED]"
        }
    }
}
