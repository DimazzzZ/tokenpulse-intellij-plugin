package org.zhavoronkov.tokenpulse.model

/**
 * Represents an AI service provider (company).
 *
 * Each provider may offer multiple connection types (e.g., OpenAI offers both
 * ChatGPT subscription and Platform API Key authentication).
 *
 * @property displayName Human-readable name shown in the UI.
 * @property abbreviation Short abbreviation for compact status bar display (2-3 chars).
 */
enum class Provider(val displayName: String, val abbreviation: String) {
    /** Anthropic - maker of Claude models. */
    ANTHROPIC("Anthropic", "CL"),

    /** OpenAI - maker of GPT models and ChatGPT. */
    OPENAI("OpenAI", "OA"),

    /** Cline - AI coding assistant with its own API. */
    CLINE("Cline", "CN"),

    /** OpenRouter - unified API gateway for multiple AI models. */
    OPENROUTER("OpenRouter", "OR"),

    /** Nebius - AI Studio / Token Factory cloud service. */
    NEBIUS("Nebius", "NB");

    companion object {
        /**
         * Returns providers sorted by display name for consistent UI ordering.
         */
        fun sortedByDisplayName(): List<Provider> = entries.sortedBy { it.displayName }
    }
}
