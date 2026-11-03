package com.almus.appcreatorpro.transfer

import com.almus.appcreatorpro.data.repository.InMemoryProjectRepository
import com.almus.appcreatorpro.data.storage.InMemoryKeyValueStore
import com.almus.appcreatorpro.data.storage.RecentProjectsStore
import com.almus.appcreatorpro.domain.github.GitHubBranch
import com.almus.appcreatorpro.domain.github.GitHubRepo
import com.almus.appcreatorpro.domain.github.RepoPage
import com.almus.appcreatorpro.domain.model.ProjectType
import com.almus.appcreatorpro.domain.transfer.ProjectSort
import com.almus.appcreatorpro.domain.transfer.TransferException
import com.almus.appcreatorpro.domain.transfer.TransferProgress
import com.almus.appcreatorpro.domain.transfer.TreeSource
import com.almus.appcreatorpro.service.transfer.DocumentAccess
import com.almus.appcreatorpro.service.transfer.DocumentInfo
import com.almus.appcreatorpro.service.transfer.GitHubSource
import com.almus.appcreatorpro.testutil.MainDispatcherRule
import com.almus.appcreatorpro.ui.projects.ProjectsViewModel
import com.almus.appcreatorpro.ui.transfer.BackupsViewModel
import com.almus.appcreatorpro.ui.transfer.ImportUiState
import com.almus.appcreatorpro.ui.transfer.ImportViewModel
import com.almus.appcreatorpro.ui.transfer.ProjectTransferViewModel
import com.almus.appcreatorpro.ui.transfer.TransferOpState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream

class FakeDocumentAccess : DocumentAccess {
    val inputs = HashMap<String, ByteArray>()
    val names = HashMap<String, String>()
    val trees = HashMap<String, TreeSource>()
    val outputs = HashMap<String, ByteArrayOutputStream>()
    val deleted = ArrayList<String>()
    var failOutput: Boolean = false

    override fun describe(uri: String) = DocumentInfo(names[uri], inputs[uri]?.size?.toLong())

    override fun openInput(uri: String): InputStream =
        ByteArrayInputStream(inputs[uri] ?: throw TransferException(TransferException.Kind.NOT_FOUND, "The selected file no longer exists."))

    override fun openOutput(uri: String): OutputStream {
        if (failOutput) throw TransferException(TransferException.Kind.PERMISSION, "Android did not allow writing to the chosen location. Please choose another one.")
        return ByteArrayOutputStream().also { outputs[uri] = it }
    }

    override fun delete(uri: String): Boolean {
        deleted += uri
        return true
    }

    override fun treeSource(treeUri: String): TreeSource = trees[treeUri] ?: throw TransferException(TransferException.Kind.NOT_FOUND, "The selected folder is not valid.")
}

class FakeGitHubSource(
    var user: String? = "octo",
    var repos: List<GitHubRepo> = emptyList(),
    var branches: List<GitHubBranch> = emptyList(),
    var archive: ByteArray = ByteArray(0),
) : GitHubSource {
    val downloads = ArrayList<List<String>>()
    override suspend fun connectedUser(): String? = user
    override suspend fun listRepositories(page: Int) = RepoPage(repos, null)
    override suspend fun listBranches(owner: String, name: String) = branches
    override suspend fun download(owner: String, name: String, ref: String, target: File, onProgress: (TransferProgress) -> Unit) {
        downloads += listOf(owner, name, ref)
        target.writeBytes(archive)
        onProgress(TransferProgress(TransferProgress.Phase.DOWNLOADING, archive.size.toLong(), archive.size.toLong()))
    }
}

fun ghRepo(id: Long, name: String) = GitHubRepo(id, "octo", name, false, "main", true, "https://github.com/octo/$name")

