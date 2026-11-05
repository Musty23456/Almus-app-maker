package com.almus.appcreatorpro.service.build

import com.almus.appcreatorpro.data.repository.BuildConfigurationStore
import com.almus.appcreatorpro.data.repository.CloudBuildConfigStore
import com.almus.appcreatorpro.domain.build.BuildConfiguration
import com.almus.appcreatorpro.domain.error.FeatureNotImplementedException
import com.almus.appcreatorpro.domain.github.CloudBuildConfig
import com.almus.appcreatorpro.domain.github.CloudBuildNotReadyException
import com.almus.appcreatorpro.domain.model.BuildRecord
import com.almus.appcreatorpro.domain.model.Project
import com.almus.appcreatorpro.domain.repository.ProjectRepository
import com.almus.appcreatorpro.service.github.GitHubAuthManager
import com.almus.appcreatorpro.service.github.GitHubAuthState
import kotlinx.coroutines.CancellationException

/** Starts, repeats and cancels cloud builds. Every failure is returned inside the [Result] with a message safe to show. */
interface BuildService {
    val displayName: String
    val isAvailable: Boolean

    suspend fun requestBuild(project: Project): Result<BuildRecord>

    /** Builds the project of [record] again with the same settings. */
    suspend fun retryBuild(record: BuildRecord): Result<BuildRecord> =
        Result.failure(IllegalStateException("Building again is not available here."))

    suspend fun cancelBuild(record: BuildRecord): Result<String> =
        Result.failure(IllegalStateException("Cancelling a build is not available here."))
}

/** The placeholder from the early phases: honest about not being connected to anything. */
class PrototypeCloudBuildService : BuildService {
    override val displayName: String = "Cloud build (not connected)"
    override val isAvailable: Boolean = false

    override suspend fun requestBuild(project: Project): Result<BuildRecord> =
        Result.failure(FeatureNotImplementedException("Cloud build"))
}

/** Real builds on GitHub Actions. */
class GitHubActionsBuildService(
    private val auth: GitHubAuthManager,
    private val configs: CloudBuildConfigStore,
    private val tracker: BuildTracker,
    private val projects: ProjectRepository,
    private val buildConfigurations: BuildConfigurationStore? = null,
) : BuildService {

    override val displayName: String = "GitHub Actions"

    override val isAvailable: Boolean
        get() = auth.state.value is GitHubAuthState.Connected

    override suspend fun requestBuild(project: Project): Result<BuildRecord> {
        if (!isAvailable) return Result.failure(CloudBuildNotReadyException("Connect GitHub first (Settings, GitHub)."))
        val config = configs.get(project.id)
            ?: return Result.failure(CloudBuildNotReadyException("Choose a GitHub repository for this project first."))
        val buildConfig = buildConfigurations?.get(project.id) ?: BuildConfiguration.DEFAULT
        return start(project, config, buildConfig)
    }

    override suspend fun retryBuild(record: BuildRecord): Result<BuildRecord> {
        if (!isAvailable) return Result.failure(CloudBuildNotReadyException("Connect GitHub first (Settings, GitHub)."))
        val projectId = record.projectId
        val project = projectId?.let { id ->
            try {
                projects.getProject(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        } ?: return Result.failure(CloudBuildNotReadyException("The project of this build no longer exists."))
        val parts = record.repoFullName?.split('/').orEmpty()
        if (parts.size != 2 || record.branch.isNullOrBlank()) {
            return Result.failure(CloudBuildNotReadyException("This build has no repository to build in."))
        }
        val config = CloudBuildConfig(
            projectId = project.id,
            repoOwner = parts[0],
            repoName = parts[1],
            branch = record.branch,
            projectDir = record.projectDir.orEmpty(),
            gradleVersion = configs.get(project.id)?.gradleVersion ?: CloudBuildConfig.DEFAULT_GRADLE_VERSION,
        )
        return start(project, config, record.configuration ?: BuildConfiguration.DEFAULT)
    }

    override suspend fun cancelBuild(record: BuildRecord): Result<String> = tracker.cancel(record)

    private suspend fun start(project: Project, config: CloudBuildConfig, buildConfig: BuildConfiguration): Result<BuildRecord> =
        try {
            when (val result = tracker.startBuild(project, config, buildConfig)) {
                is StartResult.Started -> Result.success(result.record)
                is StartResult.Rejected -> Result.failure(CloudBuildNotReadyException(result.reasons.joinToString(" ")))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
}
