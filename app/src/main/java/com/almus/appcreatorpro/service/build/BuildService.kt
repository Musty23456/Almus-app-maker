package com.almus.appcreatorpro.service.build

import com.almus.appcreatorpro.data.repository.BuildConfigurationStore
import com.almus.appcreatorpro.data.repository.CloudBuildConfigStore
import com.almus.appcreatorpro.domain.build.BuildConfiguration
import com.almus.appcreatorpro.domain.error.FeatureNotImplementedException
import com.almus.appcreatorpro.domain.github.CloudBuildConfig
import com.almus.appcreatorpro.domain.github.GitHubException
import com.almus.appcreatorpro.domain.model.BuildRecord
import com.almus.appcreatorpro.domain.model.Project
import com.almus.appcreatorpro.domain.repository.ProjectRepository
import com.almus.appcreatorpro.service.github.GitHubAuthManager
import com.almus.appcreatorpro.service.github.GitHubAuthState
import kotlinx.coroutines.CancellationException

/** What the home screen, the dashboard and the status screen need to start, cancel and repeat builds. */
interface BuildService {
    val displayName: String
    val isAvailable: Boolean

    suspend fun requestBuild(project: Project): Result<BuildRecord>

    /** Asks the build system to cancel [record]. The message is shown to the user. */
    suspend fun cancelBuild(record: BuildRecord): Result<String> =
        Result.failure(FeatureNotImplementedException("Cancelling a build"))

    /** Builds the project again with its current files and the same settings. */
    suspend fun retryBuild(record: BuildRecord): Result<BuildRecord> =
        Result.failure(FeatureNotImplementedException("Retrying a build"))
}

/** Phase 1/2 placeholder: honest about not being able to build. */
class PrototypeCloudBuildService : BuildService {
    override val displayName: String = "Cloud build (GitHub Actions)"
    override val isAvailable: Boolean = false

    override suspend fun requestBuild(project: Project): Result<BuildRecord> =
        Result.failure(FeatureNotImplementedException("Cloud build"))
}

class GitHubActionsBuildService(
    private val auth: GitHubAuthManager,
    private val configs: CloudBuildConfigStore,
    private val tracker: BuildTracker,
    private val projects: ProjectRepository,
    private val buildConfigs: BuildConfigurationStore? = null,
) : BuildService {

    override val displayName: String = "GitHub Actions"

    override val isAvailable: Boolean get() = auth.state.value is GitHubAuthState.Connected

    override suspend fun requestBuild(project: Project): Result<BuildRecord> {
        if (auth.state.value !is GitHubAuthState.Connected) {
            return Result.failure(IllegalStateException("Connect GitHub first (Settings > GitHub)."))
        }
        val config = configs.get(project.id)
            ?: return Result.failure(IllegalStateException("Choose a GitHub repository for this project first (open the project > Cloud build)."))
        val buildConfig = buildConfigs?.get(project.id) ?: BuildConfiguration.DEFAULT
        return start(project, config.copy(projectDir = config.projectDir.ifBlank { CloudBuildConfig.defaultDir(project.name) }), buildConfig)
    }

    override suspend fun retryBuild(record: BuildRecord): Result<BuildRecord> {
        val projectId = record.projectId
            ?: return Result.failure(IllegalStateException("This build has no project, so it cannot be built again."))
        val project = try {
            projects.getProject(projectId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return Result.failure(IllegalStateException("This project no longer exists, so it cannot be built again."))

        val saved = configs.get(projectId)
        val repo = record.repoFullName?.split('/', limit = 2)?.takeIf { it.size == 2 && it.all(String::isNotBlank) }
        val config = when {
            repo != null && record.branch != null -> CloudBuildConfig(
                projectId = projectId,
                repoOwner = repo[0],
                repoName = repo[1],
                branch = record.branch,
                projectDir = record.projectDir?.takeIf { it.isNotBlank() } ?: CloudBuildConfig.defaultDir(project.name),
                gradleVersion = saved?.gradleVersion ?: CloudBuildConfig.DEFAULT_GRADLE_VERSION,
            )
            saved != null -> saved
            else -> return Result.failure(IllegalStateException("The repository of this build is unknown. Choose one in Cloud build first."))
        }
        return start(project, config, record.configuration ?: buildConfigs?.get(projectId) ?: BuildConfiguration.DEFAULT)
    }

    override suspend fun cancelBuild(record: BuildRecord): Result<String> = tracker.cancel(record)

    private suspend fun start(project: Project, config: CloudBuildConfig, buildConfig: BuildConfiguration): Result<BuildRecord> = try {
        when (val result = tracker.startBuild(project, config, buildConfig)) {
            is StartResult.Started -> Result.success(result.record)
            is StartResult.Rejected -> Result.failure(IllegalStateException(result.reasons.joinToString("\n")))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: GitHubException) {
        Result.failure(IllegalStateException(e.message))
    } catch (e: Exception) {
        Result.failure(IllegalStateException(e.message ?: "The build could not be started."))
    }
}
