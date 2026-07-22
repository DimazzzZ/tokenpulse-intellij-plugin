package org.zhavoronkov.tokenpulse.provider.openai.chatgpt

import org.zhavoronkov.tokenpulse.model.Balance
import org.zhavoronkov.tokenpulse.model.BalanceSnapshot
import org.zhavoronkov.tokenpulse.model.ConnectionType
import org.zhavoronkov.tokenpulse.model.Credits
import org.zhavoronkov.tokenpulse.model.ProviderResult
import org.zhavoronkov.tokenpulse.model.Tokens
import org.zhavoronkov.tokenpulse.provider.ProviderClient
import org.zhavoronkov.tokenpulse.provider.openai.chatgpt.oauth.CodexCredentialReader
import org.zhavoronkov.tokenpulse.provider.openai.chatgpt.oauth.CodexOAuthRefreshClient
import org.zhavoronkov.tokenpulse.provider.openai.chatgpt.oauth.CodexOAuthUsageClient
import org.zhavoronkov.tokenpulse.provider.openai.chatgpt.oauth.IdTokenClaims
import org.zhavoronkov.tokenpulse.settings.Account
import org.zhavoronkov.tokenpulse.utils.TokenPulseLogger
import java.time.Instant

/**
 * Provider client for Codex CLI (ChatGPT Plus/Pro/Team).
 *
 * Reads the OAuth credentials the `codex` CLI stores in `~/.codex/auth.json`
 * and calls ChatGPT's undocumented usage endpoint directly to fetch the
 * 5-hour, weekly, and code-review rate limits. No `codex app-server`
 * subprocess is spawned.
 *
 * ## Authentication
 * The access token is read from `auth.json`. If it has expired, we refresh it
 * via the OAuth token endpoint and persist the rotated tokens back to
 * `auth.json` (mirroring the CLI), so the user's real `codex` login stays in
 * sync. If credentials are missing or the refresh token is revoked, we tell
 * the user to run `codex` to sign in.
 *
 * ## Credits
 * Credits remaining is not available via this endpoint; we report "unavailable".
 */
