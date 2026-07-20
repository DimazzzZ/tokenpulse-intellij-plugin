package org.zhavoronkov.tokenpulse.provider.anthropic.claudecode

import com.intellij.openapi.application.ApplicationManager
import org.zhavoronkov.tokenpulse.model.Balance
import org.zhavoronkov.tokenpulse.model.BalanceSnapshot
import org.zhavoronkov.tokenpulse.model.ConnectionType
import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.model.Tokens
import org.zhavoronkov.tokenpulse.provider.ProviderClient
import org.zhavoronkov.tokenpulse.settings.Account
import org.zhavoronkov.tokenpulse.settings.TokenPulseSettingsService
import org.zhavoronkov.tokenpulse.utils.TokenPulseLogger
import java.time.Instant

/**
 * Provider client for Claude Code (Claude CLI).
 *
 * Reads existing Claude Code OAuth credentials from the local credential store
 * (Keychain on macOS, plaintext file on other platforms) and uses the undocumented
 * OAuth API to fetch usage data.
 *
 * ## Authentication
 * - Reads existing credentials from Claude Code's credential store
 * - No manual token generation required — uses what `claude` already has
 * - If credentials are missing/expired, tells user to run `claude` to log in
 *
 * ## Usage Data
 * The provider extracts:
 * - Session usage percentage (5-hour window)
 * - Week usage percentage (7-day window for all models)
 * - Reset times for both windows
 *
 * ## Requirements
 * - Claude CLI must be installed and user must have logged in via `claude`
 */
class ClaudeCodeProviderClient : ProviderClient {

    private val oauthClient = ClaudeOAuthUsageClient()
    private val refreshClient = ClaudeOAuthRefreshClient()

    override fun fetchBalance(account: Account, secret: String): ProviderResult {
        TokenPulseLogger.Provider.debug("[ClaudeCodeProviderClient] fetchBalance() called for account: ${account.id}")

        // Check if Claude CLI is installed
        if (!ClaudeCliDetector.isInstalled()) {
            return ProviderResult.Failure.AuthError(
                "Claude CLI not found. Please install it via: npm install -g @anthropic-ai/claude-code"
            )
        }

        val credentialReader = ClaudeCredentialReader(account.claudeConfigDir)

        // Acquire an access token, refreshing proactively if the stored one has expired.
        var accessToken = when (val t = acquireAccessToken(credentialReader)) {
            is TokenAcquisition.Ok -> t.accessToken
            is TokenAcquisition.Failed -> return t.error
        }

        // Call OAuth API. If it returns auth-error, do a single reactive
        // refresh + retry — the retry itself is not itself retried.
        var result = oauthClient.fetchUsage(accessToken)
        if (result is ClaudeOAuthUsageClient.OAuthUsageResult.Error && result.isAuthError) {
            TokenPulseLogger.Provider.info(
                "[ClaudeCodeProviderClient] Usage API returned auth error; attempting single token refresh + retry"
            )
            when (val r = refreshOnce(credentialReader)) {
                is TokenAcquisition.Ok -> {
                    accessToken = r.accessToken
                    result = oauthClient.fetchUsage(accessToken)
                }
                is TokenAcquisition.Failed -> return r.error
            }
        }

        return when (result) {
            is ClaudeOAuthUsageClient.OAuthUsageResult.Success -> {
                TokenPulseLogger.Provider.debug("[ClaudeCodeProviderClient] OAuth API successful")
                buildSuccessResult(account, result.usageData)
            }
            is ClaudeOAuthUsageClient.OAuthUsageResult.Error -> {
                TokenPulseLogger.Provider.warn("[ClaudeCodeProviderClient] OAuth API error: ${result.message}")
                if (result.isAuthError) {
                    ProviderResult.Failure.AuthError(
                        "Claude Code session expired. Please run `claude` to re-authenticate."
                    )
                } else {
                    ProviderResult.Failure.UnknownError(
                        "Claude API error. Please try again later."
                    )
                }
            }
        }
    }

