package com.almus.appcreatorpro.service.artifacts

import java.io.File

/**
 * Turns "what is inside the file" + "what Android says" + "what the user asked for" into one honest [ArtifactReport].
 *
 * Rules that matter:
 *  - An APK is called installable ONLY when Android itself parsed it and verified its signature, the archive is whole,
 *    and its checksum matches the recorded one. Anything less says so in words ("not checked", "not signed"...).
 *  - An AAB is never called installable: it is for Google Play.
 *  - What the FILE says (debuggable flag, debug certificate) outranks what the build settings asked for; a disagreement
 *    is reported as a warning.
 *  - Only public certificate data is used. No keystore, password or private key is ever read.
 */
class ArtifactAssessor(
    private val analyzer: ArtifactAnalyzer,
    private val reader: ApkPackageReader,
    private val registry: ArtifactRegistry,
    private val keys: KnownSigningKeys = KnownSigningKeys.NONE,
    /** Android API level of this phone, to compare with the APK's minimum. Null = unknown. */
    private val deviceSdk: Int? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {

    fun assess(buildId: String, file: File, context: BuildContext?): ArtifactReport {
        val name = file.name
        val type = ArtifactType.of(name)
        val intent = context?.intent
        val analysis = analyzer.analyze(file, type)
        val android: ApkPackageResult =
            if (type == ArtifactType.APK && analysis.structureOk) reader.read(file) else ApkPackageResult.Unavailable
        val parsed = android as? ApkPackageResult.Parsed
        val bundle = analysis.bundleManifest

        // ---- package and version: the file first, the build settings only when the file cannot tell
        val filePackage = parsed?.packageName ?: bundle?.packageName
        val fromFile = filePackage != null
        val packageName = filePackage ?: intent?.packageName
        val versionName: String?
        val versionCode: Long?
        if (fromFile) {
            versionName = parsed?.versionName ?: bundle?.versionName
            versionCode = parsed?.versionCode ?: bundle?.versionCode
        } else {
            versionName = intent?.versionName
            versionCode = intent?.versionCode
        }
        val minSdk = parsed?.minSdk ?: bundle?.minSdk
        val targetSdk = parsed?.targetSdk ?: bundle?.targetSdk

        // ---- signing
        val signing = signingReport(type, analysis, parsed)

        // ---- debug / release
        val debuggable: Boolean? = parsed?.debuggable ?: bundle?.let { it.debuggable ?: false }
        val fromFileKind = when {
            debuggable == true -> BuildKind.DEBUG
            debuggable == false -> BuildKind.RELEASE
            signing.signers.any { it.isAndroidDebugCertificate } -> BuildKind.DEBUG
            else -> BuildKind.UNKNOWN
        }
        val intentKind = intent?.kind ?: BuildKind.UNKNOWN
        val nameKind = BuildKind.fromFileName(name)
        val kind: BuildKind
        val kindSource: String
        when {
            fromFileKind != BuildKind.UNKNOWN -> { kind = fromFileKind; kindSource = "the file" }
            intentKind != BuildKind.UNKNOWN -> { kind = intentKind; kindSource = "the build settings" }
            nameKind != BuildKind.UNKNOWN -> { kind = nameKind; kindSource = "the file name" }
            else -> { kind = BuildKind.UNKNOWN; kindSource = "nothing (it could not be told)" }
        }

        // ---- integrity
        val integrity = integrityReport(buildId, name, analysis)

        // ---- installability
        val install = installVerdict(type, analysis, integrity, android, parsed, signing)

        // ---- warnings
        val warnings = ArrayList<String>()
        if (intentKind != BuildKind.UNKNOWN && fromFileKind != BuildKind.UNKNOWN && intentKind != fromFileKind) {
            warnings += "The build settings asked for a ${intentKind.label.lowercase()} build, but the file itself is a " +
                "${fromFileKind.label.lowercase()} build. Trust the file."
        }
        val releaseLike = kind == BuildKind.RELEASE || intentKind == BuildKind.RELEASE
        if (releaseLike && signing.status == SigningStatus.DEBUG_KEY) {
            warnings += "This release build is signed with the Android debug key. Never publish it or hand it to users as final."
        }
        if (releaseLike && signing.status == SigningStatus.UNSIGNED) {
            warnings += "This release build is not signed."
        }
        if (kind == BuildKind.DEBUG) {
            warnings += if (type == ArtifactType.AAB) {
                "Debug bundle: for testing only. Google Play does not accept debuggable bundles."
            } else {
                "Debug build: for testing only. Do not publish it."
            }
        }
        if ((signing.status == SigningStatus.OWN_KEY || signing.status == SigningStatus.OTHER_KEY) && !signing.verified) {
            warnings += "A signature was found but could not be verified on this phone."
        }
        if (signing.signers.any { (it.notAfterMillis ?: Long.MAX_VALUE) < now() }) {
            warnings += "The signing certificate has expired. Google Play will not accept an update signed with it."
        }
        if (fromFile && intent?.versionCode != null && versionCode != null && intent.versionCode != versionCode) {
            warnings += "The build settings say version code ${intent.versionCode}, but the file contains $versionCode."
        }
        if (fromFile && intent?.packageName != null && packageName != null && intent.packageName != packageName) {
            warnings += "The build settings say package ${intent.packageName}, but the file contains $packageName."
        }
        if (integrity.state == IntegrityState.MODIFIED) {
            warnings += "This file is different from the one that was saved. Do not share or install it; download it again."
        }

        return ArtifactReport(
            fileName = name,
            type = type,
            sizeBytes = analysis.sizeBytes,
            packageName = packageName,
            versionName = versionName,
            versionCode = versionCode,
            minSdk = minSdk,
            targetSdk = targetSdk,
            detailsFromFile = fromFile,
            buildVariantLabel = intent?.variantLabel ?: defaultVariantLabel(type, kind),
            kind = kind,
            kindSource = kindSource,
            signing = signing,
            integrity = integrity,
            install = install,
            warnings = warnings,
            checkedAtMillis = now(),
        )
    }

    private fun defaultVariantLabel(type: ArtifactType, kind: BuildKind): String =
        if (kind == BuildKind.UNKNOWN) type.label else "${kind.label} ${type.label}"

    private fun signingReport(type: ArtifactType, analysis: FileAnalysis, parsed: ApkPackageResult.Parsed?): SigningReport {
        val problems = ArrayList<String>(analysis.signatureProblems)
        val androidVerified = parsed != null && parsed.signatureVerified
        val jarVerified = type == ArtifactType.AAB && analysis.jarSignatureVerified == true
        val verified = androidVerified || jarVerified
        val verifiedBy = when {
            androidVerified -> "Android"
            jarVerified -> "the bundle signature check"
            else -> null
        }
        val signers: List<SignerInfo> = if (androidVerified && parsed.signers.isNotEmpty()) parsed.signers else analysis.signers
        val matched = signers.firstNotNullOfOrNull { keys.labelFor(it.sha256) }

        val status = when {
            !analysis.hasSignature && !verified -> SigningStatus.UNSIGNED
            signers.isEmpty() -> SigningStatus.UNREADABLE
            signers.any { it.isAndroidDebugCertificate } -> SigningStatus.DEBUG_KEY
            matched != null -> SigningStatus.OWN_KEY
            else -> SigningStatus.OTHER_KEY
        }
        if (status != SigningStatus.UNSIGNED && !verified) {
            if (type == ArtifactType.APK && parsed != null) problems += "Android could not verify this APK's signature."
            if (type == ArtifactType.APK && parsed == null) problems += "The signature was not verified (no Android check was possible)."
        }
        return SigningReport(
            status = status,
            schemes = analysis.schemes,
            signers = signers,
            verified = verified,
            verifiedBy = verifiedBy,
            matchedKeyLabel = if (status == SigningStatus.OWN_KEY) matched else null,
            problems = problems,
        )
    }

    private fun integrityReport(buildId: String, name: String, analysis: FileAnalysis): IntegrityReport {
        val sha = analysis.sha256
        val record = registry.get(buildId, name)
        if (sha == null) {
            return IntegrityReport(IntegrityState.UNREADABLE, null, record?.sha256, record?.source, record?.recordedAtMillis, analysis.structureProblems)
        }
        if (record != null && !record.sha256.equals(sha, ignoreCase = true)) {
            val source = if (record.source == "download") "the checksum recorded when it was downloaded" else "the checksum recorded earlier"
            return IntegrityReport(
                IntegrityState.MODIFIED, sha, record.sha256, record.source, record.recordedAtMillis,
                listOf("The file's SHA-256 differs from $source.") + analysis.structureProblems,
            )
        }
        if (!analysis.structureOk) {
            return IntegrityReport(IntegrityState.CORRUPT, sha, record?.sha256, record?.source, record?.recordedAtMillis, analysis.structureProblems)
        }
        if (record == null) {
            val written = now()
            registry.put(buildId, name, ArtifactRecord(sha, analysis.sizeBytes, written, "first check"))
            return IntegrityReport(IntegrityState.RECORDED, sha, sha, "first check", written, emptyList())
        }
        return IntegrityReport(IntegrityState.VERIFIED, sha, record.sha256, record.source, record.recordedAtMillis, emptyList())
    }

    private fun installVerdict(
        type: ArtifactType,
        analysis: FileAnalysis,
        integrity: IntegrityReport,
        android: ApkPackageResult,
        parsed: ApkPackageResult.Parsed?,
        signing: SigningReport,
    ): InstallVerdict {
        if (type == ArtifactType.AAB) {
            return InstallVerdict(
                InstallStatus.BUNDLE_ONLY,
                "Not installable directly",
                "An AAB is made for Google Play. To try it on a phone it must first be turned into APKs (for example with bundletool).",
            )
        }
        if (integrity.state == IntegrityState.MODIFIED) {
            return InstallVerdict(InstallStatus.NOT_INSTALLABLE, "Do not install: the file changed after it was saved", integrity.problems.firstOrNull().orEmpty())
        }
        if (integrity.state == IntegrityState.CORRUPT || integrity.state == IntegrityState.UNREADABLE) {
            return InstallVerdict(InstallStatus.NOT_INSTALLABLE, "Cannot be installed: the file is damaged", integrity.problems.firstOrNull().orEmpty())
        }
        if (signing.status == SigningStatus.UNSIGNED) {
            return InstallVerdict(InstallStatus.NOT_INSTALLABLE, "Cannot be installed: it is not signed", "Android refuses APKs without a signature. Build it again with signing.")
        }
        if (android is ApkPackageResult.Rejected) {
            return InstallVerdict(InstallStatus.NOT_INSTALLABLE, "Cannot be installed: Android rejected this file", "Android could not read it as an APK.")
        }
        if (parsed == null) {
            return InstallVerdict(InstallStatus.NOT_CHECKED, "Not checked yet", "Android's own check was not possible, so nothing is claimed about installing it.")
        }
        if (!parsed.signatureVerified) {
            return InstallVerdict(InstallStatus.NOT_INSTALLABLE, "Cannot be installed: the signature could not be verified", "Android could not match the signature to the file.")
        }
        val min = parsed.minSdk
        if (deviceSdk != null && min != null && min > deviceSdk) {
            return InstallVerdict(InstallStatus.NOT_INSTALLABLE, "Cannot be installed on this phone", "It needs Android API $min or newer; this phone has API $deviceSdk.")
        }
        return InstallVerdict(
            InstallStatus.VERIFIED_INSTALLABLE,
            "Checked: Android can read this APK and its signature is valid",
            "Android can still refuse the install, for example if the same app is already installed with a different signature or a newer version.",
        )
    }
}
