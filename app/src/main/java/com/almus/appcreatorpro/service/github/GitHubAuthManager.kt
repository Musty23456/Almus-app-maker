package com.almus.appcreatorpro.service.github

import com.almus.appcreatorpro.data.storage.KeyValueStore
import com.almus.appcreatorpro.domain.github.AuthMethod
import com.almus.appcreatorpro.domain.github.DeviceFlowException
import com.almus.appcreatorpro.domain.github.GitHubApi
import com.almus.appcreatorpro.domain.github.GitHubException
import com.almus.appcreatorpro.domain.github.GitHubUser
import com.almus.appcreatorpro.domain.github.GrantedAccess
import com.almus.appcreatorpro.domain.model.GitHubConnection
import com.almus.appcreatorpro.domain.repository.GitHubRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the UI may truthfully say about the GitHub connection. [Connected] only ever follows a successful /user call. */
sealed interface GitHubAuthState {
    data object Disconnected : GitHubAuthState

    /** The user must enter [userCode] at [verificationUri]. */
    data class AwaitingAuthorization(
        val userCode: String,
        val verificationUri: String,
        val expiresAtMillis: Long,
    ) : GitHubAuthState

    /** A token exists and is being checked against GitHub. */
    data object Verifying : GitHubAuthState

    data class Connected(val user: GitHubUser, val access: GrantedAccess) : GitHubAuthState

    /** A saved sign-in exists but GitHub could not be reached, so it is NOT known to be valid. */
    data class Unverified(val username: String, val reason: String) : GitHubAuthState

    /** GitHub rejected the saved token. The token has been deleted from the phone. */
    data class Expired(val username: String?) : GitHubAuthState

    /** A sign-in attempt failed (denied, expired code, bad Client ID...). Nothing was stored. */
    data class Failed(val message: String) : GitHubAuthState
}

/**
 * Owns the GitHub sign-in: runs the device flow, verifies the token, stores it encrypted, and clears it.
 * The token is only ever held in [secrets]; [state] contains no secret.
 */
