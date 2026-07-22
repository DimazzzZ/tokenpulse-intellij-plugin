package org.zhavoronkov.tokenpulse.utils

import java.util.Properties

/**
 * Reads the plugin's own version from the `tokenpulse.properties` classpath
 * resource (populated by Gradle's `processResources` `expand()` from the
 * `pluginVersion` property in `gradle.properties`).
 *
 * Pure-JVM: no IntelliJ platform dependency, so it is safe to call from unit
 * tests that run without a live `Application`. Cached; falls back to
 * `"unknown"` on any failure (missing resource, malformed properties,
 * unsubstituted placeholder that still starts with `$` or `@`).
 */
internal object PluginVersion {
    val value: String by lazy { load() }

    private fun load(): String = try {
        val props = Properties()
        PluginVersion::class.java.classLoader
            .getResourceAsStream("tokenpulse.properties")
            ?.use(props::load)
        val raw = props.getProperty("version").orEmpty().trim()
        // Defensive: if the Gradle placeholder ever ships unsubstituted, don't
        // leak `${pluginVersion}` / `@pluginVersion@` into HTTP headers.
        if (raw.isNotEmpty() && !raw.startsWith("$") && !raw.startsWith("@")) raw else "unknown"
    } catch (_: Exception) {
        "unknown"
    }
}
