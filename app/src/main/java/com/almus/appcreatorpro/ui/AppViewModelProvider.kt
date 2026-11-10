package com.almus.appcreatorpro.ui

import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.viewModelFactory
import com.almus.appcreatorpro.AlmusApplication
import com.almus.appcreatorpro.di.AppContainer
import com.almus.appcreatorpro.ui.agent.AgentNewProjectViewModel
import com.almus.appcreatorpro.ui.preview.PreviewViewModel
import com.almus.appcreatorpro.ui.agent.AgentViewModel
import com.almus.appcreatorpro.ui.ai.AiAssistantViewModel
import com.almus.appcreatorpro.ui.ai.AiGeneratorViewModel
import com.almus.appcreatorpro.ui.ai.AiSettingsViewModel
import com.almus.appcreatorpro.ui.artifacts.ArtifactsViewModel
import com.almus.appcreatorpro.ui.buildconfig.BuildConfigViewModel
import com.almus.appcreatorpro.ui.signing.SigningViewModel
import com.almus.appcreatorpro.ui.builds.BuildHistoryViewModel
import com.almus.appcreatorpro.ui.builds.BuildStatusViewModel
import com.almus.appcreatorpro.ui.cloudbuild.CloudBuildViewModel
import com.almus.appcreatorpro.ui.dashboard.BuildDashboardViewModel
import com.almus.appcreatorpro.ui.github.GitHubConnectionViewModel
import com.almus.appcreatorpro.ui.github.RepositorySelectorViewModel
import com.almus.appcreatorpro.ui.editor.EditorViewModel
import com.almus.appcreatorpro.ui.home.HomeViewModel
import com.almus.appcreatorpro.ui.projects.CreateProjectViewModel
import com.almus.appcreatorpro.ui.projects.ProjectsViewModel
import com.almus.appcreatorpro.ui.settings.SettingsViewModel
import com.almus.appcreatorpro.ui.transfer.BackupsViewModel
import com.almus.appcreatorpro.ui.transfer.ImportViewModel
import com.almus.appcreatorpro.ui.transfer.ProjectTransferViewModel

/** One factory that knows how to build every ViewModel from the [AppContainer]. */
object AppViewModelProvider {
    val Factory = viewModelFactory {
        initializer {
            HomeViewModel(
                projectRepository = container().projectRepository,
                buildRepository = container().buildRepository,
                gitHubRepository = container().gitHubRepository,
                storageInfoProvider = container().storageInfoProvider,
                buildService = container().buildService,
            )
        }
        initializer {
            ProjectsViewModel(
                projectRepository = container().projectRepository,
                recents = container().recentProjects,
            )
        }
        initializer {
            ImportViewModel(
                service = container().importService,
                documents = container().documentAccess,
                projects = container().projectRepository,
                github = container().gitHubSource,
            )
        }
        initializer {
            ProjectTransferViewModel(
                archiver = container().projectArchiver,
                documents = container().documentAccess,
                backups = container().backupStore,
            )
        }
        initializer {
            BuildHistoryViewModel(
                buildRepository = container().buildRepository,
                downloads = container().apkDownloads,
                apkStore = container().apkStore,
            )
        }
        initializer {
            SettingsViewModel(
                settingsRepository = container().settingsRepository,
                notificationController = container().notificationController,
            )
        }
        initializer {
            BuildDashboardViewModel(
                builds = container().buildStore,
                follower = container().buildTracker,
                buildService = container().buildService,
                refresher = container().buildMonitor,
                notificationController = container().notificationController,
                apkStore = container().apkStore,
            )
        }
        initializer { SigningViewModel(manager = container().signingManager) }
        initializer {
            ArtifactsViewModel(
                builds = container().buildStore,
                store = container().apkStore,
                inspector = container().artifactInspector,
            )
        }
        initializer { GitHubConnectionViewModel(auth = container().gitHubAuth) }
        initializer { AiSettingsViewModel(settings = container().aiSettings, backend = container().aiBackend) }
        initializer { AiGeneratorViewModel(hub = container().aiHub) }
        initializer { AgentNewProjectViewModel(hub = container().aiHub) }
        initializer {
            CreateProjectViewModel(
                createProject = container().createProjectUseCase,
                projectRepository = container().projectRepository,
            )
        }
    }

    /** [projectId] null = backups of all projects. */
    fun backupsFactory(projectId: String?): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            BackupsViewModel(
                projectId = projectId,
                backups = container().backupStore,
                projects = container().projectRepository,
            )
        }
    }

    fun repositorySelectorFactory(projectId: String): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            RepositorySelectorViewModel(
                projectId = projectId,
                auth = container().gitHubAuth,
                configs = container().cloudBuildConfigStore,
                projects = container().projectRepository,
            )
        }
    }

    fun cloudBuildFactory(projectId: String): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            CloudBuildViewModel(
                projectId = projectId,
                projects = container().projectRepository,
                configs = container().cloudBuildConfigStore,
                auth = container().gitHubAuth,
                orchestrator = container().orchestrator,
                tracker = container().buildTracker,
                workflowTemplate = container().workflowTemplate,
                buildConfigs = container().buildConfigurationStore,
            )
        }
    }

    fun buildConfigFactory(projectId: String): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            BuildConfigViewModel(
                projectId = projectId,
                store = container().buildConfigurationStore,
                signing = container().signingManager,
            )
        }
    }

    fun buildStatusFactory(buildId: String): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            BuildStatusViewModel(
                buildId = buildId,
                builds = container().buildStore,
                tracker = container().buildTracker,
                detailsService = container().buildDetailsService,
                downloads = container().apkDownloads,
                apkStore = container().apkStore,
                buildService = container().buildService,
                auth = container().gitHubAuth,
            )
        }
    }

    fun debugFactory(projectId: String, buildId: String?): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            com.almus.appcreatorpro.ui.debug.DebugViewModel(
                projectId = projectId,
                buildId = buildId,
                hub = container().debugHub,
            )
        }
    }

    fun aiAssistantFactory(projectId: String): ViewModelProvider.Factory = viewModelFactory {
        initializer { AiAssistantViewModel(projectId = projectId, hub = container().aiHub) }
    }

    fun previewFactory(projectId: String): ViewModelProvider.Factory = viewModelFactory {
        initializer { PreviewViewModel(projectId = projectId, hub = container().previewHub) }
    }

    fun agentFactory(projectId: String): ViewModelProvider.Factory = viewModelFactory {
        initializer { AgentViewModel(projectId = projectId, hub = container().aiHub) }
    }

    /** The editor needs to know which project it edits, so it gets its own factory. */
    fun editorFactory(projectId: String): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            EditorViewModel(
                projectId = projectId,
                projects = container().projectRepository,
                files = container().sourceFileRepository,
            )
        }
    }
}

private fun CreationExtras.container(): AppContainer =
    (this[APPLICATION_KEY] as AlmusApplication).container
