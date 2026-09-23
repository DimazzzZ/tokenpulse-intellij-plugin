package org.zhavoronkov.tokenpulse.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [CredentialsStore]: all secrets live in ONE backend entry (the vault),
 * so macOS Keychain asks for access once per IDE build instead of once per account.
 */
class CredentialsStoreTest {

    /** In-memory backend that counts reads per key (each read = potential Keychain prompt). */
    private class FakeBackend(initial: Map<String, String> = emptyMap()) : SecretBackend {
        val entries = initial.toMutableMap()
        val reads = mutableMapOf<String, Int>()

        override fun get(key: String): String? {
            reads.merge(key, 1, Int::plus)
            return entries[key]
        }

        override fun set(key: String, value: String?) {
            if (value == null) entries.remove(key) else entries[key] = value
        }
    }

    @Test
    fun `saved keys are stored in a single vault entry`() {
        val backend = FakeBackend()
        val store = CredentialsStore(backend)

        store.saveApiKey("a1", "key-1")
        store.saveApiKey("a2", "key-2")

        assertEquals(setOf(CredentialsStore.VAULT_KEY), backend.entries.keys)
    }

    @Test
    fun `keys round-trip through a fresh store instance`() {
        val backend = FakeBackend()
        CredentialsStore(backend).apply {
            saveApiKey("a1", "key-1")
            saveApiKey("a2", "{\"pat\":\"x\",\"org\":\"y\"}")
        }

        val reopened = CredentialsStore(backend)

        assertEquals("key-1", reopened.getApiKey("a1"))
        assertEquals("{\"pat\":\"x\",\"org\":\"y\"}", reopened.getApiKey("a2"))
    }

    @Test
    fun `vault is read from the backend only once for many accounts`() {
        val backend = FakeBackend()
        CredentialsStore(backend).apply { (1..10).forEach { saveApiKey("a$it", "key-$it") } }
        backend.reads.clear()

        val store = CredentialsStore(backend)
        repeat(3) { (1..10).forEach { i -> assertEquals("key-$i", store.getApiKey("a$i")) } }

        assertEquals(1, backend.reads[CredentialsStore.VAULT_KEY])
        assertEquals(setOf(CredentialsStore.VAULT_KEY), backend.reads.keys)
    }

    @Test
    fun `remove deletes the key from the vault`() {
        val backend = FakeBackend()
        val store = CredentialsStore(backend)
        store.saveApiKey("a1", "key-1")
        store.saveApiKey("a2", "key-2")

        store.removeApiKey("a1")

        assertNull(store.getApiKey("a1"))
        assertNull(CredentialsStore(backend).getApiKey("a1"))
        assertEquals("key-2", CredentialsStore(backend).getApiKey("a2"))
    }

    @Test
    fun `legacy per-account entry is migrated into the vault and deleted`() {
        val backend = FakeBackend(mapOf("a1" to "legacy-key"))
        val store = CredentialsStore(backend)

        assertEquals("legacy-key", store.getApiKey("a1"))

        assertFalse(backend.entries.containsKey("a1"))
        assertEquals("legacy-key", CredentialsStore(backend).getApiKey("a1"))
    }

    @Test
    fun `missing legacy entry is looked up only once per session`() {
        val backend = FakeBackend()
        val store = CredentialsStore(backend)

        repeat(5) { assertNull(store.getApiKey("ghost")) }

        assertEquals(1, backend.reads["ghost"])
    }

    @Test
    fun `migrated account is not looked up in legacy storage again`() {
        val backend = FakeBackend(mapOf("a1" to "legacy-key"))
        CredentialsStore(backend).getApiKey("a1")
        backend.reads.clear()

        CredentialsStore(backend).getApiKey("a1")

        assertNull(backend.reads["a1"])
    }

    @Test
    fun `saving over a legacy entry removes the legacy entry`() {
        val backend = FakeBackend(mapOf("a1" to "old"))
        val store = CredentialsStore(backend)

        store.saveApiKey("a1", "new")

        assertFalse(backend.entries.containsKey("a1"))
        assertEquals("new", CredentialsStore(backend).getApiKey("a1"))
    }

    @Test
    fun `removing an account also removes its legacy entry`() {
        val backend = FakeBackend(mapOf("a1" to "old"))

        CredentialsStore(backend).removeApiKey("a1")

        assertTrue(backend.entries.isEmpty() || backend.entries.keys == setOf(CredentialsStore.VAULT_KEY))
        assertNull(CredentialsStore(backend).getApiKey("a1"))
    }

    @Test
    fun `corrupt vault is treated as empty and legacy entries still resolve`() {
        val backend = FakeBackend(mapOf(CredentialsStore.VAULT_KEY to "not json", "a1" to "legacy-key"))

        assertEquals("legacy-key", CredentialsStore(backend).getApiKey("a1"))
    }
}
