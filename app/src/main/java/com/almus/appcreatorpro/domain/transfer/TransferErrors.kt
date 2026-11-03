package com.almus.appcreatorpro.domain.transfer

/**
 * Thrown by the Phase 5 import / export / backup code. [message] is always safe to show to the user:
 * it never contains tokens, signed URLs or raw file-system paths.
 */
class TransferException(
    val kind: Kind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Kind {
        NOT_A_ZIP,
        CORRUPT_ARCHIVE,
        UNSAFE_PATH,
        TOO_LARGE,
        TOO_MANY_FILES,
        EMPTY,
        UNSUPPORTED_PROJECT,
        STORAGE,
        READ_FAILED,
        PERMISSION,
        NOT_FOUND,
        NETWORK,
        AUTH,
        RATE_LIMITED,
        SERVER,
        INVALID_BACKUP,
        LIMIT_REACHED,
    }
}
