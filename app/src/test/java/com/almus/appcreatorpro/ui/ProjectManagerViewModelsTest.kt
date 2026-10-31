package com.almus.appcreatorpro.ui

import com.almus.appcreatorpro.data.repository.InMemoryProjectRepository
import com.almus.appcreatorpro.data.storage.FileProjectStore
import com.almus.appcreatorpro.domain.model.Project
import com.almus.appcreatorpro.domain.model.ProjectType
import com.almus.appcreatorpro.domain.usecase.CreateProjectUseCase
import com.almus.appcreatorpro.testutil.MainDispatcherRule
import com.almus.appcreatorpro.testutil.testProject
import com.almus.appcreatorpro.ui.projects.CreateProjectViewModel
import com.almus.appcreatorpro.ui.projects.ProjectsViewModel
import com.almus.appcreatorpro.util.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneOffset
import java.util.Locale

class CreateProjectTypesViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun viewModel(repo: InMemoryProjectRepository = InMemoryProjectRepository()) =
        CreateProjectViewModel(CreateProjectUseCase(repo), repo)

    @Test
    fun default_type_is_kotlin_android() {
        assertEquals(ProjectType.ANDROID_KOTLIN, viewModel().form.value.type)
    }

    @Test
    fun web_projects_need_no_package_and_can_be_created() {
        val repo = InMemoryProjectRepository()
        val vm = viewModel(repo)
        vm.onTypeChange(ProjectType.WEB)
        vm.onNameChange("My Site")
        assertEquals("", vm.form.value.packageName)
        assertNull(vm.form.value.packageError)

        var created: Project? = null
        vm.submit { created = it }
        assertEquals(ProjectType.WEB, created?.type)
        assertEquals("", created?.packageName)
    }

    @Test
    fun switching_back_to_android_suggests_a_package() {
        val vm = viewModel()
        vm.onTypeChange(ProjectType.WEB)
        vm.onNameChange("Cool App")
        vm.onTypeChange(ProjectType.ANDROID_JAVA)
        assertEquals("com.example.coolapp", vm.form.value.packageName)
        assertEquals(ProjectType.ANDROID_JAVA, vm.form.value.type)
    }

    @Test
    fun java_project_keeps_its_type_when_created() {
        val vm = viewModel()
        vm.onTypeChange(ProjectType.ANDROID_JAVA)
        vm.onNameChange("Java App")
        var created: Project? = null
        vm.submit { created = it }
        assertEquals(ProjectType.ANDROID_JAVA, created?.type)
    }
}

class ProjectsViewModelRenameTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun rename_updates_the_list_and_reports_success() {
        val repo = InMemoryProjectRepository(listOf(testProject("a", name = "Old Name")))
        val vm = ProjectsViewModel(repo)
        vm.renameProject(testProject("a", name = "Old Name"), "New Name")

        val data = (vm.state.value as UiState.Success<List<Project>>).data
        assertEquals("New Name", data.single().name)
        assertEquals("Renamed to \"New Name\".", vm.message.value)
    }

    @Test
    fun invalid_or_duplicate_rename_reports_the_reason_and_changes_nothing() {
        val repo = InMemoryProjectRepository(
            listOf(testProject("a", name = "Alpha"), testProject("b", name = "Beta")),
        )
        val vm = ProjectsViewModel(repo)

        vm.renameProject(testProject("a", name = "Alpha"), "beta")
        assertEquals("A project with this name already exists.", vm.message.value)
        vm.renameProject(testProject("a", name = "Alpha"), "")
        assertEquals("Project name is required.", vm.message.value)

        val names = (vm.state.value as UiState.Success<List<Project>>).data.map { it.name }.toSet()
        assertEquals(setOf("Alpha", "Beta"), names)
    }

    @Test
    fun delete_with_file_store_removes_the_project_from_disk() = runTest {
        val store = FileProjectStore(File(tmp.root, "projects"), ioDispatcher = Dispatchers.Unconfined)
        val project = store.createProject("To Delete", "", "", ProjectType.WEB)
        val vm = ProjectsViewModel(store)

        vm.deleteProject(project)

        assertTrue((vm.state.value as UiState.Success<List<Project>>).data.isEmpty())
        assertTrue(FileProjectStore(File(tmp.root, "projects"), ioDispatcher = Dispatchers.Unconfined).getProjects().isEmpty())
    }

    @Test
    fun damaged_project_folders_are_surfaced_as_load_issues() {
        val root = File(tmp.root, "projects")
        File(root, "broken").mkdirs()
        val store = FileProjectStore(root, ioDispatcher = Dispatchers.Unconfined)
        val vm = ProjectsViewModel(store)

        assertTrue(vm.state.value is UiState.Success)
        assertEquals(1, vm.loadIssues.value.size)
        assertNotNull(vm.loadIssues.value.first())
    }

    @Test
    fun projects_saved_by_one_run_are_listed_by_the_next_run() = runTest {
        val root = File(tmp.root, "projects")
        FileProjectStore(root, ioDispatcher = Dispatchers.Unconfined)
            .createProject("Survivor", "com.example.survivor", "", ProjectType.ANDROID_KOTLIN)

        val vm = ProjectsViewModel(FileProjectStore(root, ioDispatcher = Dispatchers.Unconfined))
        val data = (vm.state.value as UiState.Success<List<Project>>).data
        assertEquals(listOf("Survivor"), data.map { it.name })
        assertEquals(ProjectType.ANDROID_KOTLIN, data[0].type)
    }
}

class FormatDateTest {
    @Test
    fun formats_day_month_year_in_the_given_zone_and_locale() {
        assertEquals("1 Jan 1970", Formatters.formatDate(0L, ZoneOffset.UTC, Locale.US))
        assertEquals("2 Jan 1970", Formatters.formatDate(24 * 3_600_000L + 1, ZoneOffset.UTC, Locale.US))
        // 23:30 UTC on Jan 1 is already Jan 2 in UTC+2
        assertEquals("2 Jan 1970", Formatters.formatDate(23 * 3_600_000L + 30 * 60_000L, ZoneOffset.ofHours(2), Locale.US))
    }
}
