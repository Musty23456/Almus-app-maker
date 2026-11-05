package com.almus.appcreatorpro.data.repository

import com.almus.appcreatorpro.domain.model.GitHubConnection
import com.almus.appcreatorpro.domain.repository.GitHubRepository

/**
 * PROTOTYPE: always reports "not connected". No token is requested, stored or logged in Phase 1.
 * Phase 2 plan: GitHub OAuth device flow, token kept in Android Keystore-backed storage.
 */
class PrototypeGitHubRepository : GitHubRepository {
    override suspend fun getConnection(): GitHubConnection = GitHubConnection.NotConnected
}
