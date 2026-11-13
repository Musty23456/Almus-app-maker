package com.almus.appcreatorpro.git

import com.almus.appcreatorpro.data.storage.InMemoryKeyValueStore
import com.almus.appcreatorpro.service.github.TokenProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The service on top of the real engine: validation, confirmations, clean-tree rules, secret detection, locking. */
class GitServiceTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var ws: GitWorkspace
    private lateinit var service: GitService
    private val project = "p1"

    private fun newService(engine: GitEngine = JGitEngine(), withIdentity: Boolean = true, token: String? = "tok123"): GitService {
        val identities = GitIdentityStore(InMemoryKeyValueStore())
        if (withIdentity) identities.set(GitIdentity("Tester", "t@example.com"))
        return GitService(
            workspaces = { ws }, engine = engine, credentials = GitHubTokenCredentialSource(TokenProvider { token }),
            identities = identities, ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @Before fun setUp() {
        ws = GitWorkspace(File(tmp.root, "files").also { it.mkdirs() }, File(tmp.root, "git"))
        service = newService()
    }

    private fun write(path: String, text: String) { File(ws.workTree, path).also { it.parentFile.mkdirs() }.writeText(text) }
    private fun commit(msg: String = "c") = runBlocking { service.stageAll(project); service.commit(project, msg) }
    private fun failure(block: suspend () -> Unit): GitException {
        try { runBlocking { block() } } catch (e: GitException) { return e }
        fail("expected GitException"); throw IllegalStateException()
    }

    @Test fun statusIsNullUntilInitialized() = runBlocking {
        assertNull(service.status(project))
        service.init(project)
        assertNotNull(service.status(project))
    }

    @Test fun operationsOnAnUninitializedProjectExplainWhatToDo() {
        assertEquals(GitException.Kind.NOT_A_REPOSITORY, failure { service.stageAll(project) }.kind)
        assertEquals(GitException.Kind.NOT_A_REPOSITORY, failure { service.history(project) }.kind)
    }

    @Test fun badInputIsRejectedBeforeAnythingRuns() {
        runBlocking { service.init(project) }
        assertEquals(GitException.Kind.INVALID_INPUT, failure { service.createBranch(project, "bad name") }.kind)
        assertEquals(GitException.Kind.INVALID_INPUT, failure { service.stage(project, listOf("../x")) }.kind)
        assertEquals(GitException.Kind.INVALID_INPUT, failure { service.setRemote(project, "origin", "ssh://git@github.com/o/r.git") }.kind)
        assertEquals(GitException.Kind.INVALID_INPUT, failure { service.setRemote(project, "origin", "https://u:tok@github.com/o/r.git") }.kind)
        assertEquals(GitException.Kind.INVALID_INPUT, failure { service.commit(project, "  ") }.kind)
        assertEquals(GitException.Kind.INVALID_INPUT, failure { service.commitFiles(project, "not-a-sha") }.kind)
        assertEquals(GitException.Kind.INVALID_INPUT, failure { service.cloneRepository("http://github.com/o/r.git") }.kind)
    }

    @Test fun commitNeedsAnIdentityAndStagedChanges() {
        runBlocking { service.init(project) }
        write("a.txt", "1")
        val anon = newService(withIdentity = false)
        assertEquals(GitException.Kind.IDENTITY_MISSING, failure { anon.commit(project, "x") }.kind)
        assertEquals(GitException.Kind.NOTHING_TO_COMMIT, failure { service.commit(project, "x") }.kind)
        runBlocking { service.stage(project, listOf("a.txt")) }
        assertEquals("x", runBlocking { service.commit(project, "x") }.summary)
    }

    @Test fun defaultIdentityComesFromTheGitHubLogin() {
        val s = GitService({ ws }, JGitEngine(), { null }, GitIdentityStore(InMemoryKeyValueStore()), defaultIdentity = { GitIdentity("octo", "octo@users.noreply.github.com") }, ioDispatcher = Dispatchers.Unconfined)
        assertEquals("octo", s.identity()?.name)
        s.setIdentity("Me", "me@example.com")
        assertEquals("Me", s.identity()?.name)
    }

    // ------------------------------------------------------------------ secrets
    @Test fun committingASecretFileNeedsConfirmation() {
        runBlocking { service.init(project, createGitignore = false) }
        write(".env", "API=1"); write("ok.txt", "fine")
        runBlocking { service.stageAll(project) }
        val e = failure { service.commit(project, "x") }
        assertEquals(GitException.Kind.SECRETS_DETECTED, e.kind)
        assertNotNull(e.risk); assertTrue(e.risk!!.message.contains(".env"))
        assertTrue(runBlocking { service.history(project) }.isEmpty())
        assertEquals("x", runBlocking { service.commit(project, "x", GitConfirmation.Yes) }.summary)
    }

    @Test fun committingATokenInsideAFileNeedsConfirmationAndNeverShowsIt() {
        runBlocking { service.init(project, createGitignore = false) }
        val token = "ghp_" + "b".repeat(36)
        write("notes.txt", "my token $token")
        runBlocking { service.stageAll(project) }
        val e = failure { service.commit(project, "x") }
        assertEquals(GitException.Kind.SECRETS_DETECTED, e.kind)
        assertFalse(e.message!!.contains(token)); assertFalse(e.risk!!.message.contains(token))
    }

    @Test fun gitignoreKeepsSecretsOutOfTheCommit() {
        runBlocking { service.init(project, createGitignore = true) }
        write("local.properties", "sdk.dir=/x"); write("a.txt", "1")
        runBlocking { service.stageAll(project) }
        val st = runBlocking { service.status(project)!! }
        assertEquals(listOf(".gitignore", "a.txt"), st.staged.map { it.path })
        assertEquals("x", runBlocking { service.commit(project, "x") }.summary)
    }

    // ------------------------------------------------------------------ destructive operations
    private fun repoWithUnmergedBranch() {
        runBlocking { service.init(project, createGitignore = false) }
        write("a.txt", "1"); commit("base")
        runBlocking { service.createBranch(project, "work", checkout = true) }
        write("w.txt", "w"); commit("work only")
        runBlocking { service.switchBranch(project, "main") }
    }

    @Test fun deletingAnUnmergedBranchNeedsTheNameTyped() {
        repoWithUnmergedBranch()
        val first = failure { service.deleteBranch(project, "work") }
        assertEquals(GitException.Kind.CONFIRMATION_REQUIRED, first.kind)
        assertEquals(GitRiskLevel.TYPE_TO_CONFIRM, first.risk!!.level)
        assertEquals(GitException.Kind.CONFIRMATION_REQUIRED, failure { service.deleteBranch(project, "work", GitConfirmation.Yes) }.kind)
        assertEquals(GitException.Kind.CONFIRMATION_REQUIRED, failure { service.deleteBranch(project, "work", GitConfirmation(true, "wrong")) }.kind)
        assertTrue(runBlocking { service.branches(project) }.any { it.name == "work" })
        runBlocking { service.deleteBranch(project, "work", GitConfirmation(true, "work")) }
        assertFalse(runBlocking { service.branches(project) }.any { it.name == "work" })
    }

    @Test fun deletingAMergedBranchNeedsOneConfirmationAndCurrentBranchIsRefused() {
        runBlocking { service.init(project, createGitignore = false) }
        write("a.txt", "1"); commit("base")
        runBlocking { service.createBranch(project, "done", checkout = false) }
        val e = failure { service.deleteBranch(project, "done") }
        assertEquals(GitRiskLevel.CONFIRM, e.risk!!.level)
        runBlocking { service.deleteBranch(project, "done", GitConfirmation.Yes) }
        assertEquals(GitException.Kind.INVALID_INPUT, failure { service.deleteBranch(project, "main", GitConfirmation.Yes) }.kind)
    }

    @Test fun abortingAMergeNeedsConfirmation() {
        runBlocking { service.init(project, createGitignore = false) }
        write("c.txt", "line\n"); commit("base")
        runBlocking { service.createBranch(project, "theirs", true) }
        write("c.txt", "theirs\n"); commit("t")
        runBlocking { service.switchBranch(project, "main") }
        write("c.txt", "mine\n"); commit("m")
        val out = runBlocking { service.merge(project, "theirs") }
        assertEquals(MergeStatus.CONFLICTS, out.status)

        val conflicts = runBlocking { service.conflicts(project) }
        assertEquals("c.txt", conflicts.single().path); assertTrue(conflicts.single().stillHasMarkers)
        assertEquals(GitException.Kind.MERGE_IN_PROGRESS, failure { service.switchBranch(project, "theirs") }.kind)
        assertEquals(GitException.Kind.CONFLICTS, failure { service.commit(project, "x") }.kind)
        assertEquals(GitException.Kind.CONFLICTS, failure { service.markResolved(project, "c.txt") }.kind) // markers still there
        assertEquals(GitException.Kind.CONFIRMATION_REQUIRED, failure { service.resolveWithSide(project, "c.txt", ConflictSide.THEIRS) }.kind)
        assertEquals(GitException.Kind.CONFIRMATION_REQUIRED, failure { service.abortMerge(project) }.kind)
        assertTrue(GitConflicts.hasMarkers(File(ws.workTree, "c.txt").readText()))

        runBlocking { service.abortMerge(project, GitConfirmation.Yes) }
        assertEquals("mine\n", File(ws.workTree, "c.txt").readText())
        assertEquals(GitException.Kind.INVALID_INPUT, failure { service.abortMerge(project, GitConfirmation.Yes) }.kind)
    }

    @Test fun resolveByHandThenMarkResolvedThenCommit() {
        runBlocking { service.init(project, createGitignore = false) }
        write("c.txt", "line\n"); commit("base")
        runBlocking { service.createBranch(project, "theirs", true) }
        write("c.txt", "theirs\n"); commit("t")
        runBlocking { service.switchBranch(project, "main") }
        write("c.txt", "mine\n"); commit("m")
        runBlocking { service.merge(project, "theirs") }
        write("c.txt", "merged by hand\n")
        runBlocking { service.markResolved(project, "c.txt") }
        assertTrue(runBlocking { service.commit(project, "merge") }.isMerge)
        assertTrue(runBlocking { service.status(project)!! }.isClean)
    }

    // ------------------------------------------------------------------ clean-tree rules
    @Test fun mergeAndPullRefuseToStartWithUncommittedChanges() {
        repoWithUnmergedBranch()
        write("a.txt", "dirty")
        assertEquals(GitException.Kind.UNCOMMITTED_CHANGES, failure { service.merge(project, "work") }.kind)
        assertEquals(GitException.Kind.UNCOMMITTED_CHANGES, failure { service.pull(project) }.kind)
        assertEquals("dirty", File(ws.workTree, "a.txt").readText())
    }

    @Test fun cannotMergeABranchIntoItself() {
        repoWithUnmergedBranch()
        assertEquals(GitException.Kind.INVALID_INPUT, failure { service.merge(project, "main") }.kind)
    }

    // ------------------------------------------------------------------ push, pull with a local bare remote
    private fun bare(): String {
        val dir = File(tmp.root, "remote.git")
        Git.init().setBare(true).setDirectory(dir).setInitialBranch("main").call().close()
        return dir.absolutePath
    }

    private fun addRemote(url: String) = JGitEngine().setRemote(ws, "origin", url) // bypasses the https-only rule on purpose (test remote is a local folder)

    @Test fun pushNeedsConfirmationIsNeverForcedAndSecondPushIsANoOp() {
        runBlocking { service.init(project, createGitignore = false) }
        write("a.txt", "1"); commit("one")
        assertEquals(GitException.Kind.NO_REMOTE, failure { service.push(project) }.kind)
        addRemote(bare())
        val preview = runBlocking { service.pushPreview(project) }
        assertEquals(1, preview.commits); assertTrue(preview.createsRemoteBranch)
        val e = failure { service.push(project) }
        assertEquals(GitException.Kind.CONFIRMATION_REQUIRED, e.kind); assertTrue(e.risk!!.message.contains("origin/main"))
        assertFalse(runBlocking { service.push(project, GitConfirmation.Yes) }.upToDate)
        assertEquals("origin/main", runBlocking { service.status(project)!! }.upstream)
        assertTrue(runBlocking { service.push(project) }.upToDate)
    }

    @Test fun pullFastForwardsFromTheRemote() {
        runBlocking { service.init(project, createGitignore = false) }
        write("a.txt", "1"); commit("one")
        val url = bare(); addRemote(url)
        runBlocking { service.push(project, GitConfirmation.Yes) }

        // someone else pushes a commit
        val otherWs = GitWorkspace(File(tmp.root, "o/files"), File(tmp.root, "o/git"))
        val engine = JGitEngine()
        engine.clone(otherWs, url, "main", GitContext())
        File(otherWs.workTree, "b.txt").writeText("2")
        engine.stageAll(otherWs); engine.commit(otherWs, "theirs", GitIdentity("O", "o@example.com"))
        engine.push(otherWs, "origin", false, GitContext())

        val out = runBlocking { service.pull(project) }
        assertEquals(MergeStatus.FAST_FORWARD, out.merge.status)
        assertEquals("2", File(ws.workTree, "b.txt").readText())
        assertEquals(MergeStatus.UP_TO_DATE, runBlocking { service.pull(project) }.merge.status)
    }

    @Test fun pullFromARemoteWithoutTheBranchSaysSo() {
        runBlocking { service.init(project, createGitignore = false) }
        write("a.txt", "1"); commit("one")
        addRemote(bare())
        assertEquals(GitException.Kind.NOT_FOUND, failure { service.pull(project) }.kind)
    }

    // ------------------------------------------------------------------ concurrency
    @Test fun aSecondMutatingOperationFailsFastWhileOneIsRunning() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val real = JGitEngine()
        val slow = object : GitEngine by real {
            override fun stageAll(ws: GitWorkspace) { started.countDown(); release.await(10, TimeUnit.SECONDS); real.stageAll(ws) }
        }
        val s = newService(engine = slow)
        s.init(project)
        val running = async(Dispatchers.IO) { s.stageAll(project) }
        assertTrue(started.await(10, TimeUnit.SECONDS))
        val busy = try { s.stageAll(project); null } catch (e: GitException) { e }
        assertEquals(GitException.Kind.BUSY, busy?.kind)
        release.countDown()
        running.await()
        s.stageAll(project) // free again
    }

    @Test fun unknownProjectIsReportedAsNotFound() {
        val s = GitService({ throw com.almus.appcreatorpro.domain.error.StorageException(com.almus.appcreatorpro.domain.error.StorageException.Kind.NOT_FOUND, "gone") },
            JGitEngine(), { null }, GitIdentityStore(InMemoryKeyValueStore()), ioDispatcher = Dispatchers.Unconfined)
        assertEquals(GitException.Kind.NOT_FOUND, failure { s.status("zzz") }.kind)
    }
}
