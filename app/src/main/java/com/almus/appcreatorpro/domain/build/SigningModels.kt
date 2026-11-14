package com.almus.appcreatorpro.domain.build

/** Where a saved key came from. */
enum class SigningSource(val id: String, val label: String) {
    GENERATED("generated", "Created in this app"),
    IMPORTED("imported", "Imported keystore"),
    ;

    companion object {
        fun fromId(id: String?): SigningSource? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The NON-secret description of a saved release key. The keystore and its passwords are kept separately in the
 * encrypted store and are never part of this class.
 */
data class SigningProfile(
    val id: String,
    val label: String,
    val alias: String,
    val source: SigningSource,
    val createdAtMillis: Long,
    /** SHA-256 fingerprint of the public certificate (AA:BB:...), when the phone could open the keystore. */
    val sha256: String? = null,
    val subject: String? = null,
    /** False for a JKS keystore, which this phone cannot open: the cloud sign job checks it instead. */
    val verifiedOnDevice: Boolean = true,
    /** Repositories ("owner/name") in which the user said the four GitHub secrets exist. */
    val secretsConfirmedFor: List<String> = emptyList(),
)

/** The names of the four GitHub Actions secrets the workflow's sign job reads. */
object SigningSecretNames {
    const val KEYSTORE_BASE64 = "ALMUS_SIGN_KEYSTORE_BASE64"
    const val STORE_PASSWORD = "ALMUS_SIGN_STORE_PASSWORD"
    const val KEY_ALIAS = "ALMUS_SIGN_KEY_ALIAS"
    const val KEY_PASSWORD = "ALMUS_SIGN_KEY_PASSWORD"

    val ALL: List<String> = listOf(KEYSTORE_BASE64, STORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD)
}

/**
 * The four secret values of one key. [toString] never shows a value, so an accidental log line or crash report
 * cannot leak them.
 */
class SigningSecrets(
    val keystoreBase64: String,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
) {
    /** (secret name, value) in the order of [SigningSecretNames.ALL]. */
    fun entries(): List<Pair<String, String>> = listOf(
        SigningSecretNames.KEYSTORE_BASE64 to keystoreBase64,
        SigningSecretNames.STORE_PASSWORD to storePassword,
        SigningSecretNames.KEY_ALIAS to keyAlias,
        SigningSecretNames.KEY_PASSWORD to keyPassword,
    )

    override fun toString(): String = "SigningSecrets(hidden)"
}

class SigningException(
    val kind: Kind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Kind { INVALID_INPUT, WRONG_PASSWORD, NOT_A_KEYSTORE, ALIAS_NOT_FOUND, NOT_FOUND, STORAGE, UNKNOWN }
}

/** Tells the build whether release signing is ready. A non-empty list blocks the build. */
fun interface SigningReadiness {
    fun blockers(config: BuildConfiguration, repoFullName: String): List<String>
}

object SigningValidator {
    const val MAX_KEYSTORE_BYTES = 64 * 1024
    const val MIN_PASSWORD_LENGTH = 12
    private val ALIAS = Regex("^[A-Za-z0-9._-]{1,40}$")
    private val NAME_PART = Regex("^[\\p{L}\\p{N} .,'&()_-]*$")

    fun validateLabel(label: String): String? = when {
        label.isBlank() -> "Give the key a name so you can recognise it."
        label.trim().length > 60 -> "The name is too long (60 characters at most)."
        else -> null
    }

    fun validateAlias(alias: String): String? = when {
        alias.isBlank() -> "Enter an alias, for example: release."
        !ALIAS.matches(alias) -> "The alias may use letters, digits and . _ - only (no spaces, up to 40 characters)."
        else -> null
    }

    /** For a NEW password: strong enough, and typeable in a GitHub secret and in the workflow without surprises. */
    fun validateNewPassword(password: String): String? = when {
        password.length < MIN_PASSWORD_LENGTH -> "Use at least $MIN_PASSWORD_LENGTH characters."
        password.length > 128 -> "The password is too long (128 characters at most)."
        password.any { it.isWhitespace() || it.isISOControl() } -> "The password must not contain spaces or control characters."
        password.toSet().size < 5 -> "The password is too repetitive. Use a mix of different characters."
        else -> null
    }

    /** For the password of a keystore that already exists: whatever it is, it only has to be present. */
    fun validateExistingPassword(password: String): String? = when {
        password.isEmpty() -> "Enter the password of this keystore."
        password.length > 256 -> "That password is too long."
        else -> null
    }

    fun validateNamePart(value: String, fieldName: String): String? = when {
        value.length > 64 -> "$fieldName is too long (64 characters at most)."
        !NAME_PART.matches(value) -> "$fieldName contains characters that are not allowed."
        else -> null
    }
}
