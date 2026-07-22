package org.zhavoronkov.tokenpulse.provider.oauth

import java.net.http.HttpClient
import java.time.Duration

/** User-Agent sent by all TokenPulse OAuth clients (Claude, Codex). */
internal const val OAUTH_USER_AGENT = "TokenPulse/1.0"

/** Max characters of a response body echoed into an error/log message. */
internal const val OAUTH_BODY_PREVIEW = 200

/** Build an [HttpClient] with the given connect timeout (seconds). */
internal fun oauthHttpClient(connectSeconds: Long): HttpClient =
    HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(connectSeconds))
        .build()