    private fun buildSuccessResult(
        account: Account,
        usageData: ClaudeUsageData,
    ): ProviderResult.Success {
        TokenPulseLogger.Provider.debug(
            "[ClaudeCodeProviderClient] Extraction successful (source=oauth_api): " +
                "session=${usageData.sessionUsedPercent}%, week=${usageData.weekUsedPercent}%"
        )

        // Lazy migration: enrich blank account names from the identity file
        // (once per account, best-effort). Runs on the fetch's IO thread; the
        // settings mutation is dispatched to the EDT.
        maybeEnrichAccountName(account)

        val metadata = buildMetadata(usageData)
        val (tokensUsed, tokensTotal) = calculateTokens(usageData)

        return ProviderResult.Success(
            BalanceSnapshot(
                accountId = account.id,
                connectionType = ConnectionType.CLAUDE_CODE,
                timestamp = Instant.now(),
                balance = Balance(
                    tokens = Tokens(
                        used = tokensUsed,
                        total = tokensTotal,
                        remaining = tokensTotal - tokensUsed
                    )
                ),
                metadata = metadata
            )
        )
    }

    /**
     * Once a Claude Code account has been successfully refreshed, upgrade its
     * blank display name to the account's real identity (email/org) read from
     * `<configDir>/.claude.json`. Runs at most once per account: after the
     * first success, `name` is non-blank and this becomes a no-op.
     *
     * Guarded by [ClaudeAccountIdentity.hasAny] so we never write the raw
     * `.claude` basename fallback that [ClaudeAccountDiscovery.labelFor]
     * produces for identity-less dirs.
     */
    internal fun maybeEnrichAccountName(account: Account) {
        if (account.name.isNotBlank()) return
        val identity = ClaudeAccountIdentityReader.read(account.claudeConfigDir)
        val label = enrichedAccountLabel(identity, account.claudeConfigDir) ?: return

        ApplicationManager.getApplication().invokeLater {
            val target = TokenPulseSettingsService.getInstance().state.accounts
                .find { it.id == account.id }
            if (target != null && target.name.isBlank()) {
                TokenPulseLogger.Provider.info(
                    "[ClaudeCodeProviderClient] Enriching account ${account.id} name -> '$label'"
                )
                target.name = label
            }
        }
    }

    override fun testCredentials(account: Account, secret: String): ProviderResult {
        // Check if Claude CLI is installed
        if (!ClaudeCliDetector.isInstalled()) {
            return ProviderResult.Failure.AuthError(
                "Claude CLI not found. Please install it via: npm install -g @anthropic-ai/claude-code"
            )
        }

        val credentialReader = ClaudeCredentialReader(account.claudeConfigDir)

        var accessToken = when (val t = acquireAccessToken(credentialReader)) {
            is TokenAcquisition.Ok -> t.accessToken
            is TokenAcquisition.Failed -> return t.error
        }

        var result = oauthClient.fetchUsage(accessToken)
        if (result is ClaudeOAuthUsageClient.OAuthUsageResult.Error && result.isAuthError) {
            when (val r = refreshOnce(credentialReader)) {
                is TokenAcquisition.Ok -> {
                    accessToken = r.accessToken
                    result = oauthClient.fetchUsage(accessToken)
                }
                is TokenAcquisition.Failed -> return r.error
            }
        }

        return when (result) {
            is ClaudeOAuthUsageClient.OAuthUsageResult.Success -> {
                TokenPulseLogger.Provider.debug("[ClaudeCodeProviderClient] OAuth API test successful")
                ProviderResult.Success(
                    BalanceSnapshot(
                        accountId = account.id,
                        connectionType = ConnectionType.CLAUDE_CODE,
                        timestamp = Instant.now(),
                        balance = Balance(),
                        metadata = mapOf("authMethod" to "oauth_api")
                    )
                )
            }
            is ClaudeOAuthUsageClient.OAuthUsageResult.Error -> {
                TokenPulseLogger.Provider.warn("[ClaudeCodeProviderClient] OAuth API test failed: ${result.message}")
                if (result.isAuthError) {
                    ProviderResult.Failure.AuthError(
                        "Claude Code session expired. Please run `claude` to re-authenticate."
                    )
                } else {
                    ProviderResult.Failure.UnknownError(
                        "Claude API error. Please try again later."
                    )
                }
            }
        }
    }

    private fun buildMetadata(usageData: ClaudeUsageData): Map<String, String> {
        val metadata = mutableMapOf<String, String>()

        usageData.sessionUsedPercent?.let { metadata["sessionUsed"] = it.toString() }
        usageData.sessionResetsAt?.let { metadata["sessionResetsAt"] = it }
        usageData.weekUsedPercent?.let { metadata["weekUsed"] = it.toString() }
        usageData.weekResetsAt?.let { metadata["weekResetsAt"] = it }
        metadata["source"] = "oauth_api"
        metadata["status"] = "OAuth API successful"

        return metadata
    }

