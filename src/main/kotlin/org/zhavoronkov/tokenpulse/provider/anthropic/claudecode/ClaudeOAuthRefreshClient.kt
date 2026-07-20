package org.zhavoronkov.tokenpulse.provider.anthropic.claudecode

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName
import org.zhavoronkov.tokenpulse.utils.TokenPulseLogger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Exchanges a Claude Code OAuth refresh token for a fresh access token.
 *
 * The endpoint and parameters mirror the reference Claude CLI implementation
 * (`services/oauth/client.ts` -> `refreshOAuthToken`):
 * - POST https://platform.claude.com/v1/oauth/token
 * - JSON body: grant_type=refresh_token, refresh_token, client_id, scope
 * - No Authorization or anthropic-beta header (this is the auth server, not the API)
 *
 * The production client_id can be overridden via the CLAUDE_CODE_OAUTH_CLIENT_ID
 * environment variable, matching the CLI's behavior.
 */
class ClaudeOAuthRefreshClient internal constructor(
    private val tokenUrl: String = TOKEN_URL,
) {

    private val gson: Gson = GsonBuilder().create()
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
        .build()

    /**
     * Refresh the OAuth token.
     *
     * @param refreshToken The stored refresh token
     * @return [RefreshResult] describing the outcome
     */
    fun refresh(refreshToken: String): RefreshResult {
        if (refreshToken.isBlank()) {
            return RefreshResult.Error("Refresh token is empty", isAuthError = true)
        }

        TokenPulseLogger.Provider.debug("[ClaudeOAuthRefreshClient] Refreshing OAuth token")

        return try {
            val request = buildRequest(refreshToken)
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            TokenPulseLogger.Provider.debug("[ClaudeOAuthRefreshClient] Refresh response: ${response.statusCode()}")

            when (response.statusCode()) {
                200 -> parseSuccessResponse(response.body())
                400, 401 -> RefreshResult.Error("Refresh token invalid or expired", isAuthError = true)
                else -> RefreshResult.Error(
                    "Token refresh failed (${response.statusCode()}): ${response.body().take(200)}"
                )
            }
        } catch (e: java.net.http.HttpTimeoutException) {
            TokenPulseLogger.Provider.warn("[ClaudeOAuthRefreshClient] Refresh request timed out")
            RefreshResult.NetworkError("Token refresh timed out after $REQUEST_TIMEOUT_SECONDS seconds")
        } catch (e: java.net.ConnectException) {
            TokenPulseLogger.Provider.warn("[ClaudeOAuthRefreshClient] Refresh connection failed: ${e.message}")
            RefreshResult.NetworkError("Cannot connect to token endpoint: ${e.message}")
        } catch (e: Exception) {
            TokenPulseLogger.Provider.error("[ClaudeOAuthRefreshClient] Refresh request failed", e)
            RefreshResult.NetworkError("Token refresh failed: ${e.message}")
        }
    }

    private fun buildRequest(refreshToken: String): HttpRequest {
        val body = mapOf(
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
            "client_id" to clientId(),
            "scope" to DEFAULT_SCOPES
        )
        return HttpRequest.newBuilder()
            .uri(URI.create(tokenUrl))
            .header("Content-Type", "application/json")
            .header("User-Agent", USER_AGENT)
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
            .build()
    }

    private fun parseSuccessResponse(body: String): RefreshResult {
        return try {
            val response = gson.fromJson(body, RefreshTokenResponse::class.java)

            val accessToken = response?.accessToken
            val expiresIn = response?.expiresIn
            if (accessToken.isNullOrBlank() || expiresIn == null) {
                return RefreshResult.Error("Token refresh response missing access token")
            }

            RefreshResult.Success(
                accessToken = accessToken,
                // Claude's backend may omit refresh_token on refresh; the caller
                // keeps the previous one when this is null.
                refreshToken = response.refreshToken?.takeIf { it.isNotBlank() },
                expiresAt = System.currentTimeMillis() + expiresIn * MILLIS_PER_SECOND,
                scope = response.scope
            )
        } catch (e: JsonSyntaxException) {
            TokenPulseLogger.Provider.warn("[ClaudeOAuthRefreshClient] Failed to parse refresh response: ${e.message}")
            RefreshResult.Error("Invalid response format from token endpoint")
        }
    }

    private fun clientId(): String {
        val override = System.getenv("CLAUDE_CODE_OAUTH_CLIENT_ID")
        return override?.takeIf { it.isNotBlank() } ?: DEFAULT_CLIENT_ID
    }

    /**
     * Result of a token refresh attempt.
     */
    sealed class RefreshResult {
        data class Success(
            val accessToken: String,
            val refreshToken: String?,
            val expiresAt: Long,
            val scope: String?,
        ) : RefreshResult()

        data class Error(val message: String, val isAuthError: Boolean = false) : RefreshResult()
        data class NetworkError(val message: String) : RefreshResult()
    }

    private data class RefreshTokenResponse(
        @SerializedName("access_token") val accessToken: String?,
        @SerializedName("refresh_token") val refreshToken: String?,
        @SerializedName("expires_in") val expiresIn: Long?,
        @SerializedName("scope") val scope: String?,
    )

    companion object {
        private const val TOKEN_URL = "https://platform.claude.com/v1/oauth/token"
        private const val DEFAULT_CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
        private const val DEFAULT_SCOPES =
            "user:profile user:inference user:sessions:claude_code user:mcp_servers user:file_upload"
        private const val USER_AGENT = "TokenPulse/1.0"
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val REQUEST_TIMEOUT_SECONDS = 15L
        private const val MILLIS_PER_SECOND = 1000L
    }
}
