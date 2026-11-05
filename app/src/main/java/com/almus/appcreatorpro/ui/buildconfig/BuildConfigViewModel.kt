package com.almus.appcreatorpro.ui.buildconfig

import androidx.lifecycle.ViewModel
import com.almus.appcreatorpro.data.repository.BuildConfigurationStore
import com.almus.appcreatorpro.domain.build.BuildConfiguration
import com.almus.appcreatorpro.domain.build.BuildConfigurationValidator
import com.almus.appcreatorpro.domain.build.BuildOptions
import com.almus.appcreatorpro.domain.build.BuildVariant
import com.almus.appcreatorpro.domain.build.OutputFormat
import com.almus.appcreatorpro.domain.build.SigningProfile
import com.almus.appcreatorpro.service.signing.SigningManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The text the user typed, kept as text so a half-typed number is not lost. */
data class BuildConfigUiState(
    val config: BuildConfiguration = BuildConfiguration.DEFAULT,
    val applicationId: String = "",
    val versionName: String = "",
    val versionCode: String = "",
    val minSdk: String = "",
    val targetSdk: String = "",
    val profiles: List<SigningProfile> = emptyList(),
    val errors: Map<String, String> = emptyMap(),
    val saved: Boolean = true,
) {
    val hasErrors: Boolean get() = errors.isNotEmpty()
}

/** Edits and saves the build configuration of one project. Every change is validated before it is stored. */
class BuildConfigViewModel(
    private val projectId: String,
    private val store: BuildConfigurationStore,
    private val signing: SigningManager?,
) : ViewModel() {

    private val _state = MutableStateFlow(BuildConfigUiState())
    val state: StateFlow<BuildConfigUiState> = _state.asStateFlow()

    init {
        reload()
    }

    /** Re-reads the saved configuration and the list of keys (call after creating a key). */
    fun reload() {
        val c = store.get(projectId)
        _state.value = BuildConfigUiState(
            config = c,
            applicationId = c.applicationId.orEmpty(),
            versionName = c.versionName.orEmpty(),
            versionCode = c.versionCode?.toString().orEmpty(),
            minSdk = c.minSdk?.toString().orEmpty(),
            targetSdk = c.targetSdk?.toString().orEmpty(),
            profiles = try { signing?.profiles().orEmpty() } catch (e: Exception) { emptyList() },
        )
    }

    fun setVariant(v: BuildVariant) = change { it.copy(variant = v) }
    fun setCustomOutput(o: OutputFormat) = change { it.copy(customOutput = o) }
    fun setCustomRelease(release: Boolean) = change { it.copy(customRelease = release) }
    fun setCustomSign(sign: Boolean) = change { it.copy(customSign = sign) }
    fun setMinify(value: Boolean?) = change { it.copy(options = it.options.copy(minify = value, shrinkResources = if (value == false) null else it.options.shrinkResources)) }
    fun setShrink(value: Boolean?) = change { it.copy(options = it.options.copy(shrinkResources = value)) }
    fun setSigningProfile(id: String?) = change { it.copy(signingProfileId = id) }

    fun onApplicationId(text: String) {
        _state.update { it.copy(applicationId = text) }
        change { it.copy(applicationId = text.trim().ifEmpty { null }) }
    }

    fun onVersionName(text: String) {
        _state.update { it.copy(versionName = text) }
        change { it.copy(versionName = text.trim().ifEmpty { null }) }
    }

    fun onVersionCode(text: String) {
        _state.update { it.copy(versionCode = text) }
        change { it.copy(versionCode = text.trim().toIntOrNull()) }
    }

    fun onMinSdk(text: String) {
        _state.update { it.copy(minSdk = text) }
        change { it.copy(minSdk = text.trim().toIntOrNull()) }
    }

    fun onTargetSdk(text: String) {
        _state.update { it.copy(targetSdk = text) }
        change { it.copy(targetSdk = text.trim().toIntOrNull()) }
    }

    private fun change(edit: (BuildConfiguration) -> BuildConfiguration) {
        val s = _state.value
        val updated = edit(s.config)
        val errors = validateFields(updated, s)
        _state.update { it.copy(config = updated, errors = errors, saved = errors.isEmpty()) }
        // Only a valid configuration is ever stored, so a build can never start with a half-typed value.
        if (errors.isEmpty()) store.save(projectId, updated)
    }

    private fun validateFields(c: BuildConfiguration, s: BuildConfigUiState): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        BuildConfigurationValidator.validateApplicationId(c.applicationId)?.let { out["applicationId"] = it }
        BuildConfigurationValidator.validateVersionName(c.versionName)?.let { out["versionName"] = it }
        if (s.versionCode.isNotBlank() && c.versionCode == null) out["versionCode"] = "Version code must be a whole number."
        BuildConfigurationValidator.validateVersionCode(c.versionCode)?.let { out["versionCode"] = it }
        if (s.minSdk.isNotBlank() && c.minSdk == null) out["minSdk"] = "Minimum SDK must be a number."
        BuildConfigurationValidator.validateMinSdk(c.minSdk)?.let { out["minSdk"] = it }
        if (s.targetSdk.isNotBlank() && c.targetSdk == null) out["targetSdk"] = "Target SDK must be a number."
        BuildConfigurationValidator.validateTargetSdk(c.targetSdk, c.minSdk)?.let { out["targetSdk"] = it }
        if (c.options.shrinkResources == true && c.options.minify == false) out["options"] = "Shrink resources needs code shrinking (minify) to be on."
        return out
    }

    fun resetAll() {
        store.clear(projectId)
        reload()
    }
}
