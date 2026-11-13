package com.almus.appcreatorpro.workspace

/**
 * Phase 28: one GitHub repository (at one branch) that was brought onto this phone and turned into a local project the
 * AI can work on. Holds NO secret: access tokens stay in the GitHub sign-in's encrypted store and are never copied here.
 */
data class RepoWorkspace(
    val id: String,
    val owner: String,
    val repoName: String,
    val branch: String,
    /** The commit the local copy was downloaded from (full sha). */
    val commitSha: String,
    val commitTitle: String,
    val isPrivate: Boolean,
    val defaultBranch: String,
    val description: String?,
    val htmlUrl: String,
    /** The local project that holds the downloaded files (editor, AI assistant, agent and Git all use this id). */
    val projectId: String,
    val importedAtMillis: Long,
    val lastOpenedAtMillis: Long,
    val lastSyncedAtMillis: Long,
) {
    val fullName: String get() = "$owner/$repoName"
    val shortSha: String get() = commitSha.take(7)
}

/** Where the repository conversation currently is. Derived from the chat itself, so it survives leaving the screen. */
enum class WorkspaceStage(val label: String) {
    /** Talking: questions, answers, inspection. Nothing can be changed. */
    DISCUSSING("Discussing"),

    /** The AI wrote a plan and is waiting for the user's decision. Nothing can be changed yet. */
    PLAN_READY("Plan ready for your approval"),

    /** The user approved the latest plan. Files still change only through the Agent, which asks again before applying. */
    APPROVED("Plan approved"),
}

/** Parses what a user may type into the search box: `owner/repo` or a github.com link. */
object RepoReference {
    data class Ref(val owner: String, val name: String) {
        val fullName: String get() = "$owner/$name"
    }

    private val ownerRegex = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$")
    private val nameRegex = Regex("^[A-Za-z0-9._-]{1,100}$")

    fun parse(raw: String): Ref? {
        var text = raw.trim()
        if (text.isEmpty() || text.length > 300) return null
        val lower = text.lowercase()
        for (prefix in listOf("https://github.com/", "http://github.com/", "https://www.github.com/", "github.com/")) {
            if (lower.startsWith(prefix)) {
                text = text.substring(prefix.length)
                break
            }
        }
        text = text.substringBefore('?').substringBefore('#').trim('/')
        val parts = text.split('/').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        // owner/repo is enough; extra segments (tree/main/..., issues) are ignored.
        val owner = parts[0]
        val name = parts[1].removeSuffix(".git")
        if (!ownerRegex.matches(owner) || !nameRegex.matches(name) || name == "." || name == "..") return null
        if (parts.size > 2 && !lower.contains("github.com/")) return null
        return Ref(owner, name)
    }
}
