package com.almus.appcreatorpro.git

enum class GitRiskLevel {
    /** One tap on "Confirm". */
    CONFIRM,
    /** The user must type [GitRisk.requiredText] exactly. */
    TYPE_TO_CONFIRM,
}

/** What the user is shown before a risky Git operation. The service re-checks it, so the UI cannot skip it. */
data class GitRisk(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val level: GitRiskLevel = GitRiskLevel.CONFIRM,
    val requiredText: String? = null,
)

/** The user's answer. [typedText] only matters for [GitRiskLevel.TYPE_TO_CONFIRM]. */
data class GitConfirmation(val acknowledged: Boolean, val typedText: String? = null) {
    fun satisfies(risk: GitRisk): Boolean = acknowledged && when (risk.level) {
        GitRiskLevel.CONFIRM -> true
        GitRiskLevel.TYPE_TO_CONFIRM -> typedText?.trim() == risk.requiredText
    }

    companion object {
        val Yes = GitConfirmation(acknowledged = true)
    }
}

object GitRiskAssessor {
    fun deleteBranch(p: BranchDeletePreview): GitRisk = when {
        !p.isMerged && !p.existsOnRemote -> GitRisk(
            title = "Delete unmerged branch?",
            message = "\"${p.name}\" has ${p.unmergedCommits} commit(s) that are not in your current branch and not on any remote. " +
                "Deleting the branch makes that work very hard to get back. Type the branch name to confirm.",
            confirmLabel = "Delete branch",
            level = GitRiskLevel.TYPE_TO_CONFIRM,
            requiredText = p.name,
        )
        !p.isMerged -> GitRisk(
            title = "Delete branch?",
            message = "\"${p.name}\" has ${p.unmergedCommits} commit(s) that are not merged into your current branch. " +
                "They still exist on the remote, but this local branch will be removed.",
            confirmLabel = "Delete branch",
            level = GitRiskLevel.TYPE_TO_CONFIRM,
            requiredText = p.name,
        )
        else -> GitRisk(
            title = "Delete branch?",
            message = "\"${p.name}\" is fully merged into your current branch. Only the branch name is removed; no commits are lost.",
            confirmLabel = "Delete branch",
        )
    }

    fun abortMerge(conflicts: Int): GitRisk = GitRisk(
        title = "Abort the merge?",
        message = "This returns every file to how it was before the merge and throws away everything you resolved so far" +
            (if (conflicts > 0) " ($conflicts conflicted file(s) remain)." else "."),
        confirmLabel = "Abort merge",
    )

    fun resolveWithSide(path: String, side: ConflictSide): GitRisk = GitRisk(
        title = if (side == ConflictSide.OURS) "Keep your version?" else "Take their version?",
        message = "\"$path\" will be replaced by ${if (side == ConflictSide.OURS) "your" else "the incoming"} version completely. " +
            "The other side's changes to this file are discarded.",
        confirmLabel = if (side == ConflictSide.OURS) "Keep mine" else "Take theirs",
    )

    fun push(p: PushPreview): GitRisk = GitRisk(
        title = "Push to ${p.remote}?",
        message = (if (p.commits != null) "${p.commits} commit(s) will be uploaded" else "Your commits will be uploaded") +
            " to ${p.remote}/${p.branch}" + (if (p.createsRemoteBranch) ", creating that branch there" else "") +
            ". Anyone with access to the repository can see them. The app never force-pushes.",
        confirmLabel = "Push",
    )

    fun commitWithSecrets(findings: List<GitSecretGuard.Finding>): GitRisk = GitRisk(
        title = "Possible secrets in this commit",
        message = findings.take(8).joinToString("\n") { "• ${it.path}: ${it.reason}" } +
            (if (findings.size > 8) "\n…and ${findings.size - 8} more" else "") +
            "\n\nOnce pushed, a secret must be treated as leaked. Remove it or add the file to .gitignore instead.",
        confirmLabel = "Commit anyway",
    )
}
