package com.almus.appcreatorpro.service.transfer

import com.almus.appcreatorpro.domain.github.AuthMethod
import com.almus.appcreatorpro.domain.github.GitHubBranch
import com.almus.appcreatorpro.domain.github.RepoPage
import com.almus.appcreatorpro.domain.transfer.TransferProgress
import com.almus.appcreatorpro.service.github.GitHubAuthManager
import com.almus.appcreatorpro.service.github.GitHubAuthState
import java.io.File

/** What the import screen needs from GitHub. The real one reuses the app's existing sign-in. */
interface GitHubSource {
    /** The signed-in user's login, or null when GitHub is not connected (or the sign-in is no longer valid). */
    suspend fun connectedUser(): String?

    suspend fun listRepositories(page: Int): RepoPage

    suspend fun listBranches(owner: String, name: String): List<GitHubBranch>

    suspend fun download(owner: String, name: String, ref: String, target: File, onProgress: (TransferProgress) -> Unit)
}

/** [GitHubSource] on top of the existing [GitHubAuthManager] (token, API client) and [GitHubRepoDownloader]. */
class AuthBackedGitHubSource(
    private val auth: GitHubAuthManager,
    private val downloader: GitHubRepoDownloader,
) : GitHubSource {

    override suspend fun connectedUser(): String? {
        auth.ensureVerified()
        return (auth.state.value as? GitHubAuthState.Connected)?.user?.login
    }

    override suspend fun listRepositories(page: Int): RepoPage {
        val viaInstallations = auth.currentAccess()?.method == AuthMethod.GITHUB_APP
        return auth.api.listRepositories(page, viaInstallations)
    }

    override suspend fun listBranches(owner: String, name: String): List<GitHubBranch> = auth.api.listBranches(owner, name)

    override suspend fun download(owner: String, name: String, ref: String, target: File, onProgress: (TransferProgress) -> Unit) {
        downloader.download(owner, name, ref, target, onProgress)
    }
}
