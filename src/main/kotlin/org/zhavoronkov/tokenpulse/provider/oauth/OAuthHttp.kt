package org.zhavoronkov.tokenpulse.provider.oauth

import org.zhavoronkov.tokenpulse.utils.PluginVersion
import java.net.http.HttpClient
import java.time.Duration

/**
 * User-Agent sent by all TokenPulse OAuth clients (Claude, Codex).
 *
 * Resolves to `TokenPulse/<pluginVersion>` (e.g. `TokenPulse/0.3.1`) at first
 * use. Lazy — not `const` — because the version is read from the classpath
 * resource, not baked in at compile time.
 */
internal val OAUTH_USER_AGENT: String by lazy { "TokenPulse/${PluginVersion.value}" }

/** Max characters of a response body echoed into an error/log message. */
internal const val OAUTH_BODY_PREVIEW = 200

/** Build an [HttpClient] with the given connect timeout (seconds). */
internal fun oauthHttpClient(connectSeconds: Long): HttpClient =
    HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(connectSeconds))
        .build()
