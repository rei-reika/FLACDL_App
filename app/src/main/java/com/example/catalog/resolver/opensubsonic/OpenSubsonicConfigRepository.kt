package com.example.catalog.resolver.opensubsonic

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface OpenSubsonicConfigStore {
    fun loadAll(): List<OpenSubsonicServerProfile>
    fun saveAll(profiles: List<OpenSubsonicServerProfile>)
    fun clearAll()
}

/**
 * In-memory store for deterministic JVM unit tests.
 */
class InMemoryOpenSubsonicConfigStore : OpenSubsonicConfigStore {
    @Volatile
    private var stored: List<OpenSubsonicServerProfile> = emptyList()

    override fun loadAll(): List<OpenSubsonicServerProfile> = stored

    override fun saveAll(profiles: List<OpenSubsonicServerProfile>) {
        stored = profiles.toList()
    }

    override fun clearAll() {
        stored = emptyList()
    }
}

/**
 * Android Keystore-backed encrypted store (AES-256-GCM) for OpenSubsonic server configurations.
 *
 * Security guarantees:
 * - Reuses the same Keystore AES-256-GCM design as `AndroidKeystoreTokenStore` with zero extra dependencies.
 * - Credentials are NEVER stored in plaintext in SharedPreferences, Room, or logs.
 * - Each write generates a fresh random GCM IV.
 */
class AndroidKeystoreOpenSubsonicConfigStore(
    context: Context
) : OpenSubsonicConfigStore {

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    override fun saveAll(profiles: List<OpenSubsonicServerProfile>) {
        val json = serializeProfiles(profiles)
        val secretKey = getOrCreateSecretKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(json.toByteArray(StandardCharsets.UTF_8))

        val combined = ByteArray(1 + iv.size + ciphertext.size)
        combined[0] = iv.size.toByte()
        System.arraycopy(iv, 0, combined, 1, iv.size)
        System.arraycopy(ciphertext, 0, combined, 1 + iv.size, ciphertext.size)

        val encoded = Base64.encodeToString(combined, Base64.NO_WRAP)
        prefs.edit().putString(KEY_ENCRYPTED_SERVERS, encoded).apply()
    }

    override fun loadAll(): List<OpenSubsonicServerProfile> {
        val encoded = prefs.getString(KEY_ENCRYPTED_SERVERS, null) ?: return emptyList()
        return try {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            if (combined.size < 14) return emptyList()
            val ivLen = combined[0].toInt() and 0xFF
            if (ivLen <= 0 || combined.size <= 1 + ivLen) return emptyList()

            val iv = combined.copyOfRange(1, 1 + ivLen)
            val ciphertext = combined.copyOfRange(1 + ivLen, combined.size)

            val secretKey = getExistingSecretKey() ?: return emptyList()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            val plaintext = String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
            deserializeProfiles(plaintext)
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun clearAll() {
        prefs.edit().remove(KEY_ENCRYPTED_SERVERS).apply()
    }

    private fun getExistingSecretKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
    }

    private fun getOrCreateSecretKey(): SecretKey {
        getExistingSecretKey()?.let { return it }
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "flacdl_opensubsonic_config_aes_key_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val PREFS_NAME = "flacdl_opensubsonic_vault"
        private const val KEY_ENCRYPTED_SERVERS = "enc_opensubsonic_servers_v1"

        internal fun serializeProfiles(profiles: List<OpenSubsonicServerProfile>): String {
            val arr = JSONArray()
            profiles.forEach { p ->
                arr.put(
                    JSONObject().apply {
                        put("id", p.id)
                        put("name", p.name)
                        put("server_url", p.serverUrl)
                        put("username", p.username)
                        put("credential", p.credential)
                        put("auth_type", p.authType.name)
                        if (p.salt != null) put("salt", p.salt)
                        put("enabled", p.enabled)
                        put("timeout_ms", p.timeoutMs)
                        put("max_candidates", p.maxCandidates)
                    }
                )
            }
            return arr.toString()
        }

        internal fun deserializeProfiles(rawJson: String): List<OpenSubsonicServerProfile> {
            val arr = JSONArray(rawJson)
            val out = mutableListOf<OpenSubsonicServerProfile>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id", "").trim()
                val name = obj.optString("name", "").trim()
                val url = obj.optString("server_url", "").trim()
                val credential = obj.optString("credential", "").trim()
                if (id.isEmpty() || name.isEmpty() || url.isEmpty() || credential.isEmpty()) continue
                val authTypeName = obj.optString("auth_type", OpenSubsonicAuthType.TOKEN.name)
                val authType = OpenSubsonicAuthType.entries.firstOrNull { it.name == authTypeName }
                    ?: OpenSubsonicAuthType.TOKEN
                out.add(
                    OpenSubsonicServerProfile(
                        id = id,
                        name = name,
                        serverUrl = url,
                        username = obj.optString("username", "").trim(),
                        credential = credential,
                        authType = authType,
                        salt = obj.optString("salt", "").trim().takeIf { it.isNotEmpty() },
                        enabled = obj.optBoolean("enabled", true),
                        timeoutMs = obj.optLong("timeout_ms", 10_000L).coerceAtLeast(1_000L),
                        maxCandidates = obj.optInt("max_candidates", 10).coerceAtLeast(1)
                    )
                )
            }
            return out
        }
    }
}

