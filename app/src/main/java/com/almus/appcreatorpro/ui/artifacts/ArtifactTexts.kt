package com.almus.appcreatorpro.ui.artifacts

import com.almus.appcreatorpro.service.artifacts.ArtifactEntry
import com.almus.appcreatorpro.service.artifacts.ArtifactReport
import com.almus.appcreatorpro.service.artifacts.BuildKind
import com.almus.appcreatorpro.service.artifacts.Checksums
import com.almus.appcreatorpro.service.artifacts.IntegrityState

/** The words the artifact screen shows, kept free of Compose so they can be unit-tested. */
object ArtifactTexts {

    /** "DEBUG APK", "RELEASE AAB" or just "APK" when debug/release is not known. Always uppercase so it stands out. */
    fun badge(entry: ArtifactEntry): String {
        val kind = entry.kindHint
        return if (kind == BuildKind.UNKNOWN) entry.type.label else "${kind.label.uppercase()} ${entry.type.label}"
    }

    fun packageLine(r: ArtifactReport): String =
        "Package: ${r.packageName ?: "not available"}" + if (r.packageName != null && !r.detailsFromFile) " (from the build settings)" else ""

    fun versionLine(r: ArtifactReport): String =
        "Version: ${r.versionName ?: "not available"}" + (r.versionCode?.let { " (code $it)" } ?: "")

    fun kindLine(r: ArtifactReport): String = "Build: ${r.buildVariantLabel} - ${r.kind.label} (from ${r.kindSource})"

    fun sdkLine(r: ArtifactReport): String? = r.minSdk?.let { "Needs Android API $it or newer" }

    fun signingLine(r: ArtifactReport): String {
        val s = r.signing
        val key = s.matchedKeyLabel?.let { " (\"$it\")" }.orEmpty()
        val check = if (s.status.name == "UNSIGNED") "" else if (s.verified) " - verified by ${s.verifiedBy}" else " - not verified"
        return "Signing: ${s.status.label}$key$check"
    }

    fun schemesLine(r: ArtifactReport): String? =
        r.signing.schemes.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.label }?.let { "Signature schemes: $it" }

    fun integrityLine(r: ArtifactReport): String {
        val i = r.integrity
        val extra = if (i.state == IntegrityState.VERIFIED || i.state == IntegrityState.RECORDED) " (SHA-256 ${Checksums.short(i.sha256)})" else ""
        return "Integrity: ${i.state.label}$extra"
    }

    /** What the delete confirmation says. A release build gets a stronger warning: it cannot simply be rebuilt identically. */
    fun deleteWarning(entry: ArtifactEntry): String {
        val base = "${entry.fileName} will be removed from this phone."
        return if (entry.kindHint == BuildKind.RELEASE) {
            "$base\n\nThis is a RELEASE file. A new build can differ from it (for example its checksum and build time), so if it is the copy you published or sent to someone, keep it. " +
                "It can be downloaded again from GitHub only while the artifact has not expired."
        } else {
            "$base You can download it again from GitHub while the artifact has not expired."
        }
    }
}
