package com.almus.appcreatorpro.service.artifacts

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import com.almus.appcreatorpro.domain.build.BuildConfiguration
import java.io.File

/** What is known about the package inside an APK/AAB. [fromFile] is false when it comes from the build settings instead. */
data class ArtifactDetails(
    val packageName: String?,
    val versionName: String?,
    val versionCode: Long?,
    val fromFile: Boolean,
) {
    companion object {
        /** Values the user asked for in the build settings (the only source for an AAB, which Android cannot open). */
        fun fromConfiguration(config: BuildConfiguration?): ArtifactDetails = ArtifactDetails(
            packageName = config?.applicationId,
            versionName = config?.versionName,
            versionCode = config?.versionCode?.toLong(),
            fromFile = false,
        )
    }
}

fun interface ArtifactInspector {
    /** Never throws. [fallback] is the configuration of the build that made the file, if known. */
    fun inspect(file: File, isBundle: Boolean, fallback: BuildConfiguration?): ArtifactDetails
}

/** Reads package name and version of an APK with Android's own package parser. */
class AndroidArtifactInspector(private val context: Context) : ArtifactInspector {
    override fun inspect(file: File, isBundle: Boolean, fallback: BuildConfiguration?): ArtifactDetails {
        if (!isBundle) {
            try {
                val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
                if (info != null) {
                    return ArtifactDetails(
                        packageName = info.packageName,
                        versionName = info.versionName,
                        versionCode = PackageInfoCompat.getLongVersionCode(info),
                        fromFile = true,
                    )
                }
            } catch (e: Exception) {
                // Fall through to the build settings.
            }
        }
        return ArtifactDetails.fromConfiguration(fallback)
    }
}
