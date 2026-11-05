package com.almus.appcreatorpro.service.signing

import com.almus.appcreatorpro.domain.build.BuildConfiguration
import com.almus.appcreatorpro.domain.build.SigningException
import com.almus.appcreatorpro.domain.build.SigningProfile
import com.almus.appcreatorpro.domain.build.SigningReadiness
import com.almus.appcreatorpro.domain.build.SigningSecrets
import com.almus.appcreatorpro.domain.build.SigningSource
import com.almus.appcreatorpro.domain.build.SigningValidator
import java.security.SecureRandom
import java.util.UUID

/**
 * The release signing manager: creates and imports keys, keeps them in the encrypted [SigningStore], hands the user the
 * four values to paste into GitHub Actions Secrets, and tells the build whether release signing is ready.
 * It never writes a password or key anywhere except the encrypted store, and never puts one into a message.
 */
class SigningManager(
    private val store: SigningStore,
    private val generator: KeystoreGenerator = KeystoreGenerator(),
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString().replace("-", "").take(12) },
) : SigningReadiness {

    fun profiles(): List<SigningProfile> = store.list()

    fun profile(id: String): SigningProfile? = store.get(id)

    /** Creates a new RSA key. [password] protects both the keystore and the key (PKCS12 uses one password). */
    fun generate(label: String, alias: String, password: String, owner: CertificateOwner): SigningProfile {
        SigningValidator.validateLabel(label)?.let { throw invalid(it) }
        SigningValidator.validateAlias(alias)?.let { throw invalid(it) }
        SigningValidator.validateNewPassword(password)?.let { throw invalid(it) }
        if (owner.commonName.isBlank()) throw invalid("Enter the name of the person or company the key belongs to.")
        SigningValidator.validateNamePart(owner.commonName, "Name")?.let { throw invalid(it) }
        SigningValidator.validateNamePart(owner.organization, "Organization")?.let { throw invalid(it) }
        if (owner.country.isNotBlank() && !Regex("^[A-Za-z]{2}$").matches(owner.country.trim())) {
            throw invalid("Country is a two-letter code such as NG or US.")
        }
        val cleanAlias = alias.lowercase()
        val keystore = generator.generate(cleanAlias, password, owner)
        val profile = SigningProfile(
            id = newId(),
            label = label.trim(),
            alias = cleanAlias,
            source = SigningSource.GENERATED,
            createdAtMillis = now(),
            sha256 = keystore.sha256,
            subject = keystore.subject,
            verifiedOnDevice = true,
        )
        store.save(profile, keystore.bytes, password, password, cleanAlias)
        return profile
    }

    /**
     * Imports an existing keystore file. A PKCS12 file is opened and verified here. A JKS file cannot be opened by
     * Android, so it is kept as it is and verified by the sign job in the cloud (the alias and passwords must be typed).
     */
    fun import(label: String, bytes: ByteArray, alias: String, storePassword: String, keyPassword: String): SigningProfile {
        SigningValidator.validateLabel(label)?.let { throw invalid(it) }
        SigningValidator.validateExistingPassword(storePassword)?.let { throw invalid(it) }
        val effectiveKeyPassword = keyPassword.ifEmpty { storePassword }
        SigningValidator.validateExistingPassword(effectiveKeyPassword)?.let { throw invalid(it) }
        if (bytes.isEmpty()) throw invalid("The file is empty.")
        if (bytes.size > SigningValidator.MAX_KEYSTORE_BYTES) throw invalid("This file is too large to be a keystore.")

        val profile = if (isJks(bytes)) {
            SigningValidator.validateAlias(alias)?.let { throw invalid("This is a JKS keystore, which this phone cannot open. $it") }
            SigningProfile(
                id = newId(), label = label.trim(), alias = alias, source = SigningSource.IMPORTED,
                createdAtMillis = now(), verifiedOnDevice = false,
            )
        } else {
            val inspection = generator.inspect(bytes, storePassword, alias.ifBlank { null }, effectiveKeyPassword)
            SigningProfile(
                id = newId(), label = label.trim(), alias = inspection.alias, source = SigningSource.IMPORTED,
                createdAtMillis = now(), sha256 = inspection.sha256, subject = inspection.subject, verifiedOnDevice = true,
            )
        }
        store.save(profile, bytes, storePassword, effectiveKeyPassword, profile.alias)
        return profile
    }

    /** The four GitHub secrets for [id]. The values are the user's to copy; callers must not log or persist them. */
    fun secrets(id: String): SigningSecrets = store.secrets(id)

    /** The keystore file, for a backup the user saves themselves. */
    fun keystoreBytes(id: String): ByteArray =
        java.util.Base64.getDecoder().decode(store.secrets(id).keystoreBase64)

    /** The user says the four secrets now exist in [repoFullName]. */
    fun confirmSecrets(id: String, repoFullName: String) {
        val profile = store.get(id) ?: throw SigningException(SigningException.Kind.NOT_FOUND, "This key no longer exists.")
        if (repoFullName in profile.secretsConfirmedFor) return
        store.update(profile.copy(secretsConfirmedFor = profile.secretsConfirmedFor + repoFullName))
    }

    fun unconfirmSecrets(id: String, repoFullName: String) {
        val profile = store.get(id) ?: return
        store.update(profile.copy(secretsConfirmedFor = profile.secretsConfirmedFor - repoFullName))
    }

    fun delete(id: String) = store.delete(id)

    override fun blockers(config: BuildConfiguration, repoFullName: String): List<String> {
        if (!config.needsReleaseSigning) return emptyList()
        val id = config.signingProfileId
            ?: return listOf("Choose a signing key for release builds (Build configuration > Release signing).")
        val profile = store.get(id)
            ?: return listOf("The signing key chosen for this project no longer exists. Choose or create another one.")
        if (repoFullName !in profile.secretsConfirmedFor) {
            return listOf(
                "Add the four signing secrets to $repoFullName on GitHub, then confirm it in Release signing " +
                    "(\"I added the secrets\"). The app cannot read GitHub secrets, so it relies on your confirmation; " +
                    "the build checks them again.",
            )
        }
        return emptyList()
    }

    private fun isJks(bytes: ByteArray) =
        bytes.size >= 4 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xED.toByte() && bytes[2] == 0xFE.toByte() && bytes[3] == 0xED.toByte()

    private fun invalid(message: String) = SigningException(SigningException.Kind.INVALID_INPUT, message)

    companion object {
        private const val PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"

        /** A random 24-character password without look-alike characters (easy to read and type on a phone). */
        fun generatePassword(random: SecureRandom = SecureRandom(), length: Int = 24): String =
            buildString { repeat(length) { append(PASSWORD_ALPHABET[random.nextInt(PASSWORD_ALPHABET.length)]) } }
    }
}
