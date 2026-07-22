package org.zhavoronkov.tokenpulse.provider.oauth

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.zhavoronkov.tokenpulse.utils.TokenPulseLogger
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/**
 * Shared plaintext-credential-file mechanics for the OAuth providers
 * (Claude Code's `.credentials.json`, Codex's `auth.json`). Both stores read a
 * JSON blob, mutate only the rotating token fields, and write the result back
 * atomically with owner-only permissions.
 *
 * This module owns the file mechanics that were byte-identical between the two
 * readers; the provider-specific concerns (which JSON fields to mutate, and
 * Claude's additional macOS Keychain target) stay in the individual readers.
 */
internal object OAuthCredentialStore {

    /**
     * Write [content] to [target] atomically: write a sibling temp file, tighten
     * it to 0600, then `Files.move` it into place (ATOMIC_MOVE, falling back to a
     * plain replace where the filesystem lacks atomic move). Parent directories
     * are created. The temp file is always cleaned up.
     *
     * @param tmpPrefix temp-file name prefix (e.g. ".credentials" / "auth").
     */
    fun writeAtomic(target: File, content: String, tmpPrefix: String) {
        val dir = target.parentFile ?: File(".")
        dir.mkdirs()
        val tmp = File.createTempFile(tmpPrefix, ".json.tmp", dir)
        try {
            tmp.writeText(content, Charsets.UTF_8)
            setOwnerOnlyPermissions(tmp)
            val src = tmp.toPath()
            val dst = target.toPath()
            try {
                Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** Tighten [file] to owner read/write only (0600). No-op on non-POSIX filesystems. */
    fun setOwnerOnlyPermissions(file: File) {
        try {
            Files.setPosixFilePermissions(
                file.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX filesystem (e.g. Windows): nothing to tighten.
        } catch (e: Exception) {
            TokenPulseLogger.Provider.debug("[OAuthCredentialStore] Could not set 0600 on temp file: ${e.message}")
        }
    }

    /**
     * Parse [file] into a [JsonObject], or `null` if the file is missing,
     * unreadable, malformed, or not a JSON object at the root.
     */
    fun readJsonObject(file: File): JsonObject? {
        return try {
            if (!file.exists()) return null
            val element = JsonParser.parseString(file.readText(Charsets.UTF_8))
            if (element.isJsonObject) element.asJsonObject else null
        } catch (_: Exception) {
            null
        }
    }
}
