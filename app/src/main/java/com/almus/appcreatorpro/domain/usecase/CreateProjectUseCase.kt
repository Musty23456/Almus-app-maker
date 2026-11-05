package com.almus.appcreatorpro.domain.usecase

import com.almus.appcreatorpro.domain.error.ValidationException
import com.almus.appcreatorpro.domain.model.Project
import com.almus.appcreatorpro.domain.model.ProjectType
import com.almus.appcreatorpro.domain.repository.ProjectRepository
import com.almus.appcreatorpro.domain.validation.PackageNameValidator
import com.almus.appcreatorpro.domain.validation.ProjectNameValidator

/** Validates user input, then asks the repository to create the project. */
class CreateProjectUseCase(private val repository: ProjectRepository) {

    suspend operator fun invoke(
        name: String,
        packageName: String,
        description: String = "",
        type: ProjectType = ProjectType.ANDROID_KOTLIN,
    ): Result<Project> {
        return try {
            val existing = repository.getProjects().map { it.name }
            ProjectNameValidator.validate(name, existing).errorMessage?.let { throw ValidationException(it) }
            if (type.requiresPackage) {
                PackageNameValidator.validate(packageName).errorMessage?.let { throw ValidationException(it) }
            }
            Result.success(repository.createProject(name.trim(), packageName.trim(), description.trim(), type))
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
