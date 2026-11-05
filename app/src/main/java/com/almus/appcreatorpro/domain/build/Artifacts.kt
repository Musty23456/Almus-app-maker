package com.almus.appcreatorpro.domain.build

/** What a workflow artifact is. [INTERMEDIATE] is the hand-over file between the build and sign jobs (never shown). */
enum class ArtifactKind { APK, AAB, LOG, INTERMEDIATE, OTHER }

/** The artifact names the cloud workflow uses. The workflow file and the app must agree on them. */
object ArtifactNaming {
    const val APK_PREFIX = "almus-apk-"
    const val AAB_PREFIX = "almus-aab-"
    const val UNSIGNED_PREFIX = "almus-unsigned-"
    const val LOG_PREFIX = "almus-build-log-"

    fun kindOf(name: String): ArtifactKind = when {
        name.startsWith(APK_PREFIX) -> ArtifactKind.APK
        name.startsWith(AAB_PREFIX) -> ArtifactKind.AAB
        name.startsWith(UNSIGNED_PREFIX) -> ArtifactKind.INTERMEDIATE
        name.startsWith(LOG_PREFIX) -> ArtifactKind.LOG
        else -> ArtifactKind.OTHER
    }
}