class CodexProviderClient(
    private val credentialReaderFactory: () -> CodexCredentialReader = { CodexCredentialReader() },
    private val usageClient: CodexOAuthUsageClient = CodexOAuthUsageClient(),
    private val refreshClient: CodexOAuthRefreshClient = CodexOAuthRefreshClient(),
) : ProviderClient {

    override fun fetchBalance(account: Account, secret: String): ProviderResult {
        TokenPulseLogger.Provider.info("Codex fetchBalance: accountId=${account.id}")
        return try {
            resolveUsage(account)
        } catch (e: Exception) {
            TokenPulseLogger.Provider.error("Codex fetch error", e)
            ProviderResult.Failure.NetworkError("Failed to read Codex usage", e)
        }
    }

    override fun testCredentials(account: Account, secret: String): ProviderResult =
        fetchBalance(account, secret)

    @Suppress("ReturnCount")
    private fun resolveUsage(account: Account): ProviderResult {
        val reader = credentialReaderFactory()
        val auth = reader.read()
            ?: return notSignedIn()
        val tokens = auth.tokens
        val storedAccessToken = tokens?.accessToken?.takeIf { it.isNotBlank() }
            ?: return notSignedIn()
        val accountId = reader.accountId()
            ?: return ProviderResult.Failure.AuthError(
                "Codex credentials missing account id. Run `codex` in a terminal to sign in."
            )
        val claims = IdTokenClaims.parse(tokens.idToken)

        // Proactively refresh if the stored access token is known-expired.
        var accessToken = storedAccessToken
        if (reader.isAccessTokenExpired()) {
            accessToken = when (val r = refreshAndPersist(reader)) {
                is TokenOutcome.Ok -> r.accessToken
                is TokenOutcome.Failed -> return r.error
            }
        }

        var usage = usageClient.fetch(accessToken, accountId, claims.isFedramp)
        // Reactively refresh + retry once on a 401 from the usage endpoint.
        if (usage is CodexOAuthUsageClient.UsageResult.AuthError) {
            when (val r = refreshAndPersist(reader)) {
                is TokenOutcome.Ok -> usage = usageClient.fetch(r.accessToken, accountId, claims.isFedramp)
                is TokenOutcome.Failed -> return r.error
            }
        }

        return when (usage) {
            is CodexOAuthUsageClient.UsageResult.Success ->
                buildSuccessResult(account, usage.usage, claims.email)
            CodexOAuthUsageClient.UsageResult.AuthError -> sessionExpired()
            is CodexOAuthUsageClient.UsageResult.Forbidden -> ProviderResult.Failure.AuthError(usage.message)
            CodexOAuthUsageClient.UsageResult.RateLimited ->
                ProviderResult.Failure.RateLimited("Codex rate limited. Please try again shortly.")
            is CodexOAuthUsageClient.UsageResult.Transient ->
                ProviderResult.Failure.NetworkError(usage.message)
        }
    }

    /**
     * Refresh the access token and persist the rotated tokens back to
     * `auth.json`. Returns the fresh access token or a terminal failure.
     */
    private fun refreshAndPersist(reader: CodexCredentialReader): TokenOutcome {
        val refreshToken = reader.refreshToken() ?: return TokenOutcome.Failed(sessionExpired())
        return when (val r = refreshClient.refresh(refreshToken)) {
            is CodexOAuthRefreshClient.RefreshResult.Refreshed -> {
                // Write-back keeps the user's `codex` CLI in sync with the
                // rotated (single-use) refresh token. Best-effort: a failed
                // write does not abort the current fetch.
                reader.writeAtomic(r.idToken, r.accessToken, r.refreshToken)
                TokenOutcome.Ok(r.accessToken)
            }
            is CodexOAuthRefreshClient.RefreshResult.AuthError -> TokenOutcome.Failed(sessionExpired())
            is CodexOAuthRefreshClient.RefreshResult.Transient ->
                TokenOutcome.Failed(ProviderResult.Failure.NetworkError("Codex token refresh failed: ${r.message}"))
        }
    }

    private fun buildSuccessResult(
        account: Account,
        usage: CodexOAuthUsageClient.CodexUsage,
        email: String?,
    ): ProviderResult {
        val fiveHourUsed = usage.fiveHour?.usedPercent
        val weeklyUsed = usage.weekly?.usedPercent
        val codeReviewUsed = usage.codeReview?.usedPercent

        TokenPulseLogger.Provider.debug(
            "Codex rate limits: 5h=$fiveHourUsed%, weekly=$weeklyUsed%, codeReview=$codeReviewUsed%"
        )

        // Codex exposes only percentages; represent the "active" bucket
        // (5-hour if present, else weekly) as synthetic token counts for the
        // shared Balance slot used for coloring/sorting. OpenAI has removed
        // the short window for many plans, so weekly is commonly the only one.
        val activeUsed = fiveHourUsed ?: weeklyUsed
        val usedTokens = activeUsed?.let { (it * TOKENS_PER_PERCENT).toLong() } ?: 0L
        val totalRemaining = activeUsed?.let { ((100 - it) * TOKENS_PER_PERCENT).toLong() } ?: TOTAL_TOKENS

        // The usage payload's own email is authoritative; fall back to the
        // id_token claim when the endpoint omits it.
        val resolvedEmail = usage.email ?: email

        val metadata = buildMap {
            put("email", resolvedEmail ?: "N/A")
            put("planType", usage.planType ?: "N/A")
            put("fiveHourUsed", fiveHourUsed?.toString() ?: "N/A")
            put("weeklyUsed", weeklyUsed?.toString() ?: "N/A")
            put("fiveHourResetsAt", resetIso(usage.fiveHour?.resetAtEpochSeconds))
            put("weeklyResetsAt", resetIso(usage.weekly?.resetAtEpochSeconds))
            codeReviewUsed?.let { put("codeReviewUsed", it.toString()) }
            usage.codeReview?.resetAtEpochSeconds?.let { put("codeReviewResetsAt", resetIso(it)) }
            put("codexSource", "oauth_api")
        }

        return ProviderResult.Success(
            BalanceSnapshot(
                accountId = account.id,
                connectionType = ConnectionType.CODEX_CLI,
                timestamp = Instant.now(),
                balance = Balance(
                    credits = Credits(used = null, total = null, remaining = null),
                    tokens = Tokens(used = usedTokens, total = TOTAL_TOKENS, remaining = totalRemaining)
                ),
                metadata = metadata
            )
        )
    }

    /** Format epoch-seconds as an ISO-8601 instant string for the tooltip. */
    private fun resetIso(epochSeconds: Long?): String =
        epochSeconds?.let { Instant.ofEpochSecond(it).toString() } ?: "N/A"

    private fun notSignedIn(): ProviderResult.Failure.AuthError =
        ProviderResult.Failure.AuthError("Codex not signed in. Run `codex` in a terminal to sign in.")

    private fun sessionExpired(): ProviderResult.Failure.AuthError =
        ProviderResult.Failure.AuthError("Codex session expired. Run `codex` in a terminal to re-authenticate.")

    private sealed class TokenOutcome {
        data class Ok(val accessToken: String) : TokenOutcome()
        data class Failed(val error: ProviderResult.Failure) : TokenOutcome()
    }

    companion object {
        private const val TOKENS_PER_PERCENT = 1000
        private const val TOTAL_TOKENS = 100_000L
    }
}
