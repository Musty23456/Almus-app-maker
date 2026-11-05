package com.almus.appcreatorpro.ui.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.almus.appcreatorpro.domain.error.toUserMessage
import com.almus.appcreatorpro.domain.model.Project
import com.almus.appcreatorpro.domain.model.ProjectType
import com.almus.appcreatorpro.domain.repository.ProjectRepository
import com.almus.appcreatorpro.domain.usecase.CreateProjectUseCase
import com.almus.appcreatorpro.domain.validation.PackageNameSuggester
import com.almus.appcreatorpro.domain.validation.PackageNameValidator
import com.almus.appcreatorpro.domain.validation.ValidationResult
import com.almus.appcreatorpro.domain.validation.ProjectNameValidator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CreateProjectFormState(
    val name: String = "",
    val packageName: String = "",
    val type: ProjectType = ProjectType.ANDROID_KOTLIN,
    val description: String = "",
    val packageEdited: Boolean = false,
    val nameError: String? = null,
    val packageError: String? = null,
    val isSubmitting: Boolean = false,
    val submitError: String? = null,
)

class CreateProjectViewModel(
    private val createProject: CreateProjectUseCase,
    private val projectRepository: ProjectRepository,
) : ViewModel() {

    private val _form = MutableStateFlow(CreateProjectFormState())
    val form: StateFlow<CreateProjectFormState> = _form.asStateFlow()

    @Volatile
    private var existingNames: List<String> = emptyList()

    init {
        viewModelScope.launch {
            projectRepository.observeProjects().collect { projects ->
                existingNames = projects.map { it.name }
            }
        }
    }

    fun reset() {
        _form.value = CreateProjectFormState()
    }

    fun onNameChange(value: String) {
        _form.update { state ->
            val packageName = when {
                !state.type.requiresPackage -> ""
                state.packageEdited -> state.packageName
                else -> PackageNameSuggester.suggest(value)
            }
            state.copy(
                name = value,
                packageName = packageName,
                nameError = liveNameError(value),
                packageError = if (state.type.requiresPackage) livePackageError(packageName) else null,
                submitError = null,
            )
        }
    }

    fun onTypeChange(type: ProjectType) {
        _form.update { state ->
            val packageName = when {
                !type.requiresPackage -> ""
                state.packageEdited || state.packageName.isNotBlank() -> state.packageName
                else -> PackageNameSuggester.suggest(state.name)
            }
            state.copy(
                type = type,
                packageName = packageName,
                packageError = if (type.requiresPackage) livePackageError(packageName) else null,
                submitError = null,
            )
        }
    }

    fun onPackageChange(value: String) {
        _form.update { state ->
            state.copy(
                packageName = value,
                packageEdited = true,
                packageError = livePackageError(value),
                submitError = null,
            )
        }
    }

    fun onDescriptionChange(value: String) {
        _form.update { it.copy(description = value.take(MAX_DESCRIPTION), submitError = null) }
    }

    /** Validates the form and creates the project; calls [onSuccess] only when it worked. */
    fun submit(onSuccess: (Project) -> Unit) {
        val current = _form.value
        if (current.isSubmitting) return

        val nameResult = ProjectNameValidator.validate(current.name, existingNames)
        val packageResult = if (current.type.requiresPackage) {
            PackageNameValidator.validate(current.packageName)
        } else {
            ValidationResult.Valid
        }
        if (!nameResult.isValid || !packageResult.isValid) {
            _form.update { it.copy(nameError = nameResult.errorMessage, packageError = packageResult.errorMessage) }
            return
        }

        _form.update { it.copy(isSubmitting = true, submitError = null) }
        viewModelScope.launch {
            createProject(current.name, current.packageName, current.description, current.type).fold(
                onSuccess = { project ->
                    _form.update { it.copy(isSubmitting = false) }
                    onSuccess(project)
                },
                onFailure = { error ->
                    _form.update { it.copy(isSubmitting = false, submitError = error.toUserMessage()) }
                },
            )
        }
    }

    private fun liveNameError(value: String): String? =
        if (value.isBlank()) null else ProjectNameValidator.validate(value, existingNames).errorMessage

    private fun livePackageError(value: String): String? =
        if (value.isBlank()) null else PackageNameValidator.validate(value).errorMessage

    private companion object {
        const val MAX_DESCRIPTION = 120
    }
}
