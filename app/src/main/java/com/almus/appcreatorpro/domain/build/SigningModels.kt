package com.almus.appcreatorpro.domain.build

/** Where a signing key came from. */
enum class SigningSource(val id: String, val label: String) {
    GENERATED("generated", "Created on this phone"),
    IMPORTED("imported", "Imported keystore"),
    ;

    companion object {
        fun fromId(id: String?): SigningSource? = entries.firstOrNull { it.id == id }
    }
}

/** The non-secret description of a signing key. The key and passwords live in encrypted storage, never here. */
data class SigningProfile(
    val id: String,
    val label: String,
    val alias: String,
    val source: SigningSource,
    val createdAtMillis: Long,
    val sha256: String? = null,
    val subject: String? = null,
    /** False for a JKS file, which this phone cannot open; the cloud sign job checks it instead. */
    val verifiedOnDevice: Boolean = true,
    /** Repositories where the user said the four GitHub secrets exist. */
    val secretsConfirmedFor: List<String> = emptyList(),
)

/** The names of the four GitHub Actions secrets the sign job reads. */
object SigningSecretNames {
    const val KEYSTORE_BASE64 = "ALMUS_SIGN_KEYSTORE_BASE64"
    const val STORE_PASSWORD = "ALMUS_SIGN_STORE_PASSWORD"
    const val KEY_ALIAS = "ALMUS_SIGN_KEY_ALIAS"
    const val KEY_PASSWORD = "ALMUS_SIGN_KEY_PASSWORD"
    val ALL: List<String> = listOf(KEYSTORE_BASE64, STORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD)
}

/** The values of the four secrets. [toString] never shows a value, so an accidental log or message cannot leak one. */
class SigningSecrets(
    val keystoreBase64: String,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
) {
    /** Name and value pairs in the order of [SigningSecretNames.ALL]. */
    fun entries(): List<Pair<String, String>> = listOf(
        SigningSecretNames.KEYSTORE_BASE64 to keystoreBase64,
        SigningSecretNames.STORE_PASSWORD to storePassword,
        SigningSecretNames.KEY_ALIAS to keyAlias,
        SigningSecretNames.KEY_PASSWORD to keyPassword,
    )

    override fun toString(): String = "SigningSecrets(***)"
}

/** A signing problem with a message that is safe to show (it never contains a password). */
class SigningException(
    val kind: Kind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Kind { INVALID_INPUT, WRONG_PASSWORD, ALIAS_NOT_FOUND, NOT_A_KEYSTORE, NOT_FOUND, STORAGE, UNKNOWN }
}

/** Tells a build whether release signing can go ahead. Implemented by the signing manager. */
interface SigningReadiness {
    /** Reasons why [config] cannot be built in [repoFullName] yet. Empty means ready. */
    fun blockers(config: BuildConfiguration, repoFullName: String): List<String>
}

object SigningValidator {
    const val MAX_KEYSTORE_BYTES = 64 * 1024

    private val aliasPattern = Regex("^[A-Za-z0-9_.-]{1,64}$")
    private val forbiddenNameChars = Regex("[,=+<>#;\"\\\\]")

    fun validateLabel(label: String): String? {
        if (label.isBlank()) return "Give the key a name so you can recognise it."
        if (label.trim().length > 60) return "The name is too long (60 characters at most)."
        return null
    }

    fun validateAlias(alias: String): String? {
        if (alias.isBlank()) return "Enter the key alias."
        if (!aliasPattern.matches(alias)) return "The alias may contain letters, digits, dot, dash and underscore (no spaces)."
        return null
    }

    /** Rules for a password the app creates a key with. */
    fun validateNewPassword(password: String): String? {
        if (password.length < 12) return "Use at least 12 characters."
        if (password.length > 128) return "The password is too long."
        if (password.any { it.isWhitespace() }) return "The password must not contain spaces."
        if (password.toSet().size < 5) return "The password is too simple. Use more different characters."
        return null
    }

    /** An existing key's password is whatever it is; only obviously unusable values are refused. */
    fun validateExistingPassword(password: String): String? {
        if (password.isEmpty()) return "Enter the keystore password."
        if (password.length > 256) return "The password is too long."
        return null
    }

    /** A part of the certificate name (organisation, person). Blank is allowed. */
    fun validateNamePart(value: String, fieldName: String): String? {
        if (value.isBlank()) return null
        if (value.trim().length > 64) return "$fieldName is too long (64 characters at most)."
        if (forbiddenNameChars.containsMatchIn(value)) return "$fieldName must not contain , = + < > # ; \" or \\."
        return null
    }
}
