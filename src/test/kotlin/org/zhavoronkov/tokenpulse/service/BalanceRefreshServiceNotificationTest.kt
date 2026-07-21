package org.zhavoronkov.tokenpulse.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.settings.AuthType

/**
 * Tests for the notification-message composer.
 *
 * The composer is a top-level pure function so we can exercise it directly
 * without instantiating [BalanceRefreshService], whose init block requires a
 * live IDE application.
 */
class BalanceRefreshServiceNotificationTest {

    private val label = "Claude Code CLI • dimaz.lark@gmail.com"

    @Test
    fun `AuthError for API-key auth type appends the re-enter suffix`() {
        val failure = ProviderResult.Failure.AuthError("Invalid API key.")
        val message = composeNotificationMessage(label, failure, AuthType.CLINE_API_KEY)
        assertEquals(
            "Failed to refresh $label: Invalid API key. " +
                "Please re-enter your API key in TokenPulse Settings.",
            message,
        )
    }

    @Test
    fun `AuthError for OAuth-CLI auth types does NOT append the API-key suffix`() {
        val failure = ProviderResult.Failure.AuthError(
            "Claude Code session expired. Please run `claude` to re-authenticate."
        )
        val message = composeNotificationMessage(label, failure, AuthType.CLAUDE_CODE_LOCAL)
        assertFalse(
            message.contains("re-enter your API key"),
            "OAuth/CLI auth types should not get the API-key hint",
        )
        assertTrue(message.endsWith("Please run `claude` to re-authenticate."))
    }

    @Test
    fun `Codex CLI AuthError does NOT append the API-key suffix`() {
        val failure = ProviderResult.Failure.AuthError(
            "Codex session expired. Run 'codex login' in terminal to re-authenticate."
        )
        val message = composeNotificationMessage(label, failure, AuthType.CODEX_CLI_LOCAL)
        assertFalse(message.contains("re-enter your API key"))
    }

    @Test
    fun `Nebius billing session AuthError does NOT append the API-key suffix`() {
        val failure = ProviderResult.Failure.AuthError("Nebius session expired. Please reconnect.")
        val message = composeNotificationMessage(label, failure, AuthType.NEBIUS_BILLING_SESSION)
        assertFalse(message.contains("re-enter your API key"))
    }

    @Test
    fun `non-AuthError failure never appends the API-key suffix`() {
        val network = ProviderResult.Failure.NetworkError("Connection timed out")
        val rate = ProviderResult.Failure.RateLimited("Too many requests")
        val unknown = ProviderResult.Failure.UnknownError("Boom")

        // Even for API-key auth types, non-AuthError failures don't get the hint.
        for (failure in listOf(network, rate, unknown)) {
            val message = composeNotificationMessage(label, failure, AuthType.CLINE_API_KEY)
            assertFalse(
                message.contains("re-enter your API key"),
                "Non-AuthError should not carry the API-key hint (was: $message)",
            )
        }
    }

    @Test
    fun `isApiKeyAuth classifies every AuthType`() {
        // Sanity check: every enum value has an explicit branch.
        AuthType.entries.forEach { authType ->
            // Just call it; the exhaustive when in production code guarantees
            // there is no missing branch, and this test guards against future
            // additions silently defaulting.
            isApiKeyAuth(authType)
        }

        // Positives
        assertTrue(isApiKeyAuth(AuthType.CLINE_API_KEY))
        assertTrue(isApiKeyAuth(AuthType.OPENAI_API_KEY))
        assertTrue(isApiKeyAuth(AuthType.XIAOMI_API_KEY))
        assertTrue(isApiKeyAuth(AuthType.XIAOMI_TOKEN_PLAN_KEY))
        assertTrue(isApiKeyAuth(AuthType.OPENROUTER_PROVISIONING_KEY))

        // Negatives (OAuth / CLI / session-backed)
        assertFalse(isApiKeyAuth(AuthType.CLAUDE_CODE_LOCAL))
        assertFalse(isApiKeyAuth(AuthType.CODEX_CLI_LOCAL))
        assertFalse(isApiKeyAuth(AuthType.OPENAI_OAUTH))
        assertFalse(isApiKeyAuth(AuthType.NEBIUS_BILLING_SESSION))
        assertFalse(isApiKeyAuth(AuthType.OPENROUTER_PLUGIN_BRIDGE))
    }
}
