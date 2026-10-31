package com.almus.appcreatorpro.di

import android.content.Context
import com.almus.appcreatorpro.data.repository.CloudBuildConfigStore
import com.almus.appcreatorpro.data.repository.FileBuildStore
import com.almus.appcreatorpro.data.repository.SharedPreferencesSettingsRepository
import com.almus.appcreatorpro.data.storage.FileProjectStore
import com.almus.appcreatorpro.data.storage.SharedPreferencesKeyValueStore
import com.almus.appcreatorpro.domain.repository.BuildRepository
import com.almus.appcreatorpro.domain.repository.BuildStore
import com.almus.appcreatorpro.domain.repository.GitHubRepository
import com.almus.appcreatorpro.domain.repository.ProjectRepository
import com.almus.appcreatorpro.domain.repository.SettingsRepository
import com.almus.appcreatorpro.domain.repository.SourceFileRepository
import com.almus.appcreatorpro.domain.repository.StorageInfoProvider
import com.almus.appcreatorpro.domain.usecase.CreateProjectUseCase
import com.almus.appcreatorpro.service.build.BuildService
import com.almus.appcreatorpro.service.build.AssetWorkflowTemplateProvider
import com.almus.appcreatorpro.service.artifacts.ApkDownloadManager
import com.almus.appcreatorpro.service.artifacts.ApkStore
import com.almus.appcreatorpro.service.artifacts.ArtifactDownloader
import com.almus.appcreatorpro.service.build.BuildDetailsService
import com.almus.appcreatorpro.service.build.BuildTracker
import com.almus.appcreatorpro.service.build.CloudBuildOrchestrator
import com.almus.appcreatorpro.service.build.GitHubActionsBuildService
import com.almus.appcreatorpro.service.build.WorkflowTemplateProvider
import com.almus.appcreatorpro.service.github.AuthBackedGitHubRepository
import com.almus.appcreatorpro.service.github.GitHubAuthManager
import com.almus.appcreatorpro.service.github.KeystoreSecretStore
import com.almus.appcreatorpro.service.github.OkHttpStreamTransport
import com.almus.appcreatorpro.service.github.OkHttpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.almus.appcreatorpro.service.storage.AndroidStorageInfoProvider
import java.io.File

/**
 * Hand-written dependency container (no DI framework, keeps the Phase 1 build simple).
 * Swap implementations here when Phase 2 adds real data sources.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val settingsRepository: SettingsRepository = SharedPreferencesSettingsRepository(
        appContext.getSharedPreferences("almus_settings", Context.MODE_PRIVATE),
    )

    /**
     * Projects live in the app's private storage (Context.filesDir/projects): no storage permission is
     * needed, and the files are removed by Android when the app is uninstalled.
     */
    private val projectStore = FileProjectStore(rootDir = File(appContext.filesDir, "projects"))

    val projectRepository: ProjectRepository = projectStore

    val sourceFileRepository: SourceFileRepository = projectStore

    // ---- Phase 3: GitHub + cloud builds -------------------------------------------------------------------
    // Sign-in details live in private preferences; the access token itself is encrypted with an Android Keystore key.
    private val githubPrefs = SharedPreferencesKeyValueStore(appContext.getSharedPreferences("almus_github", Context.MODE_PRIVATE))

    val gitHubAuth: GitHubAuthManager = GitHubAuthManager(
        meta = githubPrefs,
        secrets = KeystoreSecretStore(SharedPreferencesKeyValueStore(appContext.getSharedPreferences("almus_github_secrets", Context.MODE_PRIVATE))),
        transport = OkHttpTransport(),
    )

    val cloudBuildConfigStore = CloudBuildConfigStore(SharedPreferencesKeyValueStore(appContext.getSharedPreferences("almus_cloud_build", Context.MODE_PRIVATE)))

    val buildStore: BuildStore = FileBuildStore(dir = File(appContext.filesDir, "builds"))

    val buildRepository: BuildRepository = buildStore

    val gitHubRepository: GitHubRepository = AuthBackedGitHubRepository(gitHubAuth)

    val workflowTemplate: WorkflowTemplateProvider = AssetWorkflowTemplateProvider(appContext)

    val orchestrator = CloudBuildOrchestrator(api = gitHubAuth.api, files = sourceFileRepository, builds = buildStore)

    /** App-wide scope: builds keep being tracked while the user moves between screens. */
    val buildTracker = BuildTracker(orchestrator, buildStore, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    val buildService: BuildService = GitHubActionsBuildService(gitHubAuth, cloudBuildConfigStore, buildTracker, projectRepository)

    // ---- Phase 4: build results (details, APK download) ---------------------------------------------------
    val buildDetailsService = BuildDetailsService(api = gitHubAuth.api, builds = buildStore)

    /** Downloaded APKs live in private app storage (filesDir/apks/<buildId>/). */
    val apkStore = ApkStore(root = File(appContext.filesDir, "apks")).also { it.cleanTemp() }

    private val artifactDownloader = ArtifactDownloader(
        transport = OkHttpStreamTransport(),
        tokenProvider = gitHubAuth.tokenProvider,
        onUnauthorized = gitHubAuth::notifyTokenRejected,
    )

    val apkDownloads = ApkDownloadManager(
        downloader = artifactDownloader,
        store = apkStore,
        builds = buildStore,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    val storageInfoProvider: StorageInfoProvider = AndroidStorageInfoProvider(appContext)

    val createProjectUseCase: CreateProjectUseCase = CreateProjectUseCase(projectRepository)

}
