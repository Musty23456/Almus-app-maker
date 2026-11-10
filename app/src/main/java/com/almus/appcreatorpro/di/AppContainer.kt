package com.almus.appcreatorpro.di

import android.content.Context
import com.almus.appcreatorpro.data.repository.BuildConfigurationStore
import com.almus.appcreatorpro.data.repository.CloudBuildConfigStore
import com.almus.appcreatorpro.service.artifacts.AndroidArtifactInspector
import com.almus.appcreatorpro.service.artifacts.ArtifactInspector
import com.almus.appcreatorpro.service.signing.SigningManager
import com.almus.appcreatorpro.service.signing.SigningStore
import com.almus.appcreatorpro.data.repository.FileBuildStore
import com.almus.appcreatorpro.data.repository.SharedPreferencesSettingsRepository
import com.almus.appcreatorpro.data.storage.FileProjectStore
import com.almus.appcreatorpro.data.storage.RecentProjectsStore
import com.almus.appcreatorpro.data.transfer.BackupStore
import com.almus.appcreatorpro.data.transfer.ProjectArchiver
import com.almus.appcreatorpro.data.transfer.ProjectImportService
import com.almus.appcreatorpro.domain.repository.ProjectTransferRepository
import com.almus.appcreatorpro.domain.repository.RecentProjectsTracker
import com.almus.appcreatorpro.service.transfer.AndroidDocumentAccess
import com.almus.appcreatorpro.service.transfer.AuthBackedGitHubSource
import com.almus.appcreatorpro.service.transfer.DocumentAccess
import com.almus.appcreatorpro.service.transfer.GitHubRepoDownloader
import com.almus.appcreatorpro.service.transfer.GitHubSource
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
import com.almus.appcreatorpro.service.debug.DebugHub
import com.almus.appcreatorpro.service.build.BuildTracker
import com.almus.appcreatorpro.service.build.CloudBuildOrchestrator
import com.almus.appcreatorpro.service.build.GitHubActionsBuildService
import com.almus.appcreatorpro.service.build.WorkflowTemplateProvider
import com.almus.appcreatorpro.service.monitor.AndroidNotificationSink
import com.almus.appcreatorpro.service.monitor.BuildMonitor
import com.almus.appcreatorpro.service.monitor.BuildNotificationCoordinator
import com.almus.appcreatorpro.service.monitor.BuildSyncEngine
import com.almus.appcreatorpro.service.monitor.BuildSyncStateStore
import com.almus.appcreatorpro.service.monitor.MonitorSettingsStore
import com.almus.appcreatorpro.service.monitor.NotificationController
import com.almus.appcreatorpro.service.monitor.WorkManagerBuildSyncScheduler
import com.almus.appcreatorpro.service.github.AuthBackedGitHubRepository
import com.almus.appcreatorpro.service.github.GitHubAuthManager
import com.almus.appcreatorpro.service.github.KeystoreSecretStore
import com.almus.appcreatorpro.service.github.OkHttpStreamTransport
import com.almus.appcreatorpro.service.github.OkHttpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.almus.appcreatorpro.service.storage.AndroidStorageInfoProvider
import com.almus.appcreatorpro.ai.AiBackend
import com.almus.appcreatorpro.ai.AiSettingsStore
import com.almus.appcreatorpro.ai.GeminiDebugProvider
import com.almus.appcreatorpro.service.ai.AiHub
import com.almus.appcreatorpro.service.preview.PreviewHub
import com.almus.appcreatorpro.service.ai.FileAgentRunStore
import com.almus.appcreatorpro.service.ai.FileChatHistoryStore
import com.almus.appcreatorpro.service.ai.FileChangeHistoryStore
import com.almus.appcreatorpro.service.ai.GeminiBackendClient
import com.almus.appcreatorpro.service.ai.OkHttpPostStreamTransport
import java.util.concurrent.TimeUnit
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

    // ---- Phase 6: build configuration and release signing ------------------------------------------------------
    val buildConfigurationStore = BuildConfigurationStore(
        SharedPreferencesKeyValueStore(appContext.getSharedPreferences("almus_build_config", Context.MODE_PRIVATE)),
    )

    /**
     * Signing keys: descriptions in plain preferences, the keystore and passwords AES-256-GCM encrypted with a key that
     * lives in the Android Keystore (a different key than the GitHub token's). allowBackup is false, so none of it is
     * copied to cloud backups.
     */
    val signingManager = SigningManager(
        SigningStore(
            meta = SharedPreferencesKeyValueStore(appContext.getSharedPreferences("almus_signing_meta", Context.MODE_PRIVATE)),
            secrets = KeystoreSecretStore(
                SharedPreferencesKeyValueStore(appContext.getSharedPreferences("almus_signing_secrets", Context.MODE_PRIVATE)),
                keyAlias = "almus_signing_key",
            ),
        ),
    )

    val artifactInspector: ArtifactInspector = AndroidArtifactInspector(appContext)

    val orchestrator = CloudBuildOrchestrator(
        api = gitHubAuth.api,
        files = sourceFileRepository,
        builds = buildStore,
        signing = signingManager,
    )

    /** App-wide scope: builds keep being tracked while the user moves between screens. */
    val buildTracker = BuildTracker(orchestrator, buildStore, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    val buildService: BuildService = GitHubActionsBuildService(gitHubAuth, cloudBuildConfigStore, buildTracker, projectRepository, buildConfigurationStore)

    // ---- Phase 7: background monitoring (WorkManager), notifications, recovery ---------------------------------
    // WorkManager only schedules status checks; the build itself runs on GitHub Actions.
    val monitorSettings = MonitorSettingsStore(
        SharedPreferencesKeyValueStore(appContext.getSharedPreferences("almus_monitor", Context.MODE_PRIVATE)),
    )

    private val notificationSink = AndroidNotificationSink(appContext)

    val notificationController = NotificationController(monitorSettings, notificationSink)

    private val syncState = BuildSyncStateStore(
        SharedPreferencesKeyValueStore(appContext.getSharedPreferences("almus_monitor_state", Context.MODE_PRIVATE)),
    )

    private val buildNotifications = BuildNotificationCoordinator(monitorSettings, notificationSink, syncState)

    private val syncEngine = BuildSyncEngine(
        store = buildStore,
        orchestrator = orchestrator,
        notifications = buildNotifications,
        state = syncState,
        followedInApp = { buildTracker.activeBuildIds.value },
    )

    val buildMonitor = BuildMonitor(
        store = buildStore,
        engine = syncEngine,
        notifications = buildNotifications,
        settings = monitorSettings,
        sink = notificationSink,
        scheduler = WorkManagerBuildSyncScheduler(appContext),
        follower = buildTracker,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

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

    // ---- Phase 9: Gemini through the ALMUS backend -------------------------------------------------------------------
    // The app holds NO Gemini API key. It stores the backend address (plain) and the user's access code (encrypted with an
    // Android Keystore key) and talks only to the backend, which holds the key.
    val aiSettings = AiSettingsStore(
        meta = SharedPreferencesKeyValueStore(appContext.getSharedPreferences("almus_ai", Context.MODE_PRIVATE)),
        secrets = KeystoreSecretStore(
            SharedPreferencesKeyValueStore(appContext.getSharedPreferences("almus_ai_secrets", Context.MODE_PRIVATE)),
            keyAlias = "almus_ai_access_key",
        ),
    )

    val aiBackend: AiBackend = GeminiBackendClient(
        settings = aiSettings,
        // Answers can take a while (the backend waits for Gemini), so reads get a longer timeout than the other services.
        http = OkHttpTransport(OkHttpTransport.defaultClient().newBuilder().readTimeout(130, TimeUnit.SECONDS).build()),
        streams = OkHttpPostStreamTransport(),
    )

    private val chatHistoryStore = FileChatHistoryStore(File(appContext.filesDir, "ai-chat"))
    private val changeHistoryStore = FileChangeHistoryStore(File(appContext.filesDir, "ai-changes"))

    private val agentRunStore = FileAgentRunStore(File(appContext.filesDir, "ai-agent"))

    val aiHub = AiHub(
        backend = aiBackend,
        sourceFiles = sourceFileRepository,
        projects = projectRepository,
        chatHistory = chatHistoryStore,
        changeHistory = changeHistoryStore,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        agentStore = agentRunStore,
    )

    /** Phase 20: live previews (read-only, sandboxed) and AI-generated UI drafts. */
    val previewHub = PreviewHub(aiHub, projectRepository, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))

    // ---- Phase 8: debugging (build error analysis, code checks, fixes) ---------------------------------------------
    /** One debug session per project/build, kept while the app runs. Phase 9 connects Gemini as its AI provider. */
    val debugHub = DebugHub(
        sourceFiles = sourceFileRepository, projects = projectRepository, details = buildDetailsService,
        ai = GeminiDebugProvider(aiBackend),
    )

    val storageInfoProvider: StorageInfoProvider = AndroidStorageInfoProvider(appContext)

    val createProjectUseCase: CreateProjectUseCase = CreateProjectUseCase(projectRepository)

    // ---- Phase 5: import, export, backup, recent projects -----------------------------------------------------
    val transferRepository: ProjectTransferRepository = projectStore

    /** Remembers when each project was last opened (private preferences, no personal data). */
    val recentProjects: RecentProjectsTracker =
        RecentProjectsStore(SharedPreferencesKeyValueStore(appContext.getSharedPreferences("almus_recent_projects", Context.MODE_PRIVATE)))

    /** Storage Access Framework: the user picks files/folders in the system picker, so no storage permission is needed. */
    val documentAccess: DocumentAccess = AndroidDocumentAccess(appContext.contentResolver)

    /** Unpacking happens in filesDir/transfer-staging, never inside the projects folder. */
    val importService = ProjectImportService(
        stagingRoot = File(appContext.filesDir, "transfer-staging"),
        projects = projectRepository,
        transfer = transferRepository,
    ).also { it.cleanStaleStaging() }

    val projectArchiver = ProjectArchiver(transferRepository)

    val backupStore = BackupStore(
        rootDir = File(appContext.filesDir, "backups"),
        projects = projectRepository,
        transfer = transferRepository,
        archiver = projectArchiver,
        importer = importService,
    )

    val gitHubSource: GitHubSource = AuthBackedGitHubSource(
        auth = gitHubAuth,
        downloader = GitHubRepoDownloader(
            transport = OkHttpStreamTransport(),
            tokenProvider = gitHubAuth.tokenProvider,
            onUnauthorized = gitHubAuth::notifyTokenRejected,
        ),
    )

}
