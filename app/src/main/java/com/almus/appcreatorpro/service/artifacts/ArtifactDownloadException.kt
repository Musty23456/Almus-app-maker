package com.almus.appcreatorpro.service.artifacts

/** Why an artifact download failed. [message] is always safe to show (no token, no signed URL). */
class ArtifactDownloadException(
    val kind: Kind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    enum class Kind(
        /** Trying again may help without the user changing anything. */
        val retryable: Boolean,
    ) {
        NOT_CONNECTED(false),
        TOKEN_REJECTED(false),

        /** GitHub answered 410: the artifact passed its retention period. */
        EXPIRED(false),

        /** GitHub answered 404: the artifact (or its run) was deleted. */
        NOT_FOUND(false),
        FORBIDDEN(false),
        RATE_LIMITED(true),
        NETWORK(true),

        /** The connection broke, or fewer bytes arrived than announced. */
        INTERRUPTED(true),
        SERVER(true),

        /** GitHub's redirect was missing or unsafe (not https). */
        BAD_REDIRECT(true),

        /** The temporary download link was refused (they expire after about a minute). */
        LINK_REJECTED(true),

        /** Downloaded data is not a valid zip/APK. */
        CORRUPT(true),
        NO_APK(false),
        TOO_LARGE(false),

        /** Could not write to the phone's storage (full, or not writable). */
        STORAGE(false),
        UNKNOWN(true),
    }

    val retryable: Boolean get() = kind.retryable
}

/** Progress of one download, for the UI. */
data class DownloadProgress(
    val phase: Phase,
    val bytesDone: Long = 0L,
    /** Size announced by the server, or null if unknown (then no percentage can be shown). */
    val totalBytes: Long? = null,
) {
    enum class Phase { CONNECTING, DOWNLOADING, VERIFYING, SAVING }

    /** 0f..1f, or null when the total is unknown. */
    val fraction: Float?
        get() = totalBytes?.takeIf { it > 0L }?.let { (bytesDone.toFloat() / it.toFloat()).coerceIn(0f, 1f) }
}
