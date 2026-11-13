package com.almus.appcreatorpro.service.artifacts

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File

/**
 * Asks Android's own package parser about an APK. This is the strongest check available on the phone: when the
 * parser also reads the signing certificates, Android has verified the signature against the file contents.
 *
 * Two calls are made on purpose. The first one (no signature flags) works for an unsigned APK, so package and version
 * can still be shown. The second one (with signature flags) makes Android verify the signature; it fails for an
 * unsigned or tampered file.
 */
class AndroidApkPackageReader(private val context: Context) : ApkPackageReader {

    @Suppress("DEPRECATION")
    override fun read(file: File): ApkPackageResult {
        return try {
            val pm = context.packageManager
            val path = file.absolutePath
            val basic = pm.getPackageArchiveInfo(path, 0) ?: return ApkPackageResult.Rejected
            val app = basic.applicationInfo
            val signed = readSigners(pm, path)
            ApkPackageResult.Parsed(
                packageName = basic.packageName ?: return ApkPackageResult.Rejected,
                versionName = basic.versionName,
                versionCode = PackageInfoCompat.getLongVersionCode(basic),
                debuggable = app != null && (app.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
                minSdk = app?.minSdkVersion,
                targetSdk = app?.targetSdkVersion,
                signatureVerified = signed != null && signed.isNotEmpty(),
                signers = signed.orEmpty(),
            )
        } catch (e: Exception) {
            ApkPackageResult.Rejected
        }
    }

    /** The verified signing certificates, or null when Android could not verify the signature. */
    @Suppress("DEPRECATION")
    private fun readSigners(pm: PackageManager, path: String): List<SignerInfo>? {
        return try {
            if (Build.VERSION.SDK_INT >= 28) {
                val info = pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES) ?: return null
                val signatures = info.signingInfo?.apkContentsSigners ?: return null
                signatures.mapNotNull { SignerInfo.fromDer(it.toByteArray()) }
            } else {
                val info = pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES) ?: return null
                info.signatures?.mapNotNull { SignerInfo.fromDer(it.toByteArray()) }
            }
        } catch (e: Exception) {
            null
        }
    }
}
