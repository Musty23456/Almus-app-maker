package com.almus.appcreatorpro.service.github

import com.almus.appcreatorpro.data.storage.KeyValueStore

/**
 * Encrypts secrets with an AES-256-GCM key that lives in the Android Keystore (hardware-backed where the phone
 * supports it; the key can never be exported, even by this app). Only the ciphertext is written to [storage].
 *
 * If the key is lost (e.g. the Keystore was reset) the ciphertext is unreadable: [get] then returns null and
 * the app asks the user to sign in again, instead of crashing.
 */
class KeystoreSecretStore(
    private val storage: KeyValueStore,
    private val keyAlias: String = "almus_github_token_key",
) : SecretStore {

    override fun get(key: String): String? {
        val stored = storage.getString(PREFIX + key) ?: return null
        return try {
            decrypt(stored)
        } catch (e: Exception) {
            storage.remove(PREFIX + key)
            null
        }
    }

    override fun put(key: String, value: String) {
        storage.putString(PREFIX + key, encrypt(value))
    }

    override fun remove(key: String) {
        storage.remove(PREFIX + key)
    }

    private fun secretKey(): javax.crypto.SecretKey {
        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? javax.crypto.SecretKey)?.let { return it }
        val generator = javax.crypto.KeyGenerator.getInstance(
            android.security.keystore.KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        )
        generator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                keyAlias,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = javax.crypto.Cipher.getInstance(TRANSFORMATION)
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return android.util.Base64.encodeToString(iv + encrypted, android.util.Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val bytes = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
        require(bytes.size > IV_BYTES) { "ciphertext too short" }
        val cipher = javax.crypto.Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            secretKey(),
            javax.crypto.spec.GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES),
        )
        return String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }

    private companion object {
        const val PREFIX = "secret_"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