class GitHubAuthManager(
    private val meta: KeyValueStore,
    private val secrets: SecretStore,
    private val transport: HttpTransport,
    private val deviceFlow: DeviceFlowClient = DeviceFlowClient(transport),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<GitHubAuthState>(initialState())
    val state: StateFlow<GitHubAuthState> = _state.asStateFlow()

    /** Reads the stored token for each request. Used by the API client and by artifact downloads. */
    val tokenProvider: TokenProvider = TokenProvider { secrets.get(TOKEN_KEY) }

    /** The shared GitHub client: uses whatever token is currently stored; a 401 clears it. */
    val api: GitHubApi = GitHubApiImpl(
        transport = transport,
        tokenProvider = tokenProvider,
        onUnauthorized = ::onTokenRejected,
    )

    /** Call when any GitHub request (e.g. an artifact download) was answered with 401: clears the dead token. */
    fun notifyTokenRejected() = onTokenRejected()

    fun savedClientId(): String = meta.getString(CLIENT_ID_KEY).orEmpty()

    fun savedMethod(): AuthMethod = AuthMethod.fromId(meta.getString(METHOD_KEY)) ?: AuthMethod.GITHUB_APP

    /** Current access rights, or null unless verified-connected. */
    fun currentAccess(): GrantedAccess? = (state.value as? GitHubAuthState.Connected)?.access

    private fun initialState(): GitHubAuthState {
        val username = meta.getString(USERNAME_KEY)
        return if (secrets.get(TOKEN_KEY) != null && username != null) {
            GitHubAuthState.Unverified(username, "Saved sign-in has not been checked with GitHub yet.")
        } else {
            GitHubAuthState.Disconnected
        }
    }

    /** Checks the saved token with GitHub (no-op when already verified or when there is no token). */
    suspend fun ensureVerified() {
        if (state.value is GitHubAuthState.Connected) return
        verify()
    }

    suspend fun verify() {
        if (secrets.get(TOKEN_KEY) == null) {
            if (state.value !is GitHubAuthState.Expired) _state.value = GitHubAuthState.Disconnected
            return
        }
        val method = savedMethod()
        val savedName = meta.getString(USERNAME_KEY).orEmpty()
        _state.value = GitHubAuthState.Verifying
        try {
            val me = api.getCurrentUser()
            val scopes = me.scopes ?: if (method.isOAuthApp) GrantedAccess.parseScopes(meta.getString(SCOPES_KEY)) else emptySet()
            meta.putString(USERNAME_KEY, me.user.login)
            meta.putString(SCOPES_KEY, scopes.joinToString(","))
            _state.value = GitHubAuthState.Connected(me.user, GrantedAccess(method, scopes))
        } catch (e: CancellationException) {
            throw e
        } catch (e: GitHubException) {
            // A 401 already moved the state to Expired via onTokenRejected().
            if (!e.isAuthProblem) _state.value = GitHubAuthState.Unverified(savedName, e.message.orEmpty())
        }
    }

    /**
     * Full sign-in. Progress is published through [state]; nothing is stored unless GitHub accepts the new token.
     * Cancelling the coroutine abandons the attempt and leaves any previous sign-in untouched.
     */
    suspend fun connect(method: AuthMethod, clientId: String) {
        val previous = _state.value
        try {
            val code = deviceFlow.requestCode(clientId, method.requestedScopes)
            _state.value = GitHubAuthState.AwaitingAuthorization(
                userCode = code.userCode,
                verificationUri = code.verificationUri,
                expiresAtMillis = now() + code.expiresInSeconds * 1000L,
            )
            val token = deviceFlow.awaitToken(clientId, code)
            _state.value = GitHubAuthState.Verifying

            // Verify with the NEW token before storing anything.
            val checker = GitHubApiImpl(transport = transport, tokenProvider = { token.accessToken })
            val me = checker.getCurrentUser()
            val scopes = me.scopes ?: if (method.isOAuthApp) token.scopes else emptySet()

            secrets.put(TOKEN_KEY, token.accessToken)
            meta.putString(CLIENT_ID_KEY, clientId.trim())
            meta.putString(METHOD_KEY, method.id)
            meta.putString(USERNAME_KEY, me.user.login)
            meta.putString(SCOPES_KEY, scopes.joinToString(","))
            _state.value = GitHubAuthState.Connected(me.user, GrantedAccess(method, scopes))
        } catch (e: CancellationException) {
            _state.value = if (previous is GitHubAuthState.AwaitingAuthorization || previous is GitHubAuthState.Verifying) {
                GitHubAuthState.Disconnected
            } else {
                previous
            }
            throw e
        } catch (e: DeviceFlowException) {
            _state.value = GitHubAuthState.Failed(e.message.orEmpty())
        } catch (e: GitHubException) {
            _state.value = GitHubAuthState.Failed(e.message.orEmpty())
        }
    }

    /**
     * Deletes the token and sign-in details from this phone. The token stays valid on GitHub until revoked there
     * (GitHub only lets the app's owner revoke with a client secret, which this app deliberately does not have).
     */
    fun disconnect() {
        secrets.remove(TOKEN_KEY)
        meta.remove(USERNAME_KEY)
        meta.remove(SCOPES_KEY)
        meta.remove(METHOD_KEY)
        _state.value = GitHubAuthState.Disconnected
    }

    private fun onTokenRejected() {
        val name = meta.getString(USERNAME_KEY)
        secrets.remove(TOKEN_KEY)
        _state.value = GitHubAuthState.Expired(name)
    }

    private companion object {
        const val TOKEN_KEY = "github_access_token"
        const val CLIENT_ID_KEY = "github_client_id"
        const val METHOD_KEY = "github_auth_method"
        const val USERNAME_KEY = "github_username"
        const val SCOPES_KEY = "github_scopes"
    }
}

/** Keeps the Phase 1 dashboard honest: "Connected" only when GitHub has confirmed the token. */
class AuthBackedGitHubRepository(private val auth: GitHubAuthManager) : GitHubRepository {
    override suspend fun getConnection(): GitHubConnection {
        auth.ensureVerified()
        return when (val s = auth.state.value) {
            is GitHubAuthState.Connected -> GitHubConnection.Connected(s.user.login)
            else -> GitHubConnection.NotConnected
        }
    }
}
