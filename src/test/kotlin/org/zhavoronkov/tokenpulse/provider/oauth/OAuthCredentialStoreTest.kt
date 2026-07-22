package org.zhavoronkov.tokenpulse.provider.oauth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/**
 * Direct tests for the shared credential-store seam. The existing Claude/Codex
 * reader tests exercise this indirectly through their public write methods; these
 * tests pin the seam's contract independently.
 */
class OAuthCredentialStoreTest {

    @Test
    fun `writeAtomic writes content and leaves no temp file`(@TempDir tmp: Path) {
        val target = File(tmp.toFile(), "creds.json")
        OAuthCredentialStore.writeAtomic(target, """{"a":1}""", tmpPrefix = "creds")
        assertEquals("""{"a":1}""", target.readText(Charsets.UTF_8))
        val leftovers = tmp.toFile().listFiles().orEmpty().filter { it.name.endsWith(".json.tmp") }
        assertTrue(leftovers.isEmpty(), "temp files should be cleaned up: $leftovers")
    }

    @Test
    fun `writeAtomic creates parent directories`(@TempDir tmp: Path) {
        val target = File(tmp.toFile(), "nested/dir/creds.json")
        assertFalse(target.parentFile.exists())
        OAuthCredentialStore.writeAtomic(target, "{}", tmpPrefix = "creds")
        assertTrue(target.exists())
    }

    @Test
    fun `writeAtomic sets owner-only 0600 permissions on POSIX`(@TempDir tmp: Path) {
        val target = File(tmp.toFile(), "creds.json")
        OAuthCredentialStore.writeAtomic(target, "{}", tmpPrefix = "creds")
        val perms = try {
            Files.getPosixFilePermissions(target.toPath())
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX filesystem: no assertion possible.
            return
        }
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms)
    }

    @Test
    fun `writeAtomic overwrites existing target`(@TempDir tmp: Path) {
        val target = File(tmp.toFile(), "creds.json")
        target.writeText("""{"old":true}""")
        OAuthCredentialStore.writeAtomic(target, """{"new":true}""", tmpPrefix = "creds")
        assertEquals("""{"new":true}""", target.readText(Charsets.UTF_8))
    }

    @Test
    fun `readJsonObject returns null for a missing file`(@TempDir tmp: Path) {
        assertNull(OAuthCredentialStore.readJsonObject(File(tmp.toFile(), "nope.json")))
    }

    @Test
    fun `readJsonObject returns null for malformed JSON`(@TempDir tmp: Path) {
        val target = File(tmp.toFile(), "bad.json").apply { writeText("not json {") }
        assertNull(OAuthCredentialStore.readJsonObject(target))
    }

    @Test
    fun `readJsonObject returns null when the root is an array`(@TempDir tmp: Path) {
        val target = File(tmp.toFile(), "arr.json").apply { writeText("[1,2,3]") }
        assertNull(OAuthCredentialStore.readJsonObject(target))
    }

    @Test
    fun `readJsonObject parses a valid object`(@TempDir tmp: Path) {
        val target = File(tmp.toFile(), "obj.json").apply { writeText("""{"k":"v"}""") }
        val parsed = OAuthCredentialStore.readJsonObject(target)
        assertNotNull(parsed)
        assertEquals("v", parsed!!.get("k").asString)
    }
}
