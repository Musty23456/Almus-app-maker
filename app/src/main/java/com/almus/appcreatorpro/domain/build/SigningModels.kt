package com.almus.appcreatorpro.domain.build

/** Where a signing key came from. */
enum class SigningSource(val id: String, val label: String) {
    GENERATED("generated", "Created in this app"),
    IMPORTED("imported", "Imported"),
    ;

    companion object {
        fun fromId(id: String?): SigningSource? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The non-secret description of a signing key. The keystore file and the passwords are never part of it; they live
 * only in the encrypted store.
 */
data class SigningProfile(
    val id: String,
    val label: String,
    val alias: String,
    val source: SigningSource,
    val createdAtMillis: Long,
    /** Certificate fingerprint, when the phone could open the keystore (null for an unverified JKS file). */
    val sha256: String? = null,
    val subject: String? = null,
    /** False for a JKS file, which Android cannot open: it is checked by the sign job in the cloud instead. */
    val verifiedOnDevice: Boolean = true,
    /** Repositories (owner/name) in which the user confirmed that the four GitHub secrets exist. */
    val secretsConfirmedFor: List<String> = emptyList(),
)

/** The names of the four GitHub Actions secrets that almus-cloud-build.yml reads (only in its sign job). */
object SigningSecretNames {
    const val KEYSTORE_BASE64 = "ALMUS_SIGN_KEYSTORE_BASE64"
    const val STORE_PASSWORD = "ALMUS_SIGN_STORE_PASSWORD"
    const val KEY_ALIAS = "ALMUS_SIGN_KEY_ALIAS"
    const val KEY_PASSWORD = "ALMUS_SIGN_KEY_PASSWORD"

    val ALL: List<String> = listOf(KEYSTORE_BASE64, STORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD)
}

/**
 * The four values the user pastes into GitHub Actions Secrets. [toString] never shows a value, so an accidental log
 * line or crash report cannot leak them.
 */
class SigningSecrets(
    val keystoreBase64: String,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
) {
    /** Secret name to value, in the order of [SigningSecretNames.ALL]. */
    fun entries(): List<Pair<String, String>> = listOf(
        SigningSecretNames.KEYSTORE_BASE64 to keystoreBase64,
        SigningSecretNames.STORE_PASSWORD to storePassword,
        SigningSecretNames.KEY_ALIAS to keyAlias,
        SigningSecretNames.KEY_PASSWORD to keyPassword,
    )

    override fun toString(): String = "SigningSecrets(hidden)"
}

/** A signing problem with a message that is safe to show (it never contains a password). */
class SigningException(
    val kind: Kind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Kind {
        INVALID_INPUT,
        WRONG_PASSWORD,
        ALIAS_NOT_FOUND,
        NOT_A_KEYSTORE,
        NOT_FOUND,
        STORAGE,
        UNKNOWN,
    }
}

/** Lets the build ask whether release signing is ready, without knowing how keys are stored. */
fun interface SigningReadiness {
    /** User-safe reasons why [config] cannot be built in [repoFullName] yet. Empty when it can. */
    fun blockers(config: BuildConfiguration, repoFullName: String): List<String>
}

/** Rules for what the user types when creating or importing a key. Each function returns a message or null. */
object SigningValidator {
    const val MAX_KEYSTORE_BYTES = 65_536
    const val MIN_NEW_PASSWORD = 12
    const val MAX_PASSWORD = 128

    private val ALIAS = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$")
    private val FORBIDDEN_IN_NAME = Regex("[,+=\"\\\\<>#;\\r\\n\\u0000]")

    fun validateLabel(label: String): String? {
        val text = label.trim()
        if (text.isEmpty()) return "Give the key a name so you can recognise it."
        if (text.length > 60) return "The name is too long (60 characters at most)."
        return null
    }

    fun validateAlias(alias: String): String? {
        if (alias.isEmpty()) return "Enter the key alias (a short name such as release)."
        if (!ALIAS.matches(alias)) return "The alias may contain letters, digits and . _ - only (64 characters at most)."
        return null
    }

    /** For a password the user (or the app) chooses now. Stricter than [validateExistingPassword]. */
    fun validateNewPassword(password: String): String? {
        if (password.length < MIN_NEW_PASSWORD) return "Use a password of at least $MIN_NEW_PASSWORD characters."
        if (password.length > MAX_PASSWORD) return "The password is too long."
        if (password.any { it.code < 0x21 || it.code > 0x7E }) {
            return "Use only letters, digits and symbols from the keyboard; no spaces."
        }
        if (password.toSet().size < 5) return "The password is too repetitive. Mix different characters."
        return null
    }

    /** For the password of a keystore that already exists: anything it was created with is accepted. */
    fun validateExistingPassword(password: String): String? {
        if (password.isEmpty()) return "Enter the keystore password."
        if (password.length > MAX_PASSWORD) return "The password is too long."
        if (password.any { it.isISOControl() }) return "The password contains a character that is not allowed."
        return null
    }

    /** A part of the certificate owner's name (the name itself, the organisation). Empty is fine. */
    fun validateNamePart(value: String, field: String): String? {
        if (value.isBlank()) return null
        if (value.trim().length > 64) return "$field is too long (64 characters at most)."
        if (FORBIDDEN_IN_NAME.containsMatchIn(value)) return "$field contains a character that is not allowed."
        return null
    }
}
