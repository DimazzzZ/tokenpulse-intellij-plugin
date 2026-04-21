package org.zhavoronkov.tokenpulse.provider.openai.chatgpt

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.zhavoronkov.tokenpulse.utils.TokenPulseLogger
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Client for Codex app-server JSON-RPC communication.
 *
 * The Codex app-server is a local process that communicates via JSON-RPC 2.0
 * over stdio (newline-delimited JSON).
 *
 * ## Protocol
 * 1. Send `initialize` with client info
 * 2. Wait for `initialized` response
 * 3. Call methods like `account/read`, `account/rateLimits/read`
 * 4. Receive responses and notifications
 *
 * ## Rate Limits
 * The `account/rateLimits/read` method returns:
 * - rateLimits.primary: usedPercent, windowDurationMins, resetsAt
 * - rateLimits.secondary: optional second window
 * - rateLimitsByLimitId: map of limit buckets by limitId
 *
 * Window durations:
 * - 300 minutes = 5 hours
 * - 10080 minutes = 1 week
 */
/**
 * Result of a startup attempt, carrying diagnostic information.
 */
data class CodexStartupResult(
    val success: Boolean,
    val errorMessage: String? = null,
    val stderrPreview: String? = null,
    val exitCode: Int? = null,
    val codexVersion: String? = null
)

/**
 * Exception thrown when a JSON-RPC method fails.
 * Carries both the error message and classified error code.
 */
class CodexRpcException(
    detailMessage: String,
    val code: String = "unknown"
) : Exception("JSON-RPC error: $detailMessage (code: $code)")

