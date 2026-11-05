package com.almus.appcreatorpro.ui

import com.almus.appcreatorpro.data.repository.InMemoryProjectRepository
import com.almus.appcreatorpro.data.repository.InMemorySettingsRepository
import com.almus.appcreatorpro.data.repository.PrototypeGitHubRepository
import com.almus.appcreatorpro.data.repository.SampleBuildRepository
import com.almus.appcreatorpro.domain.model.GitHubConnection
import com.almus.appcreatorpro.domain.model.Project
import com.almus.appcreatorpro.domain.model.ThemeMode
import com.almus.appcreatorpro.domain.usecase.CreateProjectUseCase
import com.almus.appcreatorpro.service.build.PrototypeCloudBuildService
import com.almus.appcreatorpro.testutil.FailingProjectRepository
import com.almus.appcreatorpro.testutil.FakeStorageInfoProvider
import com.almus.appcreatorpro.testutil.MainDispatcherRule
import com.almus.appcreatorpro.testutil.testProject
import com.almus.appcreatorpro.ui.builds.BuildHistoryViewModel
import com.almus.appcreatorpro.ui.home.HomeData
import com.almus.appcreatorpro.ui.home.HomeViewModel
import com.almus.appcreatorpro.ui.projects.CreateProjectViewModel
import com.almus.appcreatorpro.ui.projects.ProjectsViewModel
import com.almus.appcreatorpro.ui.settings.SettingsViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProjectsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun loads_projects_into_success_state() {
        val vm = ProjectsViewModel(InMemoryProjectRepository(listOf(testProject("a"), testProject("b", name = "Other"))))

        val state = vm.state.value
        assertTrue(state is UiState.Success)
        assertEquals(2, (state as UiState.Success).data.size)
    }

    @Test
    fun empty_repository_gives_success_with_empty_list() {
        val vm = ProjectsViewModel(InMemoryProjectRepository())

        val state = vm.state.value
        assertTrue(state is UiState.Success)
        assertTrue((state as UiState.Success).data.isEmpty())
    }

    @Test
    fun failing_repository_gives_error_state_with_friendly_message() {
        val vm = ProjectsViewModel(FailingProjectRepository())

        val state = vm.state.value
        assertTrue(state is UiState.Error)
        assertEquals("Something went wrong. Please try again.", (state as UiState.Error).message)
    }

    @Test
    fun delete_updates_list_and_sets_message() {
        val repo = InMemoryProjectRepository(listOf(testProject("a", name = "Alpha"), testProject("b", name = "Beta")))
        val vm = ProjectsViewModel(repo)

        vm.deleteProject(testProject("a", name = "Alpha"))

        val data = (vm.state.value as UiState.Success<List<Project>>).data
        assertEquals(listOf("b"), data.map { it.id })
        assertEquals("Deleted \"Alpha\".", vm.message.value)
    }

    @Test
    fun consume_message_clears_it() {
        val vm = ProjectsViewModel(InMemoryProjectRepository())
        vm.onProjectCreated(testProject(name = "Fresh"))
        assertNotNull(vm.message.value)
        vm.consumeMessage()
        assertNull(vm.message.value)
    }
}

class HomeViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun viewModel(
        projects: List<Project> = listOf(testProject("a", name = "Alpha")),
    ) = HomeViewModel(
        projectRepository = InMemoryProjectRepository(projects),
        buildRepository = SampleBuildRepository(clock = { 10_000_000L }),
        gitHubRepository = PrototypeGitHubRepository(),
        storageInfoProvider = FakeStorageInfoProvider(),
        buildService = PrototypeCloudBuildService(),
    )

    @Test
    fun success_state_combines_all_sources() {
        val state = viewModel().state.value
        assertTrue(state is UiState.Success)

        val data: HomeData = (state as UiState.Success).data
        assertEquals(1, data.projectCount)
        assertEquals(1, data.recentProjects.size)
        assertEquals(3, data.recentBuilds.size)
        assertEquals(GitHubConnection.NotConnected, data.gitHub)
        assertEquals(1_000L, data.storage.totalBytes)
        assertFalse(data.cloudBuildAvailable)
    }

    @Test
    fun recent_projects_are_limited_to_three() {
        val many = (1..5).map { testProject("p$it", name = "Project $it", updatedAtMillis = it.toLong()) }
        val data = (viewModel(many).state.value as UiState.Success).data

        assertEquals(5, data.projectCount)
        assertEquals(3, data.recentProjects.size)
        assertEquals("p5", data.recentProjects.first().id)
    }

    @Test
    fun start_cloud_build_reports_not_implemented() {
        val vm = viewModel()
        vm.startCloudBuild()
        assertEquals("Cloud build is not implemented yet (planned for Phase 2).", vm.message.value)
    }

    @Test
    fun start_cloud_build_without_projects_asks_to_create_one() {
        val vm = viewModel(projects = emptyList())
        vm.startCloudBuild()
        assertEquals("Create a project first, then start a cloud build.", vm.message.value)
    }

    @Test
    fun failing_project_repository_gives_error_state() {
        val vm = HomeViewModel(
            projectRepository = FailingProjectRepository(),
            buildRepository = SampleBuildRepository(),
            gitHubRepository = PrototypeGitHubRepository(),
            storageInfoProvider = FakeStorageInfoProvider(),
            buildService = PrototypeCloudBuildService(),
        )
        assertTrue(vm.state.value is UiState.Error)
    }
}

class BuildHistoryViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun loads_sample_history() {
        val vm = BuildHistoryViewModel(SampleBuildRepository(clock = { 10_000_000L }))
        val state = vm.state.value
        assertTrue(state is UiState.Success)
        assertEquals(4, (state as UiState.Success).data.size)
    }
}

class CreateProjectViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun viewModel(repo: InMemoryProjectRepository = InMemoryProjectRepository()) =
        CreateProjectViewModel(CreateProjectUseCase(repo), repo)

    @Test
    fun typing_a_name_suggests_a_package() {
        val vm = viewModel()
        vm.onNameChange("Weather Buddy")
        assertEquals("com.example.weatherbuddy", vm.form.value.packageName)
        assertNull(vm.form.value.nameError)
    }

    @Test
    fun manually_edited_package_is_not_overwritten() {
        val vm = viewModel()
        vm.onPackageChange("org.mine.app")
        vm.onNameChange("Another Name")
        assertEquals("org.mine.app", vm.form.value.packageName)
    }

    @Test
    fun invalid_name_shows_live_error() {
        val vm = viewModel()
        vm.onNameChange("ab")
        assertNotNull(vm.form.value.nameError)
    }

    @Test
    fun duplicate_name_shows_live_error() {
        val vm = viewModel(InMemoryProjectRepository(listOf(testProject(name = "Taken Name"))))
        vm.onNameChange("taken name")
        assertEquals("A project with this name already exists.", vm.form.value.nameError)
    }

    @Test
    fun submit_with_empty_form_sets_errors_and_does_not_succeed() {
        val vm = viewModel()
        var created: Project? = null
        vm.submit { created = it }

        assertNull(created)
        assertNotNull(vm.form.value.nameError)
        assertNotNull(vm.form.value.packageError)
    }

    @Test
    fun valid_submit_creates_project_and_calls_back() {
        val repo = InMemoryProjectRepository()
        val vm = viewModel(repo)
        vm.onNameChange("Brand New")

        var created: Project? = null
        vm.submit { created = it }

        assertEquals("Brand New", created?.name)
        assertFalse(vm.form.value.isSubmitting)
    }

    @Test
    fun reset_clears_the_form() {
        val vm = viewModel()
        vm.onNameChange("Something")
        vm.reset()
        assertEquals("", vm.form.value.name)
        assertEquals("", vm.form.value.packageName)
    }
}

class SettingsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun theme_defaults_to_system_and_can_change() {
        val vm = SettingsViewModel(InMemorySettingsRepository())
        assertEquals(ThemeMode.SYSTEM, vm.themeMode.value)

        vm.setThemeMode(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, vm.themeMode.value)
    }

    @Test
    fun show_welcome_again_resets_flag_and_posts_message() {
        val repo = InMemorySettingsRepository(welcomeCompleted = true)
        val vm = SettingsViewModel(repo)

        vm.showWelcomeAgainNextLaunch()

        assertFalse(repo.isWelcomeCompleted())
        assertNotNull(vm.message.value)
        vm.consumeMessage()
        assertNull(vm.message.value)
    }
}
