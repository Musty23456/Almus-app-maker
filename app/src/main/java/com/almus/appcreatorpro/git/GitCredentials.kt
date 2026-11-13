package com.almus.appcreatorpro.git

import com.almus.appcreatorpro.service.github.TokenProvider

/** A user name plus a secret. [toString] never shows the secret. */
class GitCredentials(val username: String, private val secret: String) {
    fun secretChars(): CharArray = secret.toCharArray()
    override fun toString(): String = "GitCredentials(***)"
}

/** Supplies credentials for ONE remote URL, or null (anonymous access). */
fun interface GitCredentialSource {
    fun credentialsFor(url: String): GitCredentials?
}

/**
 * Reuses the app's existing GitHub sign-in. The token is handed out only for https://github.com addresses, so it can
 * never be sent to another server, and it is read from the encrypted store at the moment it is needed.
 */
class GitHubTokenCredentialSource(private val tokens: TokenProvider) : GitCredentialSource {
    override fun credentialsFor(url: String): GitCredentials? {
        if (!GitInputValidator.isGitHubHttps(url)) return null
        val token = tokens.token()?.takeIf { it.isNotBlank() } ?: return null
        return GitCredentials(username = "x-access-token", secret = token)
    }
}
