package org.zhavoronkov.tokenpulse.provider.openai.chatgpt

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.zhavoronkov.tokenpulse.utils.TokenPulseLogger
import java.io.BufferedReader
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
class CodexAppServerClient(
    private val gson: Gson = Gson()
) {

    private var process: Process? = null
    private var inputWriter: OutputStreamWriter? = null
    private var outputReader: BufferedReader? = null
    private var errorReader: BufferedReader? = null
    private var isInitialized = false

    private val pendingCalls = ConcurrentHashMap<Int, CompletableFuture<JsonObject>>()
    private val nextId = AtomicInteger(1)

    /**
     * Start the Codex app-server process and initialize connection.
     */
    fun start(): Boolean {
        return try {
            val processBuilder = ProcessBuilder(CODEX_COMMAND, APP_SERVER_ARGS, "--listen", LISTEN_ARG)
            processBuilder.redirectErrorStream(false)
            process = processBuilder.start()

            inputWriter = OutputStreamWriter(process!!.outputStream, Charsets.UTF_8)
            outputReader = BufferedReader(InputStreamReader(process!!.inputStream, Charsets.UTF_8))
            errorReader = BufferedReader(InputStreamReader(process!!.errorStream, Charsets.UTF_8))

            startResponseReader()

            val initializeResult = sendRequest("initialize", createClientInfo())
                .get(INITIALIZE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            if (initializeResult != null) {
                sendMethod("initialized")
                isInitialized = true
                TokenPulseLogger.Provider.info("Codex app-server initialized successfully")
                true
            } else {
                TokenPulseLogger.Provider.error("Codex app-server initialize returned null")
                false
            }
        } catch (e: Exception) {
            TokenPulseLogger.Provider.error("Failed to start Codex app-server", e)
            false
        }
    }

    /**
     * Check if the Codex CLI is available in PATH.
     * This is a lightweight check that doesn't start the app-server.
     */
    fun isCodexAvailable(): Boolean {
        return try {
            val processBuilder = ProcessBuilder(CODEX_COMMAND, "--version")
            processBuilder.redirectErrorStream(true)
            val process = processBuilder.start()
            process.waitFor() == 0
        } catch (e: Exception) {
            TokenPulseLogger.Provider.debug("Codex CLI not available: ${e.message}")
            false
        }
    }

    /**
     * Check if the app-server is running and initialized.
     */
    fun isRunning(): Boolean = isInitialized && process?.isAlive == true

    /**
     * Read rate limits from the Codex app-server.
     *
     * @return Parsed rate limits or null if not authenticated or error occurs.
     */
    fun readRateLimits(): RateLimits? {
        if (!isRunning() && !start()) {
            return null
        }

        return try {
            val response = sendRequest("account/rateLimits/read")
                .get(METHOD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            response?.let { parseRateLimitsResponse(it) }
        } catch (e: Exception) {
            TokenPulseLogger.Provider.error("Failed to read rate limits", e)
            null
        }
    }

    /**
     * Read account info to check authentication status.
     */
    fun readAccount(): AccountInfo? {
        if (!isRunning() && !start()) {
            return null
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
     * Start the login flow (managed mode).
     *
     * @return The auth URL to open in browser, or null on error.
     */
    fun startLogin(): String? {
        if (!isRunning() && !start()) {
            return null
        }

        return try {
            val response = sendRequest("account/login/start", createMapOf("type" to "chatgpt"))
                .get(METHOD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            response?.get("authUrl")?.asString
        } catch (e: Exception) {
            TokenPulseLogger.Provider.error("Failed to start login", e)
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

    private fun createClientInfo(): JsonObject = JsonObject().apply {
        addProperty("name", "tokenpulse")
        addProperty("title", "Token Pulse")
        addProperty("version", "1.0.0")
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
                    future?.completeExceptionally(Exception("JSON-RPC error: $message"))
                } else {
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

    private fun parseRateLimitBucket(json: JsonObject): RateLimitBucket = RateLimitBucket(
        usedPercent = json.get("usedPercent")?.asDouble?.toFloat(),
        windowDurationMins = json.get("windowDurationMins")?.asInt,
        resetsAt = json.get("resetsAt")?.asLong,
        limitId = json.get("limitId")?.asString,
        limitName = json.get("limitName")?.asString
    )

    private fun parseAccountResponse(response: JsonObject): AccountInfo = AccountInfo(
        email = response.get("email")?.asString,
        authMode = response.get("authMode")?.asString,
        planType = response.get("planType")?.asString
    )

    /**
     * Represents a rate limit bucket with usage information.
     */
    data class RateLimitBucket(
        var limitId: String? = null,
        var limitName: String? = null,
        val usedPercent: Float? = null,
        val windowDurationMins: Int? = null,
        val resetsAt: Long? = null
    ) {
        val remainingPercent: Float?
            get() = usedPercent?.let { (100f - it).coerceIn(0f, 100f) }
    }

    /**
     * Contains all rate limit information from the Codex app-server.
     */
    data class RateLimits(
        var primary: RateLimitBucket? = null,
        var secondary: RateLimitBucket? = null,
        val bucketsById: MutableMap<String, RateLimitBucket> = mutableMapOf(),
        val bucketsByWindow: MutableMap<Int, List<RateLimitBucket>> = mutableMapOf(),
        var codeReviewBucket: RateLimitBucket? = null
    ) {
        val fiveHourBucket: RateLimitBucket?
            get() = bucketsByWindow[WINDOW_5_HOURS]?.firstOrNull()

        val weeklyBucket: RateLimitBucket?
            get() = bucketsByWindow[WINDOW_WEEK]?.firstOrNull()
    }

    /**
     * Account information from the Codex app-server.
     */
    data class AccountInfo(
        val email: String? = null,
        val authMode: String? = null,
        val planType: String? = null
    ) {
        val isAuthenticated: Boolean
            get() = authMode == "chatgpt" || authMode == "apikey"
    }

    companion object {
        private const val CODEX_COMMAND = "codex"
        private const val APP_SERVER_ARGS = "app-server"
        private const val LISTEN_ARG = "stdio://"
        private const val INITIALIZE_TIMEOUT_SECONDS = 30L
        private const val METHOD_TIMEOUT_SECONDS = 10L

        /** 5-hour window duration in minutes. */
        private const val WINDOW_5_HOURS = 300

        /** Weekly window duration in minutes. */
        private const val WINDOW_WEEK = 10080
    }
}
