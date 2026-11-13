package com.almus.appcreatorpro.service.artifacts

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/*
 * Phase 33: the vocabulary of the artifact manager.
 *
 * Everything here is plain Kotlin (no Android classes), so every rule can be unit-tested on a normal JVM.
 * NOTHING in this package ever reads a keystore, a keystore password or a private key: the only signing data
 * used is the PUBLIC certificate found inside an APK/AAB (its SHA-256 fingerprint and subject).
 */

enum class ArtifactType(val label: String, val longLabel: String) {
    APK("APK", "Android package (APK)"),
    AAB("AAB", "Android App Bundle (AAB)"),
    ;

    companion object {
        fun of(fileName: String): ArtifactType = if (fileName.endsWith(".aab", ignoreCase = true)) AAB else APK
    }
}

/** Debug or release. [UNKNOWN] is shown as such: the app never guesses silently. */
enum class BuildKind(val label: String) {
    DEBUG("Debug"),
    RELEASE("Release"),
    UNKNOWN("Unknown"),
    ;

    companion object {
        /** Weakest evidence there is: the file name. Used only when nothing else is known. */
        fun fromFileName(name: String): BuildKind {
            val n = name.lowercase()
            return when {
                n.contains("debug") -> DEBUG
                n.contains("release") -> RELEASE
                else -> UNKNOWN
            }
        }
    }
}

enum class SignatureScheme(val label: String) {
    V1("v1 (JAR)"),
    V2("v2"),
    V3("v3"),
    V3_1("v3.1"),
}

/**
 * The public certificate of a signer. [sha256] is written like keytool does it (AA:BB:...), the same format the
 * signing screen shows for the user's own keys, so the two can be compared.
 */
