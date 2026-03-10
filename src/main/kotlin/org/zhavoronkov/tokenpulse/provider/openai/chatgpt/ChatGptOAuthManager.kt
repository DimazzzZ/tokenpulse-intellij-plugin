package org.zhavoronkov.tokenpulse.provider.openai.chatgpt

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.zhavoronkov.tokenpulse.settings.CredentialsStore
import org.zhavoronkov.tokenpulse.utils.TokenPulseLogger
import java.net.InetSocketAddress
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * OAuth PKCE manager for ChatGPT subscription.
 *
 * Implements the same OAuth flow as Cline:
 * - Authorization endpoint: https://auth.openai.com/oauth/authorize
 * - Token endpoint: https://auth.openai.com/oauth/token
 * - Client ID: app_EMoamEEZ73f0CkXaXp7hrann
 * - Callback: http://localhost:1455/auth/callback
 * - Scopes: openid profile email offline_access
 *
 * The flow:
 * 1. Generate PKCE code_verifier and code_challenge
 * 2. Open browser to OpenAI's auth page
 * 3. Start local HTTP server on port 1455
 * 4. User signs in, OpenAI redirects to localhost
 * 5. Exchange code for tokens
 * 6. Store refresh token, auto-refresh when needed
 */
@Service(Service.Level.APP)
class ChatGptOAuthManager {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private var pendingAuth: PendingAuth? = null
    private var cachedCredentials: ChatGptCredentials? = null

    @Volatile
    private var refreshInProgress = false

    /**
     * Check if we have credentials stored (regardless of validity).
     * Note: This may return true even for expired/invalid tokens.
     * Use [hasValidSession] for a stricter check that attempts token refresh.
     */
    fun isAuthenticated(): Boolean {
        loadCredentials()
        return cachedCredentials != null
    }

    /**
     * Check if we have a valid session that can produce an access token.
     * This attempts to get/refresh the access token to verify the session is still valid.
     *
     * @return true only if we can obtain a valid access token.
     */
    fun hasValidSession(): Boolean = getAccessToken() != null

    /**
     * Get the current access token, refreshing if necessary.
     *
     * @return Access token or null if not authenticated or refresh fails.
     */
    @Suppress("ReturnCount")
    fun getAccessToken(): String? {
        loadCredentials()
        val creds = cachedCredentials ?: return null

        // Check if token is expired (with 5 minute buffer)
        if (System.currentTimeMillis() > creds.expires - TOKEN_EXPIRY_BUFFER_MS) {
            return refreshAccessToken()
        }

        return creds.accessToken
    }

    /**
     * Force refresh the access token.
     */
    fun forceRefreshAccessToken(): String? = refreshAccessToken()

    /**
     * Start the OAuth authorization flow.
     * Opens browser and returns a CompletableFuture that completes when auth is done.
     */
    fun startAuthorizationFlow(): CompletableFuture<ChatGptCredentials> {
        cancelAuthorizationFlow()

        val codeVerifier = generateCodeVerifier()
        val codeChallenge = generateCodeChallenge(codeVerifier)
        val state = generateState()

        val future = CompletableFuture<ChatGptCredentials>()
        pendingAuth = PendingAuth(codeVerifier, state, future)

        try {
            startCallbackServer(future)
        } catch (e: Exception) {
            TokenPulseLogger.Provider.error("Failed to start OAuth callback server", e)
            future.completeExceptionally(e)
            return future
        }

        val authUrl = buildAuthorizationUrl(codeChallenge, state)

        ApplicationManager.getApplication().invokeLater {
            BrowserUtil.browse(authUrl)
        }

        return future
    }

    /**
     * Cancel any pending authorization flow.
     */
    fun cancelAuthorizationFlow() {
        pendingAuth?.server?.stop(0)
        pendingAuth = null
    }

    /**
     * Clear stored credentials (logout).
     */
    fun clearCredentials() {
        CredentialsStore.getInstance().removeApiKey(CREDENTIALS_KEY)
        cachedCredentials = null
    }

