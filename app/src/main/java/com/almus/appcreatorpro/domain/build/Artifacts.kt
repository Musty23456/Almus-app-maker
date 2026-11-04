package com.almus.appcreatorpro.domain.build

/** What a workflow artifact is, judged by its name. */
enum class ArtifactKind {
    APK,
    AAB,
    BUILD_LOG,

    /** The short-lived hand-over between the build job and the sign job. Never shown to the user. */
    INTERMEDIATE,
    OTHER,
}

/** The artifact names that almus-cloud-build.yml uploads, and how the app tells them apart. */
object ArtifactNaming {
    const val APK_PREFIX = "almus-apk-"
    const val AAB_PREFIX = "almus-aab-"
    const val LOG_PREFIX = "almus-build-log-"
    const val UNSIGNED_PREFIX = "almus-unsigned-"

    fun kindOf(name: String): ArtifactKind = when {
        name.startsWith(APK_PREFIX) -> ArtifactKind.APK
        name.startsWith(AAB_PREFIX) -> ArtifactKind.AAB
        name.startsWith(LOG_PREFIX) -> ArtifactKind.BUILD_LOG
        name.startsWith(UNSIGNED_PREFIX) -> ArtifactKind.INTERMEDIATE
        else -> ArtifactKind.OTHER
    }
}
