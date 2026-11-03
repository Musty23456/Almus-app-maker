package com.almus.appcreatorpro.service.signing

import com.almus.appcreatorpro.data.storage.KeyValueStore
import com.almus.appcreatorpro.domain.build.SigningException
import com.almus.appcreatorpro.domain.build.SigningProfile
import com.almus.appcreatorpro.domain.build.SigningSecrets
import com.almus.appcreatorpro.domain.build.SigningSource
import com.almus.appcreatorpro.service.github.MiniJson
import com.almus.appcreatorpro.service.github.SecretStore
import com.almus.appcreatorpro.service.github.arr
import com.almus.appcreatorpro.service.github.bool
import com.almus.appcreatorpro.service.github.long
import com.almus.appcreatorpro.service.github.str

/**
 * Where signing keys live on the phone.
 *  - [meta] (plain preferences): the non-secret [SigningProfile] data only.
 *  - [secrets] (AES-256-GCM, key in the Android Keystore): keystore file, store password, key password, alias.
 * Nothing here is ever written to the project folder, so it can never be uploaded to GitHub or exported with a project.
 */
class SigningStore(
    private val meta: KeyValueStore,
    private val secrets: SecretStore,
) {
    private val lock = Any()

    fun list(): List<SigningProfile> = synchronized(lock) {
        ids().mapNotNull { id -> meta.getString(PROFILE + id)?.let { parse(id, it) } }.sortedByDescending { it.createdAtMillis }
    }

    fun get(id: String): SigningProfile? = synchronized(lock) {
        if (!validId(id)) null else meta.getString(PROFILE + id)?.let { parse(id, it) }
    }

    /** Saves a profile and its secrets together. If the secrets cannot be written, nothing is left behind. */
    fun save(profile: SigningProfile, keystoreBytes: ByteArray, storePassword: String, keyPassword: String, alias: String) {
        require(validId(profile.id)) { "Invalid key id" }
        synchronized(lock) {
            try {
                secrets.put(SECRET_KEYSTORE + profile.id, android64(keystoreBytes))
                secrets.put(SECRET_STORE_PW + profile.id, storePassword)
                secrets.put(SECRET_KEY_PW + profile.id, keyPassword)
                secrets.put(SECRET_ALIAS + profile.id, alias)
            } catch (e: Exception) {
                removeSecrets(profile.id)
                throw SigningException(SigningException.Kind.STORAGE, "The key could not be stored securely on this phone.", e)
            }
            meta.putString(PROFILE + profile.id, serialize(profile))
            if (profile.id !in ids()) meta.putString(INDEX, MiniJson.stringify(ids() + profile.id))
        }
    }

    /** Updates only the non-secret description (for example the confirmed repositories). */
    fun update(profile: SigningProfile) {
        synchronized(lock) {
            if (meta.getString(PROFILE + profile.id) == null) throw SigningException(SigningException.Kind.NOT_FOUND, "This key no longer exists.")
            meta.putString(PROFILE + profile.id, serialize(profile))
        }
    }

    fun secrets(id: String): SigningSecrets = synchronized(lock) {
        if (!validId(id)) throw SigningException(SigningException.Kind.NOT_FOUND, "This key no longer exists.")
        val keystore = secrets.get(SECRET_KEYSTORE + id)
        val storePw = secrets.get(SECRET_STORE_PW + id)
        val keyPw = secrets.get(SECRET_KEY_PW + id)
        val alias = secrets.get(SECRET_ALIAS + id)
        if (keystore == null || storePw == null || keyPw == null || alias == null) {
            throw SigningException(
                SigningException.Kind.NOT_FOUND,
                "The stored key can no longer be read (Android reset its secure storage). Create or import the key again.",
            )
        }
        SigningSecrets(keystore, storePw, alias, keyPw)
    }

    fun delete(id: String) {
        if (!validId(id)) return
        synchronized(lock) {
            removeSecrets(id)
            meta.remove(PROFILE + id)
            meta.putString(INDEX, MiniJson.stringify(ids() - id))
        }
    }

    private fun removeSecrets(id: String) {
        listOf(SECRET_KEYSTORE, SECRET_STORE_PW, SECRET_KEY_PW, SECRET_ALIAS).forEach { secrets.remove(it + id) }
    }

    private fun ids(): List<String> {
        val text = meta.getString(INDEX) ?: return emptyList()
        return try {
            (MiniJson.parse(text) as? List<*>).orEmpty().filterIsInstance<String>().filter { validId(it) }
        } catch (e: MiniJson.JsonException) {
            emptyList()
        }
    }

    private fun serialize(p: SigningProfile): String = MiniJson.stringify(
        linkedMapOf(
            "label" to p.label,
            "alias" to p.alias,
            "source" to p.source.id,
            "createdAtMillis" to p.createdAtMillis,
            "sha256" to p.sha256,
            "subject" to p.subject,
            "verifiedOnDevice" to p.verifiedOnDevice,
            "secretsConfirmedFor" to p.secretsConfirmedFor,
        ),
    )

    private fun parse(id: String, text: String): SigningProfile? = try {
        val o = MiniJson.parseObject(text)
        SigningProfile(
            id = id,
            label = o.str("label") ?: return null,
            alias = o.str("alias") ?: return null,
            source = SigningSource.fromId(o.str("source")) ?: SigningSource.IMPORTED,
            createdAtMillis = o.long("createdAtMillis") ?: 0L,
            sha256 = o.str("sha256"),
            subject = o.str("subject"),
            verifiedOnDevice = o.bool("verifiedOnDevice") ?: true,
            secretsConfirmedFor = o.arr("secretsConfirmedFor").orEmpty().filterIsInstance<String>(),
        )
    } catch (e: MiniJson.JsonException) {
        null
    }

    // java.util.Base64 needs API 26, which is this app's minSdk, and works the same in unit tests.
    private fun android64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

    private fun validId(id: String) = ID.matches(id)

    private companion object {
        val ID = Regex("^[a-f0-9]{8,32}$")
        const val INDEX = "signing_index"
        const val PROFILE = "signing_profile_"
        const val SECRET_KEYSTORE = "signing_keystore_"
        const val SECRET_STORE_PW = "signing_storepw_"
        const val SECRET_KEY_PW = "signing_keypw_"
        const val SECRET_ALIAS = "signing_alias_"
    }
}
