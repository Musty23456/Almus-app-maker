package com.almus.appcreatorpro.service.artifacts

import java.io.File

/** What Android itself says about an APK file. */
sealed interface ApkPackageResult {
    /**
     * Android could read the package. [signatureVerified] is true only when Android checked the signature against the
     * contents; [signers] are then the certificates Android verified.
     */
    data class Parsed(
        val packageName: String,
        val versionName: String?,
        val versionCode: Long,
        val debuggable: Boolean,
        val minSdk: Int?,
        val targetSdk: Int?,
        val signatureVerified: Boolean,
        val signers: List<SignerInfo>,
    ) : ApkPackageResult

    /** Android could not read the file as an APK. */
    data object Rejected : ApkPackageResult

    /** No Android parser is available (for example in a unit test), so nothing can be said. */
    data object Unavailable : ApkPackageResult
}

/** Seam for Android's own package parser. The real one is [AndroidApkPackageReader]. Never throws. */
fun interface ApkPackageReader {
    fun read(file: File): ApkPackageResult
}

/**
 * The keys the user saved in Release signing, seen from the outside: only the PUBLIC certificate fingerprint and the
 * label. Implementations must never expose a keystore, a password or a private key.
 */
fun interface KnownSigningKeys {
    /** The label of the saved key whose certificate has this SHA-256 fingerprint, or null. */
    fun labelFor(sha256Fingerprint: String): String?

    companion object {
        val NONE = KnownSigningKeys { null }
    }
}