class Phase5ViewModelsTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private fun importVm(env: TransferEnv, docs: FakeDocumentAccess = FakeDocumentAccess(), gh: FakeGitHubSource = FakeGitHubSource()) =
        ImportViewModel(env.importer, docs, env.store, gh, Dispatchers.Unconfined)

    // ---------------------------------------------------------------- import view model

    @Test
    fun zip_import_goes_idle_working_preview_done() = runTest {
        val env = TransferEnv(tmp)
        val docs = FakeDocumentAccess()
        docs.inputs["content://zip/1"] = zipBytes(*androidKotlinFiles())
        docs.names["content://zip/1"] = "Demo.zip"
        val vm = importVm(env, docs)
        assertTrue(vm.state.value is ImportUiState.Idle)

        vm.importZip("content://zip/1")
        val preview = vm.state.value as ImportUiState.Preview
        assertEquals("Demo App", preview.name)
        assertNull(preview.nameError)
        assertEquals(ProjectType.ANDROID_KOTLIN, preview.preview.type)
        assertTrue("nothing is saved before confirmation", env.store.getProjects().isEmpty())

        vm.confirmImport()
        val done = vm.state.value as ImportUiState.Done
        assertEquals("Demo App", done.project.name)
        assertEquals(1, env.store.getProjects().size)
        assertTrue(done.project.description.contains("Demo.zip"))
        assertTrue(env.stagingLeftovers().isEmpty())
    }

    @Test
    fun name_is_validated_while_typing_and_import_is_blocked_until_valid() = runTest {
        val env = TransferEnv(tmp)
        env.store.createProject("Taken", "com.example.taken", "")
        val docs = FakeDocumentAccess()
        docs.inputs["u"] = zipBytes("index.html" to text("<html/>"))
        docs.names["u"] = "site.zip"
        val vm = importVm(env, docs)
        vm.importZip("u")

        vm.onNameChange("taken")
        assertNotNull((vm.state.value as ImportUiState.Preview).nameError)
        vm.confirmImport()
        assertTrue("still in preview", vm.state.value is ImportUiState.Preview)
        assertTrue(env.store.getProjects().size == 1)

        vm.onNameChange("Fresh Site")
        assertNull((vm.state.value as ImportUiState.Preview).nameError)
        vm.confirmImport()
        assertTrue(vm.state.value is ImportUiState.Done)
        assertEquals(2, env.store.getProjects().size)
    }

    @Test
    fun bad_files_give_a_friendly_failure_and_try_again_returns_to_the_start() = runTest {
        val env = TransferEnv(tmp)
        val docs = FakeDocumentAccess()
        docs.inputs["u"] = text("definitely not a zip")
        val vm = importVm(env, docs)
        vm.importZip("u")
        val failed = vm.state.value as ImportUiState.Failed
        assertTrue(failed.message.contains("not a ZIP"))
        assertTrue(vm.back())
        assertTrue(vm.state.value is ImportUiState.Idle)

        vm.importZip("missing-uri")
        assertTrue((vm.state.value as ImportUiState.Failed).message.contains("no longer exists"))
        assertTrue(env.store.getProjects().isEmpty())
    }

    @Test
    fun unsupported_projects_cannot_be_confirmed() = runTest {
        val env = TransferEnv(tmp)
        val docs = FakeDocumentAccess()
        docs.inputs["u"] = zipBytes("pubspec.yaml" to text("name: x"), "lib/main.dart" to text("void main(){}"))
        val vm = importVm(env, docs)
        vm.importZip("u")
        val preview = vm.state.value as ImportUiState.Preview
        assertFalse(preview.preview.canImport)
        vm.confirmImport()
        assertTrue(vm.state.value is ImportUiState.Preview)
        assertTrue(env.store.getProjects().isEmpty())
    }

    @Test
    fun cancelling_the_preview_discards_the_staged_files() = runTest {
        val env = TransferEnv(tmp)
        val docs = FakeDocumentAccess()
        docs.inputs["u"] = zipBytes(*androidKotlinFiles())
        val vm = importVm(env, docs)
        vm.importZip("u")
        assertFalse(env.stagingLeftovers().isEmpty())
        vm.reset()
        assertTrue(vm.state.value is ImportUiState.Idle)
        assertTrue(env.stagingLeftovers().isEmpty())
    }

    @Test
    fun folder_import_uses_the_storage_access_framework_tree() = runTest {
        val env = TransferEnv(tmp)
        val docs = FakeDocumentAccess()
        docs.trees["content://tree/1"] = FakeTree("Studio Project", androidKotlinFiles().associate { it.first to it.second })
        val vm = importVm(env, docs)
        vm.importFolder("content://tree/1")
        val preview = vm.state.value as ImportUiState.Preview
        assertEquals("Studio Project", preview.name)
        vm.confirmImport()
        assertTrue(vm.state.value is ImportUiState.Done)
    }

    @Test
    fun github_flow_lists_repos_branches_and_imports_the_branch_commit() = runTest {
        val env = TransferEnv(tmp)
        val gh = FakeGitHubSource(
            repos = listOf(ghRepo(1, "alpha"), ghRepo(2, "beta-site")),
            branches = listOf(GitHubBranch("main", "aaaaaaa1111"), GitHubBranch("dev", "bbbbbbb2222")),
            archive = zipBytes("octo-beta-site-bbbbbbb/index.html" to text("<html/>"), "octo-beta-site-bbbbbbb/app.js" to text("1")),
        )
        val vm = importVm(env, gh = gh)
        vm.openGitHub()
        assertTrue(vm.state.value is ImportUiState.GitHubBrowse)
        assertEquals(true, vm.githubState.value.connected)
        assertEquals(2, vm.githubState.value.repos.size)

        vm.onRepositoryQuery("beta")
        assertEquals(listOf("beta-site"), vm.githubState.value.visibleRepos.map { it.name })

        vm.selectRepository(ghRepo(2, "beta-site"))
        assertEquals(listOf("main", "dev"), vm.githubState.value.branches.map { it.name })
        vm.selectBranch("dev")
        vm.importSelectedRepository()

        assertEquals(listOf(listOf("octo", "beta-site", "bbbbbbb2222")), gh.downloads)
        val preview = vm.state.value as ImportUiState.Preview
        assertEquals("beta-site", preview.name)
        assertEquals(ProjectType.WEB, preview.preview.type)
        assertTrue(preview.preview.description.contains("octo/beta-site"))
        assertTrue(preview.preview.description.contains("dev"))
        vm.confirmImport()
        val project = (vm.state.value as ImportUiState.Done).project
        // the single wrapper folder of the GitHub archive is gone: structure is preserved from the repo root
        assertEquals(listOf("app.js", "index.html"), env.store.listFiles(project.id).map { it.path })
        assertTrue(env.stagingLeftovers().isEmpty())
    }

    @Test
    fun github_not_connected_is_reported() = runTest {
        val env = TransferEnv(tmp)
        val vm = importVm(env, gh = FakeGitHubSource(user = null))
        vm.openGitHub()
        assertEquals(false, vm.githubState.value.connected)
    }

    @Test
    fun github_download_failure_shows_a_message_and_leaves_no_files() = runTest {
        val env = TransferEnv(tmp)
        val gh = FakeGitHubSource(repos = listOf(ghRepo(1, "alpha")), branches = listOf(GitHubBranch("main", "")), archive = text("<html>not a zip</html>"))
        val vm = importVm(env, gh = gh)
        vm.openGitHub()
        vm.selectRepository(ghRepo(1, "alpha"))
        vm.importSelectedRepository()
        assertTrue(vm.state.value is ImportUiState.Failed)
        assertEquals("main", gh.downloads.single()[2]) // no sha known: falls back to the branch name
        assertTrue(env.stagingLeftovers().isEmpty())
        assertTrue(env.store.getProjects().isEmpty())
    }

    // ---------------------------------------------------------------- export / backup view models

    private suspend fun demoProject(env: TransferEnv) =
        env.importer.commit(
            env.importer.stageZip(ByteArrayInputStream(zipBytes(*androidKotlinFiles())), null, "d.zip", com.almus.appcreatorpro.domain.transfer.ImportSourceKind.ZIP_FILE),
            "Demo App",
        )

    @Test
    fun export_writes_a_zip_to_the_chosen_destination() = runTest {
        val env = TransferEnv(tmp)
        val project = demoProject(env)
        val docs = FakeDocumentAccess()
        val vm = ProjectTransferViewModel(env.archiver, docs, env.backups, clock = { 0L }, ioDispatcher = Dispatchers.Unconfined)

        val suggested = vm.beginExport(project)
        assertTrue(suggested.startsWith("Demo-App-") && suggested.endsWith(".zip"))
        vm.completeExport("content://dest/1")

        assertTrue(vm.state.value is TransferOpState.Idle)
        assertTrue(vm.message.value!!.startsWith("Exported 9 files"))
        val names = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(docs.outputs.getValue("content://dest/1").toByteArray())).use { zip ->
            while (true) { names += (zip.nextEntry ?: break).name }
        }
        assertEquals(9, names.size)
        assertTrue(docs.deleted.isEmpty())
    }

    @Test
    fun export_cancelled_in_the_picker_does_nothing_and_failures_clean_up() = runTest {
        val env = TransferEnv(tmp)
        val project = demoProject(env)
        val docs = FakeDocumentAccess()
        val vm = ProjectTransferViewModel(env.archiver, docs, env.backups, ioDispatcher = Dispatchers.Unconfined)

        vm.beginExport(project)
        vm.completeExport(null)
        assertNull(vm.message.value)
        assertTrue(docs.outputs.isEmpty())

        docs.failOutput = true
        vm.beginExport(project)
        vm.completeExport("content://dest/2")
        assertTrue(vm.message.value!!.contains("did not allow"))
        assertEquals(listOf("content://dest/2"), docs.deleted)
        assertTrue(vm.state.value is TransferOpState.Idle)
    }

    @Test
    fun backup_now_creates_a_backup_and_reports_it() = runTest {
        val env = TransferEnv(tmp)
        val project = demoProject(env)
        val vm = ProjectTransferViewModel(env.archiver, FakeDocumentAccess(), env.backups, ioDispatcher = Dispatchers.Unconfined)
        vm.backupNow(project)
        assertTrue(vm.message.value!!.startsWith("Backup saved"))
        assertEquals(1, env.backups.listBackups(project.id).size)
    }

    @Test
    fun backups_view_model_lists_creates_restores_and_deletes() = runTest {
        val env = TransferEnv(tmp)
        val project = demoProject(env)
        val vm = BackupsViewModel(project.id, env.backups, env.store)
        assertTrue(vm.state.value.canCreate)
        assertEquals("Demo App", vm.state.value.projectName)
        assertTrue(vm.state.value.items.isEmpty())

        vm.createBackup()
        assertEquals(1, vm.state.value.items.size)
        assertTrue(vm.state.value.items.first().projectExists)

        vm.restoreAsNew(vm.state.value.items.first())
        assertEquals("Demo App restored", vm.state.value.restored?.name)
        assertEquals(2, env.store.getProjects().size)

        vm.delete(vm.state.value.items.first())
        assertTrue(vm.state.value.items.isEmpty())
    }

    @Test
    fun backups_view_model_for_all_projects_marks_deleted_projects() = runTest {
        val env = TransferEnv(tmp)
        val project = demoProject(env)
        env.backups.createBackup(project.id)
        env.store.deleteProject(project.id)
        val vm = BackupsViewModel(null, env.backups, env.store)
        val item = vm.state.value.items.single()
        assertFalse(item.projectExists)
        assertFalse(vm.state.value.canCreate)
        vm.restoreReplacing(item)
        assertNotNull(vm.state.value.message)
        assertTrue(env.store.getProjects().isEmpty())
    }

    // ---------------------------------------------------------------- projects list (search, sort, recents)

    @Test
    fun recent_projects_store_persists_and_forgets() {
        val kv = InMemoryKeyValueStore()
        var now = 100L
        val store = RecentProjectsStore(kv) { now++ }
        store.markOpened("a")
        store.markOpened("b")
        store.markOpened("a")
        assertEquals(mapOf("a" to 102L, "b" to 101L), store.opened.value)
        assertEquals(mapOf("a" to 102L, "b" to 101L), RecentProjectsStore(kv).opened.value)
        store.forget("a")
        assertEquals(mapOf("b" to 101L), RecentProjectsStore(kv).opened.value)
    }

    @Test
    fun projects_view_model_search_sort_and_recents() = runTest {
        val repo = InMemoryProjectRepository()
        repo.createProject("Zebra App", "com.example.zebra", "")
        repo.createProject("Apple App", "com.example.apple", "")
        repo.createProject("Mango Site", "", "", ProjectType.WEB)
        val recents = RecentProjectsStore(InMemoryKeyValueStore()) { 5L }
        val vm = ProjectsViewModel(repo, recents)

        assertEquals(3, vm.visibleProjects.value.size)
        vm.onSortChange(ProjectSort.NAME_AZ)
        assertEquals(listOf("Apple App", "Mango Site", "Zebra App"), vm.visibleProjects.value.map { it.name })

        vm.onQueryChange("app")
        assertEquals(listOf("Apple App", "Zebra App"), vm.visibleProjects.value.map { it.name })
        vm.onQueryChange("html")
        assertEquals(listOf("Mango Site"), vm.visibleProjects.value.map { it.name })
        vm.onQueryChange("")

        assertTrue(vm.recentProjects.value.isEmpty())
        val mango = vm.visibleProjects.value.first { it.name == "Mango Site" }
        recents.markOpened(mango.id)
        assertEquals(listOf("Mango Site"), vm.recentProjects.value.map { it.name })
        vm.onSortChange(ProjectSort.RECENTLY_OPENED)
        assertEquals("Mango Site", vm.visibleProjects.value.first().name)

        // the original list state is unchanged by search and sort
        vm.onQueryChange("zzz")
        assertTrue(vm.visibleProjects.value.isEmpty())
        assertEquals(3, (vm.state.value as com.almus.appcreatorpro.ui.UiState.Success).data.size)
    }

    @Test
    fun deleting_a_project_forgets_it_in_recents() = runTest {
        val repo = InMemoryProjectRepository()
        val p = repo.createProject("Doomed Project", "com.example.doomed", "")
        val recents = RecentProjectsStore(InMemoryKeyValueStore())
        recents.markOpened(p.id)
        val vm = ProjectsViewModel(repo, recents)
        vm.deleteProject(p)
        assertTrue(recents.opened.value.isEmpty())
        assertTrue(vm.recentProjects.value.isEmpty())
    }

    @Test
    fun projects_view_model_still_works_without_recents() = runTest {
        val repo = InMemoryProjectRepository()
        repo.createProject("Plain Project", "com.example.plain", "")
        val vm = ProjectsViewModel(repo)
        assertEquals(1, vm.visibleProjects.value.size)
        assertTrue(vm.recentProjects.value.isEmpty())
    }
}