    /**
     * Get user email from stored credentials.
     */
    fun getEmail(): String? {
        loadCredentials()
        return cachedCredentials?.email
    }

    private fun loadCredentials() {
        if (cachedCredentials != null) return

        val json = CredentialsStore.getInstance().getApiKey(CREDENTIALS_KEY) ?: return
        cachedCredentials = try {
            gson.fromJson(json, ChatGptCredentials::class.java)
        } catch (e: Exception) {
            TokenPulseLogger.Provider.warn("Failed to parse stored OAuth credentials", e)
            null
        }
    }

    private fun saveCredentials(credentials: ChatGptCredentials) {
        cachedCredentials = credentials
        val json = gson.toJson(credentials)
        CredentialsStore.getInstance().saveApiKey(CREDENTIALS_KEY, json)
    }

    @Suppress("ReturnCount")
    private fun refreshAccessToken(): String? {
        val creds = cachedCredentials ?: return null
        val refreshToken = creds.refreshToken ?: return null

        if (refreshInProgress) {
            TokenPulseLogger.Provider.debug("Token refresh already in progress, waiting...")
            Thread.sleep(REFRESH_WAIT_MS)
            return cachedCredentials?.accessToken
        }

        refreshInProgress = true
        TokenPulseLogger.Provider.debug("Refreshing ChatGPT access token")

        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", CLIENT_ID)
            .add("refresh_token", refreshToken)
            .build()

        val request = Request.Builder()
            .url(TOKEN_ENDPOINT)
            .post(body)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: ""
                    TokenPulseLogger.Provider.warn("Token refresh failed: ${response.code} $errorBody")

                    val oauthError = parseOAuthError(errorBody)

                    if (isInvalidGrantError(response.code, oauthError)) {
                        TokenPulseLogger.Provider.warn(
                            "Refresh token is invalid (${oauthError?.error ?: "unknown"}), clearing credentials"
                        )
                        clearCredentials()
                    } else {
                        TokenPulseLogger.Provider.info(
                            "Token refresh failed but may be temporary, keeping credentials"
                        )
                    }
                    return null
                }

                val tokenResponse = gson.fromJson(response.body?.string(), TokenResponse::class.java)

                val newCreds = ChatGptCredentials(
                    accessToken = tokenResponse.accessToken,
                    refreshToken = tokenResponse.refreshToken ?: refreshToken,
                    expires = System.currentTimeMillis() + (tokenResponse.expiresIn * 1000L),
                    email = creds.email ?: tokenResponse.email
                )

