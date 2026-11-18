package com.laksh.forge.data.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.laksh.forge.ForgeApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Its own DataStore file so it can be excluded from backup on its own (res/xml/backup_rules.xml
 * and data_extraction_rules.xml): the Keystore key never leaves this device, so a restored
 * copy of the ciphertext would be undecryptable anyway, and the plaintext never existed on disk.
 */
private val Context.secretsDataStore: DataStore<Preferences> by preferencesDataStore(name = "forge_secrets")

/**
 * Encrypted storage for API keys and tokens (the AI key; WHOOP tokens and client secret).
 *
 * Each value is sealed with AES-256-GCM under a non-exportable Android Keystore key and the
 * ciphertext lives in the "forge_secrets" DataStore. The entry name is bound in as associated
 * data, so a ciphertext copied to another slot fails to decrypt instead of being misread.
 *
 * Decrypted values are cached in memory: the AI layer needs the key synchronously to describe
 * routes, and decrypting on every call would be wasted work.
 */
object SecretStore {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "forge_secret_store_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    /** Format byte in front of each blob, so the layout can change without guesswork later. */
    private const val FORMAT_V1: Byte = 1

    private val cache = ConcurrentHashMap<String, String>()
    private val keyLock = Any()

    @Volatile private var attached: Context? = null

    /** Optional: give the store a context early (the app's Application is used otherwise). */
    fun attach(context: Context) {
        if (attached == null) attached = context.applicationContext
    }

    private val context: Context
        get() = attached ?: ForgeApp.instance.applicationContext.also { attached = it }

    suspend fun put(key: String, value: String) {
        require(key.isNotBlank()) { "Secret name must not be blank" }
        withContext(Dispatchers.IO) {
            val sealed = encrypt(key, value)
            context.secretsDataStore.edit { it[stringPreferencesKey(key)] = sealed }
            cache[key] = value
        }
    }

    /** The stored value, or null when absent or no longer decryptable (Keystore key lost). */
    suspend fun get(key: String): String? {
        cache[key]?.let { return it }
        return withContext(Dispatchers.IO) {
            val prefs = context.secretsDataStore.data
                .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
                .first()
            val sealed = prefs[stringPreferencesKey(key)] ?: return@withContext null
            val plain = try {
                decrypt(key, sealed)
            } catch (e: Exception) {
                // A tag mismatch or malformed blob means the Keystore key that sealed it is gone
                // (new key generated) or the blob is corrupt: permanently unreadable, so clear it
                // rather than failing on every launch. Anything else may be transient; keep it.
                val permanent = e is javax.crypto.AEADBadTagException ||
                    e is IllegalArgumentException ||
                    (e is GeneralSecurityException && e.message?.contains("secret") == true)
                if (permanent) {
                    runCatching { context.secretsDataStore.edit { it.remove(stringPreferencesKey(key)) } }
                }
                return@withContext null
            }
            cache[key] = plain
            plain
        }
    }

    suspend fun remove(key: String) {
        withContext(Dispatchers.IO) {
            cache.remove(key)
            context.secretsDataStore.edit { it.remove(stringPreferencesKey(key)) }
        }
    }

    /** Memory-only read for synchronous callers; null until [get] or [put] has run this process. */
    fun peek(key: String): String? = cache[key]

    // ---------------------------------------------------------------- crypto

    private fun secretKey(): SecretKey = synchronized(keyLock) {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // The Keystore picks a fresh random IV for every encryption.
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        generator.generateKey()
    }

    private fun encrypt(name: String, value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val out = ByteArray(2 + iv.size + ct.size)
        out[0] = FORMAT_V1
        out[1] = iv.size.toByte()
        System.arraycopy(iv, 0, out, 2, iv.size)
        System.arraycopy(ct, 0, out, 2 + iv.size, ct.size)
        return Base64.getEncoder().encodeToString(out)
    }

    @Throws(GeneralSecurityException::class)
    private fun decrypt(name: String, sealed: String): String {
        val bytes = Base64.getDecoder().decode(sealed)
        if (bytes.size < 3 || bytes[0] != FORMAT_V1) throw GeneralSecurityException("Unknown secret format")
        val ivLen = bytes[1].toInt()
        if (ivLen <= 0 || 2 + ivLen >= bytes.size) throw GeneralSecurityException("Corrupt secret")
        val iv = bytes.copyOfRange(2, 2 + ivLen)
        val ct = bytes.copyOfRange(2 + ivLen, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }
}
