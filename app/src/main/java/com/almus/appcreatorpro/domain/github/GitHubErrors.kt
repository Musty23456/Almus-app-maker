package com.almus.appcreatorpro.domain.github

/**
 * Every failure of a GitHub operation. [message] is always safe to show to the user and never contains a token.
 */
class GitHubException(
    val kind: Kind,
    message: String,
    /** For [Kind.RATE_LIMITED]: epoch millis when the limit resets, if GitHub said so. */
    val retryAtMillis: Long? = null,
    val httpStatus: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {

    enum class Kind {
        /** No token stored, or the user is not signed in. */
        NOT_CONNECTED,

        /** GitHub rejected the token (401): expired, revoked or wrong. */
        TOKEN_REJECTED,

        /** Token is valid but lacks permission for this action (403), e.g. a read-only repository. */
        FORBIDDEN,
        RATE_LIMITED,
        REPO_NOT_FOUND,
        BRANCH_NOT_FOUND,
        WORKFLOW_NOT_FOUND,
        NOT_FOUND,

        /** 409/422 conflicts: branch moved, branch protected, invalid input. */
        CONFLICT,
        VALIDATION,
        NETWORK,
        SERVER,
        BAD_RESPONSE,
        TIMEOUT,
        UNKNOWN,
    }

    /** Worth retrying automatically (for idempotent reads). */
    val isTransient: Boolean get() = kind == Kind.NETWORK || kind == Kind.SERVER || kind == Kind.TIMEOUT

    val isAuthProblem: Boolean get() = kind == Kind.NOT_CONNECTED || kind == Kind.TOKEN_REJECTED
}

/** Device-flow (sign-in) failures. */
class DeviceFlowException(val reason: Reason, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Reason { INVALID_CLIENT_ID, DEVICE_FLOW_DISABLED, EXPIRED, DENIED, NETWORK, BAD_RESPONSE, UNKNOWN }
}

/** Returned (inside Result.failure) when a build cannot start; the message is user-safe. */
class CloudBuildNotReadyException(message: String) : Exception(message)
