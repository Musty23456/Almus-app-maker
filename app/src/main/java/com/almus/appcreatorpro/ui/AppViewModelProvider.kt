package com.almus.appcreatorpro.ui

import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.viewModelFactory
import com.almus.appcreatorpro.AlmusApplication
import com.almus.appcreatorpro.di.AppContainer
import com.almus.appcreatorpro.ui.builds.BuildHistoryViewModel
import com.almus.appcreatorpro.ui.builds.BuildStatusViewModel
import com.almus.appcreatorpro.ui.cloudbuild.CloudBuildViewModel
import com.almus.appcreatorpro.ui.github.GitHubConnectionViewModel
import com.almus.appcreatorpro.ui.github.RepositorySelectorViewModel
import com.almus.appcreatorpro.ui.editor.EditorViewModel
import com.almus.appcreatorpro.ui.home.HomeViewModel
import com.almus.appcreatorpro.ui.projects.CreateProjectViewModel
import com.almus.appcreatorpro.ui.projects.ProjectsViewModel
import com.almus.appcreatorpro.ui.settings.SettingsViewModel

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
        initializer { ProjectsViewModel(projectRepository = container().projectRepository) }
        initializer {
            BuildHistoryViewModel(
                buildRepository = container().buildRepository,
                downloads = container().apkDownloads,
                apkStore = container().apkStore,
            )
        }
        initializer { SettingsViewModel(settingsRepository = container().settingsRepository) }
        initializer { GitHubConnectionViewModel(auth = container().gitHubAuth) }
        initializer {
            CreateProjectViewModel(
                createProject = container().createProjectUseCase,
                projectRepository = container().projectRepository,
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