                saveCredentials(newCreds)
                TokenPulseLogger.Provider.debug("Token refresh successful")
                newCreds.accessToken
            }
        } catch (e: Exception) {
            TokenPulseLogger.Provider.error("Token refresh failed with exception", e)
            null
        } finally {
            refreshInProgress = false
        }
    }

    private fun parseOAuthError(errorBody: String): OAuthError? {
        return try {
            val json = gson.fromJson(errorBody, JsonObject::class.java) ?: return null

            val errorField = json.get("error")
            val errorCode = when {
                errorField?.isJsonPrimitive == true -> errorField.asString
                errorField?.isJsonObject == true -> errorField.asJsonObject.get("type")?.asString
                else -> null
            }

            val errorDescription = json.get("error_description")?.asString
                ?: (errorField?.asJsonObject?.get("message")?.asString)
                ?: json.get("message")?.asString

            OAuthError(errorCode, errorDescription)
        } catch (e: Exception) {
            TokenPulseLogger.Provider.debug("Failed to parse OAuth error: ${e.message}")
            null
        }
    }

    @Suppress("ReturnCount")
    private fun isInvalidGrantError(statusCode: Int, oauthError: OAuthError?): Boolean {
        val error = oauthError?.error?.lowercase() ?: ""
        val description = oauthError?.errorDescription?.lowercase() ?: ""

        if (error.contains("invalid_grant")) return true

        if (description.contains("revoked") ||
            description.contains("invalid refresh") ||
            (description.contains("expired") && description.contains("refresh"))
        ) {
            return true
        }

        if (statusCode == 400 && error.isNotEmpty()) {
            return error == "invalid_grant" || error == "invalid_token"
        }

        return false
    }

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(verifier.toByteArray(StandardCharsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
    }

    private fun generateState(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Generate the OAuth authorization URL without starting the flow.
     * Useful for copy-to-clipboard functionality when user needs to open in incognito.
     *
     * @return The authorization URL
     */
    fun generateAuthorizationUrl(): String {
        val codeVerifier = generateCodeVerifier()
        val codeChallenge = generateCodeChallenge(codeVerifier)
        val state = generateState()

        // Store for later use when callback comes in
        pendingAuth = PendingAuth(codeVerifier, state, CompletableFuture())

        return buildAuthorizationUrl(codeChallenge, state)
    }

    private fun buildAuthorizationUrl(codeChallenge: String, state: String): String {
        val params = listOf(
            "client_id" to CLIENT_ID,
            "redirect_uri" to REDIRECT_URI,
            "scope" to SCOPES,
            "code_challenge" to codeChallenge,
            "code_challenge_method" to "S256",
            "response_type" to "code",
            "state" to state,
            "codex_cli_simplified_flow" to "true",
            "originator" to "tokenpulse"
        ).joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
        }

        return "$AUTH_ENDPOINT?$params"
    }

    private fun startCallbackServer(future: CompletableFuture<ChatGptCredentials>) {
        val server = HttpServer.create(InetSocketAddress(CALLBACK_PORT), 0)

        server.createContext("/auth/callback") { exchange ->
            handleCallback(exchange, future)
        }

        server.executor = null
        server.start()

        pendingAuth?.server = server

        TokenPulseLogger.Provider.debug("OAuth callback server started on port $CALLBACK_PORT")

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                future.get(AUTH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                future.completeExceptionally(Exception("Authentication timed out"))
                server.stop(0)
            } catch (_: Exception) {
                // Already completed
            }
        }
    }

    private fun handleCallback(exchange: HttpExchange, future: CompletableFuture<ChatGptCredentials>) {
        try {
            val query = exchange.requestURI.query ?: ""
            val params = query.split("&").associate {
                val parts = it.split("=", limit = 2)
                parts[0] to (parts.getOrNull(1) ?: "")
            }

            val code = params["code"]
            val state = params["state"]
            val error = params["error"]

            when {
                error != null -> {
                    sendErrorResponse(exchange, "Authentication failed: $error")
                    future.completeExceptionally(Exception("OAuth error: $error"))
                }
                code == null || state == null -> {
                    sendErrorResponse(exchange, "Missing code or state parameter")
                    future.completeExceptionally(Exception("Missing code or state parameter"))
                }
                pendingAuth == null || state != pendingAuth?.state -> {
                    sendErrorResponse(exchange, "State mismatch - possible CSRF attack")
                    future.completeExceptionally(Exception("State mismatch"))
                }
                else -> {
                    val credentials = exchangeCodeForTokens(code, pendingAuth!!.codeVerifier)
                    saveCredentials(credentials)
                    sendSuccessResponse(exchange)
                    future.complete(credentials)
                    pendingAuth?.server?.stop(1)
                    pendingAuth = null
                }
            }
        } catch (e: Exception) {
            TokenPulseLogger.Provider.error("OAuth callback error", e)
            sendErrorResponse(exchange, "Token exchange failed: ${e.message}")
            future.completeExceptionally(e)
        }
    }

    @Suppress("ThrowsCount")
    private fun exchangeCodeForTokens(code: String, codeVerifier: String): ChatGptCredentials {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", CLIENT_ID)
            .add("code", code)
            .add("redirect_uri", REDIRECT_URI)
            .add("code_verifier", codeVerifier)
            .build()

        val request = Request.Builder()
            .url(TOKEN_ENDPOINT)
            .post(body)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                throw Exception("Token exchange failed: ${response.code} $errorBody")
            }

            val tokenResponse = gson.fromJson(response.body?.string(), TokenResponse::class.java)
                ?: throw Exception("Empty token response")

            val refreshToken = tokenResponse.refreshToken
                ?: throw Exception("No refresh token in response")

            val email = tokenResponse.idToken?.let { extractEmailFromIdToken(it) }
                ?: tokenResponse.email

            return ChatGptCredentials(
                accessToken = tokenResponse.accessToken,
                refreshToken = refreshToken,
                expires = System.currentTimeMillis() + (tokenResponse.expiresIn * 1000L),
                email = email
            )
        }
    }

    private fun extractEmailFromIdToken(idToken: String): String? {
        return try {
            val parts = idToken.split(".")
            if (parts.size != 3) return null
            val payload = String(
                Base64.getUrlDecoder().decode(parts[1]),
                StandardCharsets.UTF_8
            )

            @Suppress("UNCHECKED_CAST")
            val claims = gson.fromJson(payload, Map::class.java) as? Map<String, Any?>
            claims?.get("email") as? String
        } catch (e: Exception) {
            null
        }
    }

    private fun sendSuccessResponse(exchange: HttpExchange) {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Authentication Successful</title>
                <style>
                    body { font-family: -apple-system, sans-serif; text-align: center; padding: 50px; 
                           background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%); color: #fff; }
                    .icon { font-size: 64px; margin-bottom: 20px; }
                    h1 { color: #10a37f; }
                </style>
            </head>
            <body>
                <div class="icon">✓</div>
                <h1>Authentication Successful</h1>
                <p>You can close this window and return to your IDE.</p>
                <script>setTimeout(() => window.close(), 3000);</script>
            </body>
            </html>
        """.trimIndent()

        exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        exchange.sendResponseHeaders(200, html.toByteArray().size.toLong())
        exchange.responseBody.use { it.write(html.toByteArray()) }
    }

    private fun sendErrorResponse(exchange: HttpExchange, message: String) {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Authentication Failed</title>
                <style>
                    body { font-family: -apple-system, sans-serif; text-align: center; padding: 50px;
                           background: #1a1a2e; color: #fff; }
                    .icon { font-size: 64px; margin-bottom: 20px; }
                    h1 { color: #e74c3c; }
                </style>
            </head>
            <body>
                <div class="icon">✗</div>
                <h1>Authentication Failed</h1>
                <p>$message</p>
            </body>
            </html>
        """.trimIndent()

        exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        exchange.sendResponseHeaders(400, html.toByteArray().size.toLong())
        exchange.responseBody.use { it.write(html.toByteArray()) }
    }

    private data class PendingAuth(
        val codeVerifier: String,
        val state: String,
        val future: CompletableFuture<ChatGptCredentials>,
        var server: HttpServer? = null
    )

    private data class OAuthError(
        val error: String?,
        val errorDescription: String?
    )

    /**
     * Stored credentials for ChatGPT OAuth.
     */
    data class ChatGptCredentials(
        val accessToken: String,
        val refreshToken: String?,
        val expires: Long,
        val email: String? = null
    )

    private data class TokenResponse(
        @SerializedName("access_token") val accessToken: String,
        @SerializedName("refresh_token") val refreshToken: String?,
        @SerializedName("id_token") val idToken: String?,
        @SerializedName("expires_in") val expiresIn: Long,
        val email: String?
    )

    companion object {
        private const val AUTH_ENDPOINT = "https://auth.openai.com/oauth/authorize"
        private const val TOKEN_ENDPOINT = "https://auth.openai.com/oauth/token"
        private const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        private const val REDIRECT_URI = "http://localhost:1455/auth/callback"
        private const val SCOPES = "openid profile email offline_access"
        private const val CALLBACK_PORT = 1455
        private const val CREDENTIALS_KEY = "chatgpt-oauth"
        private const val AUTH_TIMEOUT_SECONDS = 300L
        private const val HTTP_TIMEOUT_SECONDS = 30L
        private const val TOKEN_EXPIRY_BUFFER_MS = 300_000L // 5 minutes
        private const val REFRESH_WAIT_MS = 100L

        fun getInstance(): ChatGptOAuthManager = service()
    }
}
