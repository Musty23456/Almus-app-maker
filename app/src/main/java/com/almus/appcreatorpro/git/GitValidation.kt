package com.almus.appcreatorpro.git

import java.net.URI

/** Input checks. Every function returns an error message, or null when the input is fine. */
object GitInputValidator {
    private val badBranchChars = Regex("""[\s~^:?*\[\\\u0000-\u001f\u007f]""")
    private val remoteNameRegex = Regex("""^[A-Za-z0-9][A-Za-z0-9._\-]{0,39}$""")

    fun branchName(raw: String): String? {
        val n = raw
        return when {
            n.isBlank() -> "Branch name is required."
            n.length > 100 -> "Branch name is too long (100 characters at most)."
            n == "@" || n.equals("HEAD", ignoreCase = true) -> "That branch name is reserved."
            badBranchChars.containsMatchIn(n) -> "Branch names cannot contain spaces or these characters: ~ ^ : ? * [ \\"
            n.startsWith("-") || n.startsWith("/") || n.endsWith("/") || n.endsWith(".") -> "Branch names cannot start with - or /, or end with / or a dot."
            n.contains("..") || n.contains("//") || n.contains("@{") -> "Branch names cannot contain \"..\", \"//\" or \"@{\"."
            n.split('/').any { it.startsWith(".") || it.endsWith(".lock") } -> "Parts of a branch name cannot start with a dot or end with .lock."
            else -> null
        }
    }

    fun remoteName(raw: String): String? =
        if (remoteNameRegex.matches(raw)) null else "Remote names use letters, digits, . _ - (up to 40 characters)."

    /**
     * Only https URLs are accepted: no http (cleartext), ssh, git, file or local paths, and NO credentials inside the URL
     * (the app sends its own GitHub sign-in, and only to github.com).
     */
    fun remoteUrl(raw: String): String? {
        val url = raw.trim()
        if (url.isEmpty()) return "Repository address is required."
        if (url.length > 300) return "Repository address is too long."
        if (url.any { it.isWhitespace() || it.code < 32 }) return "Repository address cannot contain spaces."
        if (!url.startsWith("https://", ignoreCase = true)) return "Use an https:// address (for example https://github.com/owner/repo.git)."
        val uri = try { URI(url) } catch (e: Exception) { return "That is not a valid address." }
        if (uri.userInfo != null || url.substringAfter("://").substringBefore('/').contains('@')) {
            return "Do not put a user name, password or token in the address. Sign in with GitHub instead."
        }
        val host = uri.host ?: return "That is not a valid address."
        if (!host.contains('.') || host.startsWith(".") || host.endsWith(".")) return "That is not a valid address."
        if (uri.rawQuery != null || uri.rawFragment != null) return "Remove everything after ? or # from the address."
        if (uri.path.isNullOrBlank() || uri.path.trim('/').isEmpty()) return "The address must point to a repository."
        return null
    }

    fun isGitHubHttps(url: String): Boolean = try {
        val u = URI(url.trim())
        u.scheme.equals("https", true) && u.host.equals("github.com", true) && u.userInfo == null
    } catch (e: Exception) {
        false
    }

    /** "https://github.com/octo/Hello-World.git" -> "Hello-World" (used as a default project name). */
    fun repoNameOf(url: String): String =
        url.trim().trimEnd('/').substringAfterLast('/').removeSuffix(".git").ifBlank { "Cloned project" }

    fun commitMessage(raw: String): String? = when {
        raw.isBlank() -> "Write a commit message."
        raw.length > 5000 -> "The commit message is too long."
        else -> null
    }

    fun identity(name: String, email: String): String? = when {
        name.isBlank() || name.length > 100 || name.any { it == '<' || it == '>' || it == '\n' || it == '\r' } -> "Enter a name (no < > or line breaks)."
        email.length > 200 || !Regex("""^[^\s@<>]+@[^\s@<>]+$""").matches(email.trim()) -> "Enter a valid email address."
        else -> null
    }

    /** A path inside the work tree: relative, '/' separated, no '..', not inside `.git`. */
    fun repoPath(path: String): String? = when {
        path.isBlank() -> "A file path is required."
        path.startsWith("/") || path.contains('\\') || path.any { it.code < 32 } -> "That is not a valid project path."
        path.split('/').any { it.isEmpty() || it == "." || it == ".." || it.equals(".git", ignoreCase = true) } -> "That is not a valid project path."
        else -> null
    }
}