/**
 * Repository managing user-configured OpenSubsonic servers.
 * Kept strictly separate from resolver logic and backed by encrypted [OpenSubsonicConfigStore].
 */
class OpenSubsonicConfigRepository(
    private val store: OpenSubsonicConfigStore
) {
    private val lock = Any()
    private val _servers = MutableStateFlow(store.loadAll())
    val servers: StateFlow<List<OpenSubsonicServerProfile>> = _servers.asStateFlow()

    fun getAllServers(): List<OpenSubsonicServerProfile> = _servers.value

    fun getEnabledServers(): List<OpenSubsonicServerProfile> = _servers.value.filter { it.enabled }

    fun getServer(serverId: String): OpenSubsonicServerProfile? =
        _servers.value.firstOrNull { it.id == serverId }

    fun saveServer(profile: OpenSubsonicServerProfile): OpenSubsonicConfigValidationResult {
        val validation = OpenSubsonicConfigValidator.validate(profile)
        if (validation !is OpenSubsonicConfigValidationResult.Valid) return validation

        val normalized = validation.normalizedProfile
        synchronized(lock) {
            val updated = _servers.value.toMutableList()
            val idx = updated.indexOfFirst { it.id == normalized.id }
            if (idx >= 0) {
                updated[idx] = normalized
            } else {
                updated.add(normalized)
            }
            store.saveAll(updated)
            _servers.value = updated.toList()
        }
        return validation
    }

    fun updateServer(profile: OpenSubsonicServerProfile): OpenSubsonicConfigValidationResult {
        val validation = OpenSubsonicConfigValidator.validate(profile)
        if (validation !is OpenSubsonicConfigValidationResult.Valid) return validation

        val normalized = validation.normalizedProfile
        synchronized(lock) {
            val updated = _servers.value.toMutableList()
            val idx = updated.indexOfFirst { it.id == normalized.id }
            if (idx < 0) {
                return OpenSubsonicConfigValidationResult.Invalid(
                    listOf("Server configuration with id '${normalized.id}' not found.")
                )
            }
            updated[idx] = normalized
            store.saveAll(updated)
            _servers.value = updated.toList()
        }
        return validation
    }

    fun setServerEnabled(serverId: String, enabled: Boolean): Boolean {
        synchronized(lock) {
            val updated = _servers.value.toMutableList()
            val idx = updated.indexOfFirst { it.id == serverId }
            if (idx < 0) return false
            updated[idx] = updated[idx].copy(enabled = enabled)
            store.saveAll(updated)
            _servers.value = updated.toList()
            return true
        }
    }

    fun removeServer(serverId: String): Boolean {
        synchronized(lock) {
            val updated = _servers.value.filterNot { it.id == serverId }
            if (updated.size == _servers.value.size) return false
            store.saveAll(updated)
            _servers.value = updated
            return true
        }
    }
}