data class SignerInfo(
    val sha256: String,
    val subject: String,
    val notAfterMillis: Long?,
    val isAndroidDebugCertificate: Boolean,
) {
    companion object {
        fun fromCertificate(cert: X509Certificate): SignerInfo {
            val subject = cert.subjectX500Principal.name
            return SignerInfo(
                sha256 = fingerprint(cert.encoded),
                subject = subject,
                notAfterMillis = cert.notAfter?.time,
                isAndroidDebugCertificate = isDebugSubject(subject),
            )
        }

        /** Null when [der] is not a certificate. Never throws. */
        fun fromDer(der: ByteArray): SignerInfo? = try {
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as? X509Certificate
            cert?.let(::fromCertificate)
        } catch (e: Exception) {
            null
        }

        fun fingerprint(der: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(der).joinToString(":") { "%02X".format(it) }

        /** The standard Android debug key is "CN=Android Debug, O=Android, C=US". */
        fun isDebugSubject(subject: String): Boolean {
            val s = subject.replace(" ", "").lowercase()
            return s.contains("cn=androiddebug")
        }

        /** Makes "aa:bb", "AABB" and "AA:BB" comparable. */
        fun normalize(fingerprint: String): String = fingerprint.filter { it.isLetterOrDigit() }.uppercase()
    }
}

enum class SigningStatus(val label: String) {
    /** No signature of any kind was found in the file. */
    UNSIGNED("Not signed"),

    /** Signed with the standard Android debug key: fine for testing, never for publishing. */
    DEBUG_KEY("Signed with the Android debug key"),

    /** The certificate matches a key saved in this app's Release signing screen. */
    OWN_KEY("Signed with your own key"),

    /** Signed, but with a key this app does not know. */
    OTHER_KEY("Signed with another key"),

    /** A signature is present but its certificate could not be read. */
    UNREADABLE("Signature present, certificate unreadable"),
}

/**
 * What is known about the signature of one file.
 * [verified] is true ONLY when Android (APK) or the JAR verifier (AAB) actually checked the signature against the
 * file contents. A signature that was merely found is not "verified".
 */
data class SigningReport(
    val status: SigningStatus,
    val schemes: List<SignatureScheme>,
    val signers: List<SignerInfo>,
    val verified: Boolean,
    val verifiedBy: String?,
    /** Label of the saved key whose public fingerprint matched, when [status] is OWN_KEY. */
    val matchedKeyLabel: String?,
    val problems: List<String>,
)

enum class IntegrityState(val label: String) {
    /** The file is whole and its SHA-256 equals the one recorded when it was saved. */
    VERIFIED("Verified"),

    /** The file is whole; no earlier checksum existed, so one was recorded now. It proves nothing about the past. */
    RECORDED("Checksum recorded now"),

    /** The SHA-256 differs from the recorded one: the file changed after it was saved. */
    MODIFIED("Changed since it was saved"),

    /** The archive is damaged (bad entry, missing mandatory file...). */
    CORRUPT("Damaged"),

    /** The file could not be read. */
    UNREADABLE("Could not be read"),
}

data class IntegrityReport(
    val state: IntegrityState,
    val sha256: String?,
    val recordedSha256: String?,
    /** "download" (recorded when the app saved it) or "first check" (recorded when the manager first saw it). */
    val recordedSource: String?,
    val recordedAtMillis: Long?,
    val problems: List<String>,
) {
    /** True when the file is known to be unusable or altered: sharing and exporting are blocked. */
    val blocksSharing: Boolean
        get() = state == IntegrityState.MODIFIED || state == IntegrityState.CORRUPT || state == IntegrityState.UNREADABLE
}

enum class InstallStatus {
    /** Android itself parsed the APK and verified its signature, and nothing else stands in the way. */
    VERIFIED_INSTALLABLE,

    /** A check found a reason it cannot be installed. */
    NOT_INSTALLABLE,

    /** The checks needed to say anything were not possible (for example, no Android parser available). */
    NOT_CHECKED,

    /** An AAB: it is for Google Play, never installed directly. */
    BUNDLE_ONLY,
}

data class InstallVerdict(val status: InstallStatus, val headline: String, val detail: String)

/** What the user asked for when the build was started (taken from the build settings, never from a secret). */
data class BuildIntent(
    val variantLabel: String,
    val kind: BuildKind,
    val type: ArtifactType?,
    val packageName: String?,
    val versionName: String?,
    val versionCode: Long?,
)

/** What is known about the build that made a file. [intent] is null for builds made before build settings existed. */
data class BuildContext(val projectName: String, val intent: BuildIntent?)

/** The full, honest picture of one saved APK/AAB. */
data class ArtifactReport(
    val fileName: String,
    val type: ArtifactType,
    val sizeBytes: Long,
    val packageName: String?,
    val versionName: String?,
    val versionCode: Long?,
    val minSdk: Int?,
    val targetSdk: Int?,
    /** True when package/version were read from the file itself; false when they come from the build settings. */
    val detailsFromFile: Boolean,
    val buildVariantLabel: String,
    val kind: BuildKind,
    /** Where [kind] comes from: "the file", "the build settings" or "the file name". */
    val kindSource: String,
    val signing: SigningReport,
    val integrity: IntegrityReport,
    val install: InstallVerdict,
    val warnings: List<String>,
    val checkedAtMillis: Long,
) {
    /** Plain text for "Copy details". Contains only public information (no key, no password, no secret). */
    fun toPlainText(): String = buildString {
        appendLine("File: $fileName")
        appendLine("Type: ${type.longLabel}")
        appendLine("Build: $buildVariantLabel (${kind.label}, from $kindSource)")
        appendLine("Size: $sizeBytes bytes")
        appendLine("Package: ${packageName ?: "not available"}")
        appendLine("Version: ${versionName ?: "not available"}" + (versionCode?.let { " ($it)" } ?: ""))
        appendLine("Signing: ${signing.status.label}" + if (signing.verified) " (verified by ${signing.verifiedBy})" else " (not verified)")
        signing.signers.forEach { appendLine("Certificate SHA-256: ${it.sha256}") }
        appendLine("File SHA-256: ${integrity.sha256 ?: "not available"}")
        appendLine("Integrity: ${integrity.state.label}")
        appendLine("Install: ${install.headline}")
        warnings.forEach { appendLine("Warning: $it") }
    }
}
