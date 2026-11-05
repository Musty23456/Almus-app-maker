package com.almus.appcreatorpro.ui.signing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.almus.appcreatorpro.domain.build.SigningException
import com.almus.appcreatorpro.domain.build.SigningProfile
import com.almus.appcreatorpro.domain.build.SigningSecrets
import com.almus.appcreatorpro.service.signing.CertificateOwner
import com.almus.appcreatorpro.service.signing.SigningManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream

data class SigningUiState(
    val profiles: List<SigningProfile> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val info: String? = null,
    /** Secrets of one key shown on screen right now. Cleared by [SigningViewModel.hideSecrets]. Never persisted. */
    val revealed: Pair<SigningProfile, SigningSecrets>? = null,
)

/** Release signing manager: create, import, reveal the GitHub secrets, confirm them, back up and delete keys. */
class SigningViewModel(
    private val manager: SigningManager,
) : ViewModel() {

    private val _state = MutableStateFlow(SigningUiState())
    val state: StateFlow<SigningUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(profiles = safeProfiles()) }
    }

    private fun safeProfiles(): List<SigningProfile> = try {
        manager.profiles()
    } catch (e: Exception) {
        emptyList()
    }

    fun suggestPassword(): String = SigningManager.generatePassword()

    fun generate(label: String, alias: String, password: String, name: String, organization: String, country: String) {
        launchOp {
            manager.generate(label, alias, password, CertificateOwner(name, organization, country))
            "Key created. Next: add the four secrets to your GitHub repository (Show secrets)."
        }
    }

    fun import(label: String, bytes: ByteArray, alias: String, storePassword: String, keyPassword: String) {
        launchOp {
            val profile = manager.import(label, bytes, alias, storePassword, keyPassword)
            if (profile.verifiedOnDevice) {
                "Key imported and checked."
            } else {
                "Key imported. This phone cannot open JKS files, so the build checks it in the cloud."
            }
        }
    }

    fun reveal(profile: SigningProfile) {
        try {
            _state.update { it.copy(revealed = profile to manager.secrets(profile.id), error = null) }
        } catch (e: SigningException) {
            _state.update { it.copy(error = e.message) }
        }
    }

    fun hideSecrets() {
        _state.update { it.copy(revealed = null) }
    }

    fun confirmSecrets(profile: SigningProfile, repoFullName: String) {
        try {
            manager.confirmSecrets(profile.id, repoFullName.trim())
            _state.update { it.copy(profiles = safeProfiles(), info = "Saved. Release builds for $repoFullName can now start.", error = null) }
        } catch (e: SigningException) {
            _state.update { it.copy(error = e.message) }
        }
    }

    fun delete(profile: SigningProfile) {
        manager.delete(profile.id)
        _state.update { it.copy(profiles = safeProfiles(), revealed = null, info = "Key deleted from this phone.") }
    }

    /** Writes the keystore file into the stream the user picked (a backup they keep themselves). */
    fun backup(profile: SigningProfile, open: () -> OutputStream?) {
        viewModelScope.launch {
            try {
                val bytes = manager.keystoreBytes(profile.id)
                withContext(Dispatchers.IO) {
                    val out = open() ?: throw IOException("No destination was chosen.")
                    out.use { it.write(bytes) }
                }
                _state.update { it.copy(info = "Backup saved. Keep it and your password somewhere safe.", error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SigningException) {
                _state.update { it.copy(error = e.message) }
            } catch (e: Exception) {
                _state.update { it.copy(error = "The backup could not be saved there.") }
            }
        }
    }

    fun showError(text: String) {
        _state.update { it.copy(error = text) }
    }

    fun clearMessages() {
        _state.update { it.copy(error = null, info = null) }
    }

    private fun launchOp(block: () -> String) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null, info = null) }
        viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.Default) { block() }
                _state.update { it.copy(busy = false, info = info, profiles = safeProfiles()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SigningException) {
                _state.update { it.copy(busy = false, error = e.message) }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = "That did not work. Nothing was saved.") }
            }
        }
    }
}
