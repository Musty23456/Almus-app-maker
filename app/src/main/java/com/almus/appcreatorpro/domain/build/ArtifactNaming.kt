package com.almus.appcreatorpro.domain.build

/** What a workflow artifact is. The names are chosen by the workflow, so this is the one place that knows them. */
enum class ArtifactKind { APK, AAB, LOG, INTERMEDIATE, OTHER }

object ArtifactNaming {
    const val APK_PREFIX = "almus-apk-"
    const val AAB_PREFIX = "almus-aab-"
    const val LOG_PREFIX = "almus-build-log-"

    /** The short-lived hand-over between the build job and the sign job (an implementation detail, never shown). */
    const val UNSIGNED_PREFIX = "almus-unsigned-"

    fun kindOf(name: String): ArtifactKind = when {
        name.startsWith(APK_PREFIX) -> ArtifactKind.APK
        name.startsWith(AAB_PREFIX) -> ArtifactKind.AAB
        name.startsWith(LOG_PREFIX) -> ArtifactKind.LOG
        name.startsWith(UNSIGNED_PREFIX) -> ArtifactKind.INTERMEDIATE
        else -> ArtifactKind.OTHER
    }
}