class CodexAppServerClient(
    private val gson: Gson = Gson()
) {

    private var process: Process? = null
    private var inputWriter: OutputStreamWriter? = null
    private var outputReader: BufferedReader? = null
    private var errorReader: BufferedReader? = null
    private var isInitialized = false

    /**
     * Captures stderr output during startup for diagnostics.
     */
    private val startupErrorBuffer = StringBuilder()

    /**
     * Last startup result for diagnostic purposes.
     */
    var lastStartupResult: CodexStartupResult? = null
        private set

    /**
     * Last error code from rateLimits/read failure, for precise UI mapping.
     * Possible values: "token_expired", "refresh_token_reused", "limits_refresh_pending",
     * "unauthorized", "unknown", or null if last read succeeded.
     */
    var lastRateLimitsErrorCode: String? = null
        private set

    /**
     * Last error message from rateLimits/read failure, for UI detail.
     */
    var lastRateLimitsErrorMessage: String? = null
        private set

    private val pendingCalls = ConcurrentHashMap<Int, CompletableFuture<JsonObject>>()
    private val nextId = AtomicInteger(1)

    /**
     * Start the Codex app-server process and initialize connection.
     * @return CodexStartupResult with success status and optional diagnostics.
     */
    fun start(): CodexStartupResult {
        startupErrorBuffer.clear()

        // First check if codex is available and get version
        val codexVersion = getCodexVersion()
        if (codexVersion == null) {
            val result = CodexStartupResult(
                success = false,
                errorMessage = "Codex CLI not found in PATH. Install with: npm i -g @openai/codex",
                codexVersion = null
            )
            lastStartupResult = result
            return result
        }

        return try {
            val processBuilder = ProcessBuilder(CODEX_COMMAND, APP_SERVER_ARGS, "--listen", LISTEN_ARG)
            processBuilder.redirectErrorStream(false)
            // Set working directory to user home to avoid permission issues
            processBuilder.directory(File(System.getProperty("user.home")))
            process = processBuilder.start()

            inputWriter = OutputStreamWriter(process!!.outputStream, Charsets.UTF_8)
            outputReader = BufferedReader(InputStreamReader(process!!.inputStream, Charsets.UTF_8))
            errorReader = BufferedReader(InputStreamReader(process!!.errorStream, Charsets.UTF_8))

            startResponseReader()

            // Give process a moment to start or emit initial errors
            Thread.sleep(200)

            // Check if process is still alive before attempting initialize
            if (!process!!.isAlive) {
                val exitCode = process!!.exitValue()
                val stderrPreview = startupErrorBuffer.toString()
                    .lineSequence()
                    .take(5)
                    .joinToString("\n")
                    .takeIf { it.isNotBlank() }

                val result = CodexStartupResult(
                    success = false,
                    errorMessage = "Codex app-server exited immediately with code $exitCode",
                    stderrPreview = stderrPreview,
                    exitCode = exitCode,
                    codexVersion = codexVersion
                )
                lastStartupResult = result
                TokenPulseLogger.Provider.error("Codex app-server exited with code $exitCode: $stderrPreview")
                return result
            }

            val initializeResult = sendRequest("initialize", createInitializeParams())
                .get(INITIALIZE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            if (initializeResult != null) {
                sendMethod("initialized")
                isInitialized = true
                TokenPulseLogger.Provider.info("Codex app-server initialized successfully (version: $codexVersion)")
                val result = CodexStartupResult(success = true, codexVersion = codexVersion)
                lastStartupResult = result
                result
            } else {
                val result = CodexStartupResult(
                    success = false,
                    errorMessage = "Codex app-server initialize returned null",
                    codexVersion = codexVersion
                )
                lastStartupResult = result
                TokenPulseLogger.Provider.error("Codex app-server initialize returned null")
                result
            }
        } catch (e: java.util.concurrent.TimeoutException) {
            val stderrPreview = startupErrorBuffer.toString()
                .lineSequence()
                .take(5)
                .joinToString("\n")
                .takeIf { it.isNotBlank() }
            val result = CodexStartupResult(
                success = false,
                errorMessage = "Codex app-server initialize timed out after ${INITIALIZE_TIMEOUT_SECONDS}s",
                stderrPreview = stderrPreview,
                codexVersion = codexVersion
            )
            lastStartupResult = result
            TokenPulseLogger.Provider.error("Codex app-server initialize timed out", e)
            result
        } catch (e: Exception) {
            val stderrPreview = startupErrorBuffer.toString()
                .lineSequence()
                .take(5)
                .joinToString("\n")
                .takeIf { it.isNotBlank() }
            val result = CodexStartupResult(
                success = false,
                errorMessage = "Failed to start Codex app-server: ${e.message}",
                stderrPreview = stderrPreview,
                codexVersion = codexVersion
            )
            lastStartupResult = result
            TokenPulseLogger.Provider.error("Failed to start Codex app-server", e)
            result
        }
    }

    /**
     * Get the Codex CLI version string, or null if not available.
     */
    private fun getCodexVersion(): String? {
        return try {
            val processBuilder = ProcessBuilder(CODEX_COMMAND, "--version")
            processBuilder.redirectErrorStream(true)
            val process = processBuilder.start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            output.ifBlank { null }
        } catch (e: Exception) {
            TokenPulseLogger.Provider.debug("Failed to get Codex version: ${e.message}")
            null
        }
    }

    /**
     * Check if the app-server is running and initialized.
     */
    fun isRunning(): Boolean = isInitialized && process?.isAlive == true

    /**
     * Read rate limits from the Codex app-server.
     *
     * Retry logic:
     * - If first call fails with "limits_refresh_pending", wait 1.5s and retry once
     * - For other errors, return null and let caller handle via OAuth fallback
     *
     * @return Parsed rate limits or null if not authenticated or error occurs.
     */
    fun readRateLimits(): RateLimits? {
        if (!isRunning()) {
            val result = start()
            if (!result.success) {
                return null
            }
        }

        // First attempt
        val firstResult = tryReadRateLimits()
        if (firstResult != null) return firstResult

        // Check if retry is warranted (limits_refresh_pending)
        if (lastRateLimitsErrorCode == "limits_refresh_pending") {
            TokenPulseLogger.Provider.info("Codex rate limits refreshing, retrying shortly...")
            Thread.sleep(RATE_LIMITS_RETRY_DELAY_MS) // Wait for limits to become available
            val retryResult = tryReadRateLimits()
            if (retryResult != null) {
                TokenPulseLogger.Provider.info("Codex rate limits read successfully on retry")
                return retryResult
            }
        }

        // Final failure - log appropriately
        val errorCode = lastRateLimitsErrorCode
        val errorMessage = lastRateLimitsErrorMessage ?: "unknown error"

        // Normalize JsonNull and other unhelpful error messages
        val normalizedMessage = when {
            errorMessage == "JsonNull" -> "Rate limit fields were null in app-server response"
            errorMessage.isBlank() -> "unknown error"
            else -> errorMessage
        }

        when (errorCode) {
            "token_expired", "refresh_token_reused", "unauthorized" ->
                TokenPulseLogger.Provider.info("Codex rate limits unavailable: token expired")
            "limits_refresh_pending" ->
                TokenPulseLogger.Provider.info("Codex rate limits still refreshing after retry")
            null, "unknown" ->
                TokenPulseLogger.Provider.warn("Codex rate limits unavailable: $normalizedMessage")
            else ->
                TokenPulseLogger.Provider.info("Codex rate limits unavailable: $errorCode - $normalizedMessage")
        }

        return null
    }

    /**
     * Internal helper to attempt reading rate limits once.
     * @return Parsed rate limits or null on error.
     */
    private fun tryReadRateLimits(): RateLimits? {
        return try {
            val response = sendRequest("account/rateLimits/read")
                .get(METHOD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            response?.let { parseRateLimitsResponse(it) }
        } catch (_: CodexRpcException) {
            // Error from JSON-RPC - code and message already stored in handleResponse
            null
        } catch (e: java.util.concurrent.ExecutionException) {
            // Unwrap ExecutionException to find root cause
            val cause = e.cause
            if (cause is CodexRpcException) {
                // Already handled in handleResponse - just return null
                null
            } else {
                // Other unexpected error
                lastRateLimitsErrorCode = "unknown"
                lastRateLimitsErrorMessage = cause?.message ?: e.message ?: "Unknown error"
                null
            }
        } catch (_: java.util.concurrent.CancellationException) {
            // Request was cancelled
            lastRateLimitsErrorCode = "unknown"
            lastRateLimitsErrorMessage = "Request cancelled"
            null
        } catch (_: java.util.concurrent.TimeoutException) {
            // Request timed out
            lastRateLimitsErrorCode = "unknown"
            lastRateLimitsErrorMessage = "Request timed out"
            null
        } catch (e: Exception) {
            // Other unexpected error
            lastRateLimitsErrorCode = "unknown"
            lastRateLimitsErrorMessage = e.message ?: "Unknown error"
            null
        }
    }

    /**
     * Read account info to check authentication status.
     */
    fun readAccount(): AccountInfo? {
        if (!isRunning()) {
            val result = start()
            if (!result.success) {
                return null
            }
        }

        return try {
            val response = sendRequest("account/read", createMapOf("refreshToken" to false))
                .get(METHOD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            response?.let { parseAccountResponse(it) }
        } catch (e: Exception) {
            TokenPulseLogger.Provider.error("Failed to read account", e)
            null
        }
    }

    /**
     * Stop the app-server process.
     */
    fun stop() {
        try {
            isInitialized = false
            process?.destroy()
            inputWriter?.close()
            outputReader?.close()
            errorReader?.close()
        } catch (e: Exception) {
            TokenPulseLogger.Provider.warn("Error stopping Codex app-server", e)
        } finally {
            process = null
            inputWriter = null
            outputReader = null
            errorReader = null
        }
    }

    /**
     * Create the initialize params with nested clientInfo as required by Codex app-server protocol.
     */
    private fun createInitializeParams(): JsonObject = JsonObject().apply {
        add(
            "clientInfo",
            JsonObject().apply {
                addProperty("name", "tokenpulse")
                addProperty("version", "1.0.0")
            }
        )
    }

    private fun createMapOf(vararg pairs: Pair<String, Any>): JsonObject = JsonObject().apply {
        pairs.forEach { (key, value) ->
            when (value) {
                is String -> addProperty(key, value)
                is Boolean -> addProperty(key, value)
                is Number -> addProperty(key, value)
            }
        }
    }

    private fun sendRequest(method: String, params: JsonObject? = null): CompletableFuture<JsonObject> {
        val id = nextId.getAndIncrement()
        val request = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("method", method)
            addProperty("id", id)
            params?.let { add("params", it) }
        }

        val future = CompletableFuture<JsonObject>()
        pendingCalls[id] = future

        TokenPulseLogger.Provider.debug("Codex JSON-RPC request: $method")

        try {
            synchronized(this) {
                inputWriter?.write(gson.toJson(request))
                inputWriter?.write("\n")
                inputWriter?.flush()
            }
        } catch (e: Exception) {
            pendingCalls.remove(id)
            future.completeExceptionally(e)
        }

        return future
    }

    private fun sendMethod(method: String, params: JsonObject? = null) {
        val request = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("method", method)
            params?.let { add("params", it) }
        }

        try {
            synchronized(this) {
                inputWriter?.write(gson.toJson(request))
                inputWriter?.write("\n")
                inputWriter?.flush()
            }
        } catch (e: Exception) {
            TokenPulseLogger.Provider.warn("Failed to send method $method", e)
        }
    }

    private fun startResponseReader() {
        Thread {
            try {
                var line: String?
                while (outputReader?.readLine().also { line = it } != null) {
                    line?.let { handleResponse(it) }
                }
            } catch (e: Exception) {
                TokenPulseLogger.Provider.warn("Codex response reader error", e)
            }
        }.start()

        Thread {
            try {
                var line: String?
                while (errorReader?.readLine().also { line = it } != null) {
                    line?.let { TokenPulseLogger.Provider.debug("Codex stderr: $it") }
                }
            } catch (_: Exception) {
                // Ignore stderr read errors
            }
        }.start()
    }

    /**
     * Classify a JSON-RPC error message into a known error code.
     * Handles both simple messages and nested JSON body with error.code field.
     */
    private fun classifyErrorCode(message: String): String {
        val lowerMessage = message.lowercase()

        // Check for nested JSON error code pattern: "code": "token_expired"
        val tokenExpiredPattern = Regex("""["']code["']\s*:\s*["']token_expired["']""")
        val refreshTokenReusedPattern = Regex("""["']code["']\s*:\s*["']refresh_token_reused["']""")

        return when {
            // Direct message patterns
            lowerMessage.contains("token_expired") || lowerMessage.contains("token is expired") ||
                lowerMessage.contains("authentication token is expired") -> "token_expired"
            lowerMessage.contains("refresh_token_reused") -> "refresh_token_reused"

            // Nested JSON patterns in full response body
            tokenExpiredPattern.containsMatchIn(message) -> "token_expired"
            refreshTokenReusedPattern.containsMatchIn(message) -> "refresh_token_reused"

            // Unauthorized patterns
            lowerMessage.contains("unauthorized") ||
                (lowerMessage.contains("401") && !lowerMessage.contains("rate")) -> "unauthorized"

            // Transient/refresh patterns
            lowerMessage.contains("refresh requested") ||
                lowerMessage.contains("try again shortly") ||
                lowerMessage.contains("rate limits unavailable") -> "limits_refresh_pending"

            else -> "unknown"
        }
    }

    private fun handleResponse(line: String) {
        if (line.isBlank()) return

        try {
            val response = gson.fromJson(line, JsonObject::class.java)

            if (response.has("id")) {
                val id = response.get("id").asInt
                val future = pendingCalls.remove(id)

                if (response.has("error")) {
                    val error = response.getAsJsonObject("error")
                    val message = error?.get("message")?.asString ?: "Unknown error"

                    // Always use classifier to get semantic code from message/body
                    // Never use JSON-RPC error.code (-32603) for UX classification
                    val code = classifyErrorCode(message)

                    // Store error info for rateLimits method calls
                    future?.let {
                        lastRateLimitsErrorCode = code
                        lastRateLimitsErrorMessage = message
                    }

                    future?.completeExceptionally(CodexRpcException(message, code))
                } else {
                    // Success - clear last error state
                    lastRateLimitsErrorCode = null
                    lastRateLimitsErrorMessage = null

                    val result = response.get("result")
                    future?.complete(if (result is JsonObject) result else JsonObject())
                }
            } else if (response.has("method")) {
                val method = response.get("method").asString
                TokenPulseLogger.Provider.debug("Codex notification: $method")
            }
        } catch (e: Exception) {
            TokenPulseLogger.Provider.warn("Failed to parse Codex response: $line", e)
        }
    }

    private fun parseRateLimitsResponse(response: JsonObject): RateLimits {
        val result = RateLimits()

        response.getAsJsonObject("rateLimits")?.let { rateLimits ->
            rateLimits.getAsJsonObject("primary")?.let { result.primary = parseRateLimitBucket(it) }
            rateLimits.getAsJsonObject("secondary")?.let { result.secondary = parseRateLimitBucket(it) }
        }

        response.getAsJsonObject("rateLimitsByLimitId")?.let { byId ->
            byId.keySet().forEach { limitId ->
                val value = byId.get(limitId)
                if (value is JsonObject) {
                    val bucket = parseRateLimitBucket(value).also { it.limitId = limitId }
                    result.bucketsById[limitId] = bucket
                }
            }
        }

        result.bucketsByWindow[WINDOW_5_HOURS] =
            result.bucketsById.values.filter { it.windowDurationMins == WINDOW_5_HOURS }
        result.bucketsByWindow[WINDOW_WEEK] =
            result.bucketsById.values.filter { it.windowDurationMins == WINDOW_WEEK }

        result.codeReviewBucket = result.bucketsById.values.find { bucket ->
            bucket.windowDurationMins == WINDOW_WEEK &&
                (
                    bucket.limitName?.contains("review", ignoreCase = true) == true ||
                        bucket.limitId?.contains("review", ignoreCase = true) == true
                    )
        }

        TokenPulseLogger.Provider.debug(
            "Rate limits: primary=${result.primary?.usedPercent}%, " +
                "5h_buckets=${result.bucketsByWindow[WINDOW_5_HOURS]?.size}, " +
                "weekly_buckets=${result.bucketsByWindow[WINDOW_WEEK]?.size}"
        )

        return result
    }

    private fun parseRateLimitBucket(json: JsonObject): RateLimitBucket {
        // Safely extract values, handling JsonNull explicitly
        val usedPercent = json.get("usedPercent")
            ?.takeIf { !it.isJsonNull }
            ?.asDouble
            ?.toFloat()

        val windowDurationMins = json.get("windowDurationMins")
            ?.takeIf { !it.isJsonNull }
            ?.asInt

        val resetsAt = json.get("resetsAt")
            ?.takeIf { !it.isJsonNull }
            ?.asLong

        val limitId = json.get("limitId")
            ?.takeIf { !it.isJsonNull }
            ?.asString

        val limitName = json.get("limitName")
            ?.takeIf { !it.isJsonNull }
            ?.asString

        return RateLimitBucket(
            usedPercent = usedPercent,
            windowDurationMins = windowDurationMins,
            resetsAt = resetsAt,
            limitId = limitId,
            limitName = limitName
        )
    }

    /**
     * Parse the account response from Codex app-server.
     *
     * Response format:
     * {
     *   "account": {
     *     "type": "chatgpt",
     *     "email": "user@example.com",
     *     "planType": "plus"
     *   },
     *   "requiresOpenaiAuth": true
     * }
     */
    private fun parseAccountResponse(response: JsonObject): AccountInfo {
        val accountObj = response.getAsJsonObject("account")
        return AccountInfo(
            email = accountObj?.get("email")?.asString,
            type = accountObj?.get("type")?.asString,
            planType = accountObj?.get("planType")?.asString,
            requiresOpenaiAuth = response.get("requiresOpenaiAuth")?.asBoolean ?: false
        )
    }

    /**
     * Represents a rate limit bucket with usage information.
     */
    data class RateLimitBucket(
        var limitId: String? = null,
        var limitName: String? = null,
        val usedPercent: Float? = null,
        val windowDurationMins: Int? = null,
        val resetsAt: Long? = null
    )

    /**
     * Contains all rate limit information from the Codex app-server.
     *
     * Bucket resolution priority:
     * 1. `bucketsById` lookup by window duration (most reliable)
     * 2. `primary`/`secondary` buckets if their window matches
     * 3. Any bucket with matching window from combined sources
     */
    data class RateLimits(
        var primary: RateLimitBucket? = null,
        var secondary: RateLimitBucket? = null,
        val bucketsById: MutableMap<String, RateLimitBucket> = mutableMapOf(),
        val bucketsByWindow: MutableMap<Int, List<RateLimitBucket>> = mutableMapOf(),
        var codeReviewBucket: RateLimitBucket? = null
    ) {
        /**
         * Returns the 5-hour (300 min) rate limit bucket.
         * Priority: primary/secondary → by-id lookup (excluding special buckets).
         */
        val fiveHourBucket: RateLimitBucket?
            get() {
                // First: check primary/secondary directly (Codex canonical windows)
                primary?.takeIf { it.windowDurationMins == WINDOW_5_HOURS }?.let { return it }
                secondary?.takeIf { it.windowDurationMins == WINDOW_5_HOURS }?.let { return it }

                // Second: scan bucketsById for 300-min window, excluding code-review buckets
                return bucketsById.values.firstOrNull { bucket ->
                    bucket.windowDurationMins == WINDOW_5_HOURS && !isCodeReviewBucket(bucket)
                }
            }

        /**
         * Returns the weekly (10080 min) rate limit bucket.
         * Priority: primary/secondary → by-id lookup (excluding code-review bucket).
         */
        val weeklyBucket: RateLimitBucket?
            get() {
                // First: check primary/secondary directly (Codex canonical windows)
                primary?.takeIf { it.windowDurationMins == WINDOW_WEEK }?.let { return it }
                secondary?.takeIf { it.windowDurationMins == WINDOW_WEEK }?.let { return it }

                // Second: scan bucketsById for 10080-min window, excluding code-review bucket
                return bucketsById.values.firstOrNull { bucket ->
                    bucket.windowDurationMins == WINDOW_WEEK && !isCodeReviewBucket(bucket)
                }
            }

        /**
         * Check if a bucket is the code-review-specific limit (should not be used for general weekly).
         */
        private fun isCodeReviewBucket(bucket: RateLimitBucket): Boolean {
            return bucket.limitName?.contains("review", ignoreCase = true) == true ||
                bucket.limitId?.contains("review", ignoreCase = true) == true
        }
    }

    /**
     * Account information from the Codex app-server.
     *
     * Response format:
     * {
     *   "account": {
     *     "type": "chatgpt",
     *     "email": "user@example.com",
     *     "planType": "plus"
     *   },
     *   "requiresOpenaiAuth": true
     * }
     */
    data class AccountInfo(
        val email: String? = null,
        val type: String? = null,
        val planType: String? = null,
        val requiresOpenaiAuth: Boolean = false
    ) {
        /**
         * Authentication is considered successful if:
         * - account.type is "chatgpt" (user is logged in to ChatGPT)
         * - OR email is present (account is identified)
         */
        val isAuthenticated: Boolean
            get() = type == "chatgpt" || !email.isNullOrBlank()
    }

    companion object {
        private const val CODEX_COMMAND = "codex"
        private const val APP_SERVER_ARGS = "app-server"
        private const val LISTEN_ARG = "stdio://"
        private const val INITIALIZE_TIMEOUT_SECONDS = 30L
        private const val METHOD_TIMEOUT_SECONDS = 10L

        /** Retry delay for limits_refresh_pending scenario (1.5 seconds). */
        private const val RATE_LIMITS_RETRY_DELAY_MS = 1500L

        /** 5-hour window duration in minutes. */
        private const val WINDOW_5_HOURS = 300

        /** Weekly window duration in minutes. */
        private const val WINDOW_WEEK = 10080
    }
}
