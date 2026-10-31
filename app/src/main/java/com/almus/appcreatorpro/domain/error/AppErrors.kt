package com.almus.appcreatorpro.domain.error

/** Thrown by prototype services for features that are intentionally not built yet. */
class FeatureNotImplementedException(
    val featureName: String,
    val plannedPhase: String = "Phase 2",
) : UnsupportedOperationException("$featureName is not implemented yet (planned for $plannedPhase).")

/** Thrown when user input fails validation at the domain layer. */
class ValidationException(message: String) : IllegalArgumentException(message)

/** Thrown by project/file storage. [message] is always safe to show to the user. */
class StorageException(
    val kind: Kind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Kind { NOT_FOUND, ALREADY_EXISTS, INVALID_NAME, TOO_LARGE, CORRUPTED, IO }
}

/** Turns any throwable into a short message that is safe to show to a user. */
fun Throwable.toUserMessage(): String = when (this) {
    is FeatureNotImplementedException -> message ?: "This feature is not available yet."
    is ValidationException -> message ?: "Invalid input."
    is StorageException -> message ?: "A storage error occurred."
    is com.almus.appcreatorpro.domain.github.GitHubException -> message ?: "A GitHub error occurred."
    is com.almus.appcreatorpro.domain.github.DeviceFlowException -> message ?: "GitHub sign-in failed."
    is com.almus.appcreatorpro.domain.github.CloudBuildNotReadyException -> message ?: "The build cannot start yet."
    else -> "Something went wrong. Please try again."
}
