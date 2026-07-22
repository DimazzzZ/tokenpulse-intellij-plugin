package org.zhavoronkov.tokenpulse.provider.openai.chatgpt.oauth

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName
import org.zhavoronkov.tokenpulse.provider.oauth.OAUTH_BODY_PREVIEW
import org.zhavoronkov.tokenpulse.provider.oauth.OAUTH_USER_AGENT
import org.zhavoronkov.tokenpulse.provider.oauth.oauthHttpClient
import org.zhavoronkov.tokenpulse.utils.TokenPulseLogger
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Exchanges a Codex OAuth refresh token for fresh tokens.
 *
 * Mirrors codex's `request_chatgpt_token_refresh`:
 * - POST https://auth.openai.com/oauth/token
 * - JSON body: client_id, grant_type=refresh_token, refresh_token
 * - Response: {id_token?, access_token?, refresh_token?}
 *
 * The client_id can be overridden via the CODEX_APP_SERVER_LOGIN_CLIENT_ID
 * environment variable, matching the CLI.
 */
class CodexOAuthRefreshClient(
    private val tokenUrl: String = TOKEN_URL,
) {

    private val gson: Gson = Gson()
    private val httpClient = oauthHttpClient(TIMEOUT_SECONDS)

    fun refresh(refreshToken: String): RefreshResult {
        if (refreshToken.isBlank()) {
            return RefreshResult.AuthError(RefreshFailureReason.Other, "Refresh token is empty")
        }

        TokenPulseLogger.Provider.debug("[CodexOAuthRefreshClient] Refreshing OAuth token")

        return try {
            val response = httpClient.send(buildRequest(refreshToken), HttpResponse.BodyHandlers.ofString())
            TokenPulseLogger.Provider.debug("[CodexOAuthRefreshClient] Refresh response: ${response.statusCode()}")

            when (val status = response.statusCode()) {
                in HTTP_2XX_LOWER..HTTP_2XX_UPPER -> parseSuccess(response.body())
                401 -> classifyFailure(response.body(), forcePermanent = true)
                else -> {
                    val classified = classifyFailure(response.body(), forcePermanent = false)
                    // A non-401 status with an unknown error code is transient.
                    if (classified is RefreshResult.AuthError && classified.reason == RefreshFailureReason.Other) {
                        RefreshResult.Transient(
                            "Token refresh failed ($status): ${response.body().take(OAUTH_BODY_PREVIEW)}"
                        )
                    } else {
                        classified
                    }
                }
            }
        } catch (_: java.net.http.HttpTimeoutException) {
            RefreshResult.Transient("Token refresh timed out after $TIMEOUT_SECONDS seconds")
        } catch (e: java.net.ConnectException) {
            RefreshResult.Transient("Cannot connect to token endpoint: ${e.message}")
        } catch (e: Exception) {
            TokenPulseLogger.Provider.error("[CodexOAuthRefreshClient] Refresh failed", e)
            RefreshResult.Transient("Token refresh failed: ${e.message}")
        }
    }

    private fun buildRequest(refreshToken: String): HttpRequest {
        val body = mapOf(
            "client_id" to clientId(),
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
        )
        return HttpRequest.newBuilder()
            .uri(URI.create(tokenUrl))
            .header("Content-Type", "application/json")
            .header("User-Agent", OAUTH_USER_AGENT)
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
            .build()
    }

    private fun parseSuccess(body: String): RefreshResult {
        return try {
            val response = gson.fromJson(body, RefreshResponse::class.java)
            val accessToken = response?.accessToken?.takeIf { it.isNotBlank() }
                ?: return RefreshResult.Transient("Token refresh response missing access token")
            RefreshResult.Refreshed(
                idToken = response.idToken?.takeIf { it.isNotBlank() },
                accessToken = accessToken,
                refreshToken = response.refreshToken?.takeIf { it.isNotBlank() },
            )
        } catch (e: JsonSyntaxException) {
            RefreshResult.Transient("Invalid response format from token endpoint: ${e.message}")
        }
    }

    /**
     * Classify an error body using codex's error codes. When [forcePermanent]
     * is true (HTTP 401), an unknown/absent code still maps to a permanent
     * auth error. Otherwise an unknown code returns [RefreshFailureReason.Other]
     * so the caller can decide transient-vs-permanent by status.
     */
    private fun classifyFailure(body: String, forcePermanent: Boolean): RefreshResult {
        val code = extractErrorCode(body)
        val reason = when (code?.lowercase()) {
            "refresh_token_expired" -> RefreshFailureReason.Expired
            "refresh_token_reused" -> RefreshFailureReason.Reused
            "refresh_token_invalidated" -> RefreshFailureReason.Invalidated
            else -> RefreshFailureReason.Other
        }
        if (reason == RefreshFailureReason.Other && !forcePermanent) {
            return RefreshResult.AuthError(RefreshFailureReason.Other, code ?: body.take(OAUTH_BODY_PREVIEW))
        }
        return RefreshResult.AuthError(reason, code ?: "unauthorized")
    }

    private fun extractErrorCode(body: String): String? {
        return try {
            val obj = gson.fromJson(body, com.google.gson.JsonObject::class.java) ?: return null
            when (val error = obj.get("error")) {
                null -> obj.get("code")?.takeIf { it.isJsonPrimitive }?.asString
                else -> if (error.isJsonObject) {
                    error.asJsonObject.get("code")?.takeIf { it.isJsonPrimitive }?.asString
                } else if (error.isJsonPrimitive) {
                    error.asString
                } else {
                    null
                }
            }?.takeIf { it.isNotBlank() }
        } catch (_: JsonSyntaxException) {
            null
        }
    }

    private fun clientId(): String =
        System.getenv(CLIENT_ID_ENV)?.takeIf { it.isNotBlank() } ?: DEFAULT_CLIENT_ID

    /** Reason a refresh failed permanently, mirroring codex's classification. */
    enum class RefreshFailureReason { Expired, Reused, Invalidated, Other }

    sealed class RefreshResult {
        data class Refreshed(
            val idToken: String?,
            val accessToken: String,
            val refreshToken: String?,
        ) : RefreshResult()

        /** Permanent: the user must re-run `codex` to sign in. */
        data class AuthError(val reason: RefreshFailureReason, val message: String) : RefreshResult()

        /** Transient network/server error; retry later. */
        data class Transient(val message: String) : RefreshResult()
    }

    private data class RefreshResponse(
        @SerializedName("id_token") val idToken: String?,
        @SerializedName("access_token") val accessToken: String?,
        @SerializedName("refresh_token") val refreshToken: String?,
    )

    companion object {
        private const val TOKEN_URL = "https://auth.openai.com/oauth/token"
        private const val DEFAULT_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        private const val CLIENT_ID_ENV = "CODEX_APP_SERVER_LOGIN_CLIENT_ID"
        private const val TIMEOUT_SECONDS = 15L
        private const val HTTP_2XX_LOWER = 200
        private const val HTTP_2XX_UPPER = 299
    }
}
