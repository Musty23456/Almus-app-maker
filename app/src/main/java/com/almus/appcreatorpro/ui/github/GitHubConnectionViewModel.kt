package com.almus.appcreatorpro.ui.github

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.almus.appcreatorpro.domain.github.AuthMethod
import com.almus.appcreatorpro.service.github.ClientIdValidator
import com.almus.appcreatorpro.service.github.GitHubAuthManager
import com.almus.appcreatorpro.service.github.GitHubAuthState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConnectionForm(
    val clientId: String,
    val method: AuthMethod,
    val clientIdError: String? = null,
)

class GitHubConnectionViewModel(private val auth: GitHubAuthManager) : ViewModel() {

    val authState: StateFlow<GitHubAuthState> = auth.state

    private val _form = MutableStateFlow(ConnectionForm(auth.savedClientId(), auth.savedMethod()))
    val form: StateFlow<ConnectionForm> = _form.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var connectJob: Job? = null

    init {
        // A saved sign-in is re-checked with GitHub; until that succeeds the screen does not say "Connected".
        viewModelScope.launch {
            try {
                auth.ensureVerified()
            } catch (e: CancellationException) {
                throw e
            }
        }
    }

    fun onClientIdChange(value: String) {
        _form.update { it.copy(clientId = value.trim(), clientIdError = null) }
    }

    fun onMethodChange(method: AuthMethod) {
        _form.update { it.copy(method = method) }
    }

    fun connect() {
        val form = _form.value
        val error = ClientIdValidator.validate(form.clientId)
        if (error != null) {
            _form.update { it.copy(clientIdError = error) }
            return
        }
        if (connectJob?.isActive == true) return
        connectJob = viewModelScope.launch { auth.connect(form.method, form.clientId) }
    }

    /** Abandons a sign-in that is waiting for the user to approve on github.com. */
    fun cancel() {
        connectJob?.cancel()
    }

    /** Deletes the token and sign-in details from this phone. */
    fun disconnect() {
        connectJob?.cancel()
        auth.disconnect()
        _message.value = "Disconnected. The token was removed from this phone."
    }

    /** Checks the saved sign-in with GitHub again (after being offline, for example). */
    fun recheck() {
        viewModelScope.launch { auth.verify() }
    }

    fun consumeMessage() {
        _message.value = null
    }
}
