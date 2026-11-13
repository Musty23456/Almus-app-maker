package com.almus.appcreatorpro.workspace

import com.almus.appcreatorpro.domain.github.AuthMethod
import com.almus.appcreatorpro.domain.github.CommitSummary
import com.almus.appcreatorpro.domain.github.GitHubApi
import com.almus.appcreatorpro.domain.github.GitHubBranch
import com.almus.appcreatorpro.domain.github.GitHubException
import com.almus.appcreatorpro.domain.github.GitHubRepo
import com.almus.appcreatorpro.domain.github.RepoPage
import com.almus.appcreatorpro.domain.transfer.TransferException
import com.almus.appcreatorpro.domain.transfer.TransferProgress
import com.almus.appcreatorpro.service.github.GitHubAuthManager
import com.almus.appcreatorpro.service.github.GitHubAuthState
import com.almus.appcreatorpro.service.transfer.GitHubRepoDownloader
import java.io.File

/**
 * What the repository workspace needs from GitHub. Works with a sign-in (private + public repositories) and without one
 * (public repositories only), so a user can try a public project before connecting anything.
 */
interface WorkspaceGitHub {
    /** The verified signed-in login, or null when GitHub is not connected (public-only mode). */
    suspend fun connectedUser(): String?

    /** The signed-in user's own repositories. Throws [GitHubException] (NOT_CONNECTED) when nobody is signed in. */
    suspend fun myRepositories(page: Int): RepoPage

    /** Text search. Works without a sign-in for public repositories. */
    suspend fun search(text: String, page: Int): RepoPage

    /** One repository by name. Public ones need no sign-in; private ones need a sign-in that can read them. */
    suspend fun lookup(owner: String, name: String): GitHubRepo

    suspend fun branches(owner: String, name: String): List<GitHubBranch>

    suspend fun latestCommit(owner: String, name: String, branch: String): CommitSummary

    /** Downloads the source of [ref] (a commit sha) as a ZIP into [target]. */
    suspend fun download(owner: String, name: String, ref: String, target: File, onProgress: (TransferProgress) -> Unit)
}

/**
 * [WorkspaceGitHub] on top of the app's existing [GitHubAuthManager] (stored token, shared API client) and
 * [GitHubRepoDownloader]. The token is never read here: it stays inside those two classes and goes only to
 * api.github.com in an Authorization header. [publicApi] is a second client that never sends a token.
 */
class AuthBackedWorkspaceGitHub(
    private val auth: GitHubAuthManager,
    private val publicApi: GitHubApi,
    private val downloader: GitHubRepoDownloader,
) : WorkspaceGitHub {

    private fun hasToken(): Boolean = auth.tokenProvider.token()?.isNotBlank() == true

    /**
     * Uses the signed-in client when a token exists. If GitHub rejects that token (expired or revoked) the dead token is
     * cleared by the auth manager, and the same read is tried once more anonymously, so public repositories keep working.
     */
    private suspend fun <T> withApi(block: suspend (GitHubApi) -> T): T {
        if (!hasToken()) return block(publicApi)
        return try {
            block(auth.api)
        } catch (e: GitHubException) {
            if (e.kind == GitHubException.Kind.TOKEN_REJECTED) block(publicApi) else throw e
        }
    }

    override suspend fun connectedUser(): String? {
        auth.ensureVerified()
        return (auth.state.value as? GitHubAuthState.Connected)?.user?.login
    }

    override suspend fun myRepositories(page: Int): RepoPage {
        auth.ensureVerified()
        val connected = auth.state.value as? GitHubAuthState.Connected
            ?: throw GitHubException(GitHubException.Kind.NOT_CONNECTED, "Connect GitHub to see your own repositories.")
        return auth.api.listRepositories(page, connected.access.method == AuthMethod.GITHUB_APP)
    }

    override suspend fun search(text: String, page: Int): RepoPage = withApi { it.searchRepositories(text, page) }

    override suspend fun lookup(owner: String, name: String): GitHubRepo = withApi { it.getRepository(owner, name) }

    override suspend fun branches(owner: String, name: String): List<GitHubBranch> = withApi { it.listBranches(owner, name) }

    override suspend fun latestCommit(owner: String, name: String, branch: String): CommitSummary =
        withApi { it.getLatestCommit(owner, name, branch) }

    override suspend fun download(owner: String, name: String, ref: String, target: File, onProgress: (TransferProgress) -> Unit) {
        try {
            downloader.download(owner, name, ref, target, onProgress, allowAnonymous = true)
        } catch (e: TransferException) {
            // A 401 makes the downloader clear the dead token. Then a public repository can still be fetched anonymously.
            if (e.kind == TransferException.Kind.AUTH && !hasToken()) {
                downloader.download(owner, name, ref, target, onProgress, allowAnonymous = true)
            } else {
                throw e
            }
        }
    }
}
