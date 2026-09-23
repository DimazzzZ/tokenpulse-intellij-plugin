package org.zhavoronkov.tokenpulse.settings

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import org.zhavoronkov.tokenpulse.utils.TokenPulseLogger

/**
 * Raw key/value secret storage. One [get] of a key = one OS keychain access,
 * which on macOS may show an access prompt.
 */
internal interface SecretBackend {
    fun get(key: String): String?
    fun set(key: String, value: String?)
}

/** [SecretBackend] on top of IntelliJ's PasswordSafe (macOS Keychain, KeePass, ...). */
private class PasswordSafeBackend : SecretBackend {
    override fun get(key: String): String? = PasswordSafe.instance.getPassword(attributes(key))

    override fun set(key: String, value: String?) = PasswordSafe.instance.setPassword(attributes(key), value)

    // Constructed via the Java shim so the emitted bytecode binds to the plain
    // CredentialAttributes(String, String) JVM constructor. A Kotlin-side call would
    // route through the default-args synthetic ctor that 2026.1 (build 261) marks
    // @Deprecated(ERROR); see CredentialAttributesFactory for details.
    private fun attributes(key: String) =
        CredentialAttributesFactory.create(generateServiceName("TokenPulse", key), key)
}

/**
 * Secure credential storage for API keys and OAuth tokens.
 *
 * All secrets are kept in ONE PasswordSafe entry (the vault, a JSON map
 * accountId -> secret) that is read once per IDE session and cached in memory.
 * macOS ties a Keychain "Always Allow" grant to the IDE's code signature, so
 * every IDE update re-prompts once per entry; a single entry means a single prompt.
 *
 * Older versions stored one entry per account. Such a legacy entry is migrated
 * into the vault on first access and then deleted.
 */
@Service(Service.Level.APP)
class CredentialsStore internal constructor(private val backend: SecretBackend) {
    constructor() : this(PasswordSafeBackend())

    companion object {
        internal const val VAULT_KEY = "vault"

        fun getInstance(): CredentialsStore = service()
    }

    private val gson = Gson()
    private val lock = Any()
    private var vault: MutableMap<String, String>? = null

    /** Account ids whose legacy per-account entry was already checked/removed this session. */
    private val legacyChecked = mutableSetOf<String>()

    fun saveApiKey(accountId: String, apiKey: String) = synchronized(lock) {
        loadedVault()[accountId] = apiKey
        persist()
        dropLegacy(accountId)
    }

    fun getApiKey(accountId: String): String? = synchronized(lock) {
        loadedVault()[accountId] ?: migrateLegacy(accountId)
    }

    fun removeApiKey(accountId: String) = synchronized(lock) {
        if (loadedVault().remove(accountId) != null) persist()
        dropLegacy(accountId)
    }

    private fun loadedVault(): MutableMap<String, String> =
        vault ?: parseVault(backend.get(VAULT_KEY)).also { vault = it }

    private fun parseVault(raw: String?): MutableMap<String, String> {
        if (raw.isNullOrEmpty()) return mutableMapOf()
        return try {
            gson.fromJson(raw, JsonObject::class.java).entrySet()
                .filter { it.value.isJsonPrimitive }
                .associateTo(mutableMapOf()) { it.key to it.value.asString }
        } catch (e: Exception) {
            TokenPulseLogger.Settings.warn("Credential vault is unreadable, starting empty", e)
            mutableMapOf()
        }
    }

    private fun persist() {
        backend.set(VAULT_KEY, gson.toJson(loadedVault()))
    }

    private fun migrateLegacy(accountId: String): String? {
        if (!legacyChecked.add(accountId)) return null
        val legacy = backend.get(accountId) ?: return null
        // Write the vault first so a failure between the two steps never loses the secret.
        loadedVault()[accountId] = legacy
        persist()
        backend.set(accountId, null)
        TokenPulseLogger.Settings.info("Migrated credential for account $accountId into the vault")
        return legacy
    }

    private fun dropLegacy(accountId: String) {
        if (legacyChecked.add(accountId)) backend.set(accountId, null)
    }
}
