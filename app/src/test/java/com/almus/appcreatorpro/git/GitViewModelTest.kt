package com.almus.appcreatorpro.git

import com.almus.appcreatorpro.data.storage.InMemoryKeyValueStore
import com.almus.appcreatorpro.testutil.MainDispatcherRule
import com.almus.appcreatorpro.ui.git.GitViewModel
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GitViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var ws: GitWorkspace
    private lateinit var vm: GitViewModel

    @Before fun setUp() {
        ws = GitWorkspace(File(tmp.root, "files").also { it.mkdirs() }, File(tmp.root, "git"))
        val identities = GitIdentityStore(InMemoryKeyValueStore()).also { it.set(GitIdentity("T", "t@example.com")) }
        val service = GitService({ ws }, JGitEngine(), { null }, identities, ioDispatcher = Dispatchers.Unconfined)
        vm = GitViewModel("p", service)
    }

    private fun write(path: String, text: String) = File(ws.workTree, path).writeText(text)

    @Test fun showsNoRepositoryThenInitializes() {
        assertFalse(vm.state.value.loading); assertFalse(vm.state.value.hasRepo)
        vm.initRepository()
        assertTrue(vm.state.value.hasRepo); assertEquals("main", vm.state.value.status?.branch)
        assertNull(vm.state.value.busy)
    }

    @Test fun stageAndCommitUpdateTheState() {
        vm.initRepository()
        write("a.txt", "1")
        vm.refresh()
        assertEquals(1, vm.state.value.status!!.untracked.size)
        vm.stageAll()
        assertTrue(vm.state.value.status!!.staged.isNotEmpty())
        vm.commit("first")
        assertTrue(vm.state.value.status!!.isClean)
        assertEquals("first", vm.state.value.history.single().summary)
        assertTrue(vm.state.value.message!!.startsWith("Committed"))
    }

    @Test fun errorsAreShownNotThrown() {
        vm.initRepository()
        vm.commit("nothing staged")
        assertNotNull(vm.state.value.error)
        assertNull(vm.state.value.busy)
    }

    @Test fun riskyOperationAsksFirstAndRepeatsAfterConfirmation() {
        vm.initRepository()
        write("a.txt", "1"); vm.stageAll(); vm.commit("base")
        vm.createBranch("work")
        write("w.txt", "w"); vm.stageAll(); vm.commit("work only")
        vm.switchBranch("main")

        vm.deleteBranch("work")
        val pending = vm.state.value.pendingRisk
        assertNotNull(pending)
        assertTrue(vm.state.value.branches.any { it.name == "work" })

        vm.confirmRisk("wrong")                       // wrong text: nothing happens
        assertNotNull(vm.state.value.pendingRisk)
        assertTrue(vm.state.value.branches.any { it.name == "work" })

        vm.confirmRisk("work")
        assertNull(vm.state.value.pendingRisk)
        assertFalse(vm.state.value.branches.any { it.name == "work" })
    }

    @Test fun dismissingARiskChangesNothing() {
        vm.initRepository()
        write("a.txt", "1"); vm.stageAll(); vm.commit("base")
        vm.createBranch("x"); vm.switchBranch("main")
        vm.deleteBranch("x")
        assertNotNull(vm.state.value.pendingRisk)
        vm.dismissRisk()
        assertNull(vm.state.value.pendingRisk)
        assertTrue(vm.state.value.branches.any { it.name == "x" })
    }
}