    private fun calculateTokens(usageData: ClaudeUsageData): Pair<Long, Long> {
        val sessionPercent = usageData.sessionUsedPercent ?: 0
        val tokensUsed = (sessionPercent * TOKENS_PER_PERCENT).toLong()
        return tokensUsed to TOTAL_TOKENS
    }

    companion object {
        /** Use session percentage * 1000 as a pseudo-token count for display. */
        private const val TOKENS_PER_PERCENT = 1000

        /** 100% = 100,000 pseudo-tokens for display purposes. */
        private const val TOTAL_TOKENS = 100_000L
    }

    /**
     * Result of trying to obtain a usable access token — either an OK value
     * or a terminal failure suitable to return from `fetchBalance` /
     * `testCredentials` verbatim.
     */
    private sealed class TokenAcquisition {
        data class Ok(val accessToken: String) : TokenAcquisition()
        data class Failed(val error: ProviderResult.Failure) : TokenAcquisition()
    }

    /**
     * Get an access token to use for the next API call. Reads the stored
     * token; if it's known-expired, tries a single refresh before returning.
     * Missing credentials produce an `AuthError` pointing the user at `claude`.
     */
    private fun acquireAccessToken(credentialReader: ClaudeCredentialReader): TokenAcquisition {
        val stored = credentialReader.readAccessToken()
        if (stored == null) {
            return TokenAcquisition.Failed(
                ProviderResult.Failure.AuthError(
                    "Claude CLI not authenticated. Please run `claude` to log in."
                )
            )
        }
        if (!credentialReader.isTokenExpired()) {
            return TokenAcquisition.Ok(stored)
        }
        TokenPulseLogger.Provider.info(
            "[ClaudeCodeProviderClient] Stored token is expired; attempting refresh"
        )
        return refreshOnce(credentialReader)
    }

    /**
     * Attempt a single token refresh. On success, returns the fresh access
     * token for in-memory use only — TokenPulse never writes back to Claude's
     * credential store (the `claude` CLI owns its own credential lifecycle).
     */
    private fun refreshOnce(credentialReader: ClaudeCredentialReader): TokenAcquisition {
        val rt = credentialReader.readRefreshToken()
        if (rt == null) {
            return TokenAcquisition.Failed(
                ProviderResult.Failure.AuthError(
                    "Claude Code session expired. Please run `claude` to re-authenticate."
                )
            )
        }
        return when (val r = refreshClient.refresh(rt)) {
            is ClaudeOAuthRefreshClient.RefreshResult.Success -> {
                TokenAcquisition.Ok(r.accessToken)
            }
            is ClaudeOAuthRefreshClient.RefreshResult.Error -> {
                if (r.isAuthError) {
                    TokenAcquisition.Failed(
                        ProviderResult.Failure.AuthError(
                            "Claude Code session expired. Please run `claude` to re-authenticate."
                        )
                    )
                } else {
                    TokenAcquisition.Failed(
                        ProviderResult.Failure.UnknownError("Token refresh failed: ${r.message}")
                    )
                }
            }
            is ClaudeOAuthRefreshClient.RefreshResult.NetworkError -> {
                TokenAcquisition.Failed(
                    ProviderResult.Failure.UnknownError("Token refresh failed: ${r.message}")
                )
            }
        }
    }
}

/**
 * Pure decision for the lazy name-enrichment migration: derive a display label
 * from an account's identity + config dir, or null when there's nothing useful
 * to write.
 *
 * Returns null when [identity] is null or has no identifying fields
 * ([ClaudeAccountIdentity.hasAny] false) — this prevents writing the raw
 * `.claude` basename that [ClaudeAccountDiscovery.labelFor] falls back to for
 * identity-less dirs. Also returns null on a blank label.
 */
internal fun enrichedAccountLabel(
    identity: ClaudeAccountIdentity?,
    configDir: String?,
): String? {
    if (identity == null || !identity.hasAny()) return null
    return ClaudeAccountDiscovery.labelFor(identity, configDir ?: "").takeIf { it.isNotBlank() }
}
