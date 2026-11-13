package com.almus.appcreatorpro.git

import org.eclipse.jgit.errors.UnsupportedCredentialItem
import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.URIish

/**
 * Answers a server's credential request only when the request is for [allowedHost] over https. A redirect to any
 * other host gets nothing, so the GitHub token cannot follow a redirect to someone else's server.
 */
class HostPinnedCredentialsProvider(
    private val allowedHost: String,
    private val credentials: GitCredentials,
) : CredentialsProvider() {
    override fun isInteractive(): Boolean = false

    override fun supports(vararg items: CredentialItem): Boolean = items.all {
        it is CredentialItem.InformationalMessage || it is CredentialItem.Username || it is CredentialItem.Password
    }

    override fun get(uri: URIish, vararg items: CredentialItem): Boolean {
        val ok = uri.scheme.equals("https", true) && uri.host.equals(allowedHost, true)
        if (!ok) return false
        for (item in items) {
            when (item) {
                is CredentialItem.InformationalMessage -> Unit
                is CredentialItem.Username -> item.value = credentials.username
                is CredentialItem.Password -> item.value = credentials.secretChars()
                else -> throw UnsupportedCredentialItem(uri, item.javaClass.name + ":" + item.promptText)
            }
        }
        return true
    }

    override fun toString(): String = "HostPinnedCredentialsProvider($allowedHost)"
}
