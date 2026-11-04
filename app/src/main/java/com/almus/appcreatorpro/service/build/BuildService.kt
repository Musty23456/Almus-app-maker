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

/** Something that can build a project in the cloud. Failures are returned inside the [Result], never thrown. */
interface BuildService {
    val displayName: String
    val isAvailable: Boolean

    suspend fun requestBuild(project: Project): Result<BuildRecord>

    /** Builds the project of [record] again with its current files. */
    suspend fun retryBuild(record: BuildRecord): Result<BuildRecord> =
        Result.failure(FeatureNotImplementedException("Retrying a build"))

    /** Asks GitHub to cancel the run of [record]. On success the text says what happened. */
    suspend fun cancelBuild(record: BuildRecord): Result<String> =
        Result.failure(FeatureNotImplementedException("Cancelling a build"))
}

/** The Phase 1 placeholder: it is honest about not being able to build anything. */
class PrototypeCloudBuildService : BuildService {
    override val displayName: String = "Cloud build (prototype)"
    override val isAvailable: Boolean = false

    override suspend fun requestBuild(project: Project): Result<BuildRecord> =
        Result.failure(FeatureNotImplementedException("Cloud build"))
}

/** Builds projects with GitHub Actions in the user's own repository. */
class GitHubActionsBuildService(
    private val auth: GitHubAuthManager,
    private val configs: CloudBuildConfigStore,
    private val tracker: BuildTracker,
    private val projects: ProjectRepository,
    /** Phase 6: the build type and overrides chosen per project. Null means plain debug builds. */
    private val buildConfigs: BuildConfigurationStore? = null,
) : BuildService {

    override val displayName: String = "GitHub Actions"

    override val isAvailable: Boolean get() = auth.state.value is GitHubAuthState.Connected

    override suspend fun requestBuild(project: Project): Result<BuildRecord> = attempt {
        requireConnected()
        val saved = configs.get(project.id)
        val last = configs.last()
        val config = when {
            saved != null -> saved.copy(projectDir = saved.projectDir.ifBlank { CloudBuildConfig.defaultDir(project.name) })
            last != null -> last.copy(projectId = project.id, projectDir = CloudBuildConfig.defaultDir(project.name))
            else -> throw CloudBuildNotReadyException(
                "Choose a GitHub repository for \"${project.name}\" first: open the project and use Cloud build.",
            )
        }
        start(project, config, buildConfigs?.get(project.id) ?: BuildConfiguration.DEFAULT)
    }

    override suspend fun retryBuild(record: BuildRecord): Result<BuildRecord> = attempt {
        val projectId = record.projectId
            ?: throw CloudBuildNotReadyException("This build has no project, so it cannot be built again.")
        val project = projects.getProject(projectId)
            ?: throw CloudBuildNotReadyException("The project of this build no longer exists, so it cannot be built again.")
        val coordinates = splitRepo(record.repoFullName)
        val branch = record.branch
        val directory = record.projectDir
        if (coordinates == null || branch.isNullOrBlank() || directory.isNullOrBlank()) {
            throw CloudBuildNotReadyException("This build has no repository information, so it cannot be built again.")
        }
        requireConnected()
        val gradle = configs.get(projectId)?.gradleVersion ?: CloudBuildConfig.DEFAULT_GRADLE_VERSION
        start(
            project,
            CloudBuildConfig(
                projectId = projectId,
                repoOwner = coordinates.first,
                repoName = coordinates.second,
                branch = branch,
                projectDir = directory,
                gradleVersion = gradle,
            ),
            // The same settings as the first attempt: what was asked for is kept on the record.
            record.configuration ?: BuildConfiguration.DEFAULT,
        )
    }

    override suspend fun cancelBuild(record: BuildRecord): Result<String> = attempt {
        requireConnected()
        tracker.cancel(record)
    }

    private suspend fun requireConnected() {
        auth.ensureVerified()
        when (auth.state.value) {
            is GitHubAuthState.Connected -> Unit
            is GitHubAuthState.Expired ->
                throw CloudBuildNotReadyException("Your GitHub sign-in has expired. Reconnect GitHub first.")
            else -> throw CloudBuildNotReadyException("Connect your GitHub account first.")
        }
    }

    private suspend fun start(project: Project, config: CloudBuildConfig, buildConfig: BuildConfiguration): BuildRecord =
        when (val result = tracker.startBuild(project, config, buildConfig)) {
            is StartResult.Started -> result.record
            is StartResult.Rejected -> throw CloudBuildNotReadyException(
                result.reasons.take(3).joinToString(" ").ifBlank { "The build cannot start yet." },
            )
        }

    private inline fun <T> attempt(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
}
