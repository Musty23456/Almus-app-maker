package com.almus.appcreatorpro.git

/**
 * The only exception the Git layer throws (besides cancellation). The message is always run through [GitRedactor],
 * and the original cause is NOT kept: JGit messages can contain the remote URL and a cause chain could leak into crash reports.
 */
class GitException(val kind: Kind, message: String, /** Set with CONFIRMATION_REQUIRED: what the user must be shown. */ val risk: GitRisk? = null) :
    Exception(GitRedactor.redact(message)) {
    enum class Kind {
        NOT_A_REPOSITORY, ALREADY_A_REPOSITORY, INVALID_INPUT, NOT_FOUND, ALREADY_EXISTS, NO_COMMITS, NOTHING_TO_COMMIT,
        IDENTITY_MISSING, UNCOMMITTED_CHANGES, CONFLICTS, NOT_MERGED, AUTH, NETWORK, REJECTED, NO_REMOTE, DETACHED_HEAD,
        UNSAFE, SECRETS_DETECTED, CONFIRMATION_REQUIRED, BUSY, MERGE_IN_PROGRESS, UNKNOWN,
    }

    val isAuthProblem: Boolean get() = kind == Kind.AUTH
}
