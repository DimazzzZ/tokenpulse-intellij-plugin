package org.zhavoronkov.tokenpulse.provider.openai.chatgpt.oauth

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName
import org.zhavoronkov.tokenpulse.provider.oauth.AbstractOAuthRefreshClient
import org.zhavoronkov.tokenpulse.provider.oauth.OAUTH_BODY_PREVIEW
import org.zhavoronkov.tokenpulse.provider.oauth.OAUTH_USER_AGENT
import java.net.URI
import java.net.http.HttpRequest
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
) : AbstractOAuthRefreshClient<CodexOAuthRefreshClient.RefreshResult>(
    connectSeconds = TIMEOUT_SECONDS,
    logTag = "CodexOAuthRefreshClient",
) {

    private val gson: Gson = Gson()

    override fun emptyTokenResult(): RefreshResult =
        RefreshResult.AuthError(RefreshFailureReason.Other, "Refresh token is empty")

    override fun transient(message: String): RefreshResult = RefreshResult.Transient(message)

    override fun mapStatus(status: Int, body: String): RefreshResult = when (status) {
        in HTTP_2XX_LOWER..HTTP_2XX_UPPER -> parseSuccess(body)
        401 -> classifyFailure(body, forcePermanent = true)
        else -> {
            val classified = classifyFailure(body, forcePermanent = false)
            // A non-401 status with an unknown error code is transient.
            if (classified is RefreshResult.AuthError && classified.reason == RefreshFailureReason.Other) {
                RefreshResult.Transient("Token refresh failed ($status): ${body.take(OAUTH_BODY_PREVIEW)}")
            } else {
                classified
            }
        }
    }

    override fun buildRequest(refreshToken: String): HttpRequest {
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

    private fun clientId(): String = clientId(CLIENT_ID_ENV, DEFAULT_CLIENT_ID)

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
