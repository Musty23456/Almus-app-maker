package com.almus.appcreatorpro.service.artifacts

import com.almus.appcreatorpro.domain.build.BuildConfiguration

/** Reads what a build asked for from its build settings. Holds no secret: the signing key is only an id there. */
object BuildIntents {
    /** Null when the build has no recorded settings (made before build settings existed): the file alone then decides. */
    fun from(config: BuildConfiguration?): BuildIntent? {
        if (config == null) return null
        val task = config.gradleTask.toString()
        val kind = when {
            task.endsWith("Release") -> BuildKind.RELEASE
            task.endsWith("Debug") -> BuildKind.DEBUG
            else -> BuildKind.UNKNOWN
        }
        val type = when {
            task.startsWith("bundle") -> ArtifactType.AAB
            task.startsWith("assemble") -> ArtifactType.APK
            else -> null
        }
        return BuildIntent(
            variantLabel = config.shortLabel(),
            kind = kind,
            type = type,
            packageName = config.applicationId,
            versionName = config.versionName,
            versionCode = config.versionCode?.toLong(),
        )
    }
}
