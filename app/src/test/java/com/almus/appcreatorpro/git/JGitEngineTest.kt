package com.almus.appcreatorpro.git

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

/** Runs the real JGit engine against real repositories in temporary folders (no Android, no network). */
class JGitEngineTest {
    @get:Rule val tmp = TemporaryFolder()
    private val engine = JGitEngine()
    private val me = GitIdentity("Tester", "tester@example.com")
    private lateinit var ws: GitWorkspace

    private fun workspace(name: String) = GitWorkspace(File(tmp.root, "$name/files").also { it.mkdirs() }, File(tmp.root, "$name/git"))

    @Before fun setUp() {
        ws = workspace("p")
        engine.init(ws, "main", createGitignore = false)
    }

    private fun write(w: GitWorkspace, path: String, text: String) { File(w.workTree, path).also { it.parentFile.mkdirs() }.writeText(text) }
    private fun commitAll(w: GitWorkspace, msg: String) { engine.stageAll(w); engine.commit(w, msg, me) }
    private fun kind(block: () -> Unit): GitException.Kind {
        try { block() } catch (e: GitException) { return e.kind }
        fail("expected a GitException"); throw IllegalStateException()
    }

    // -------------------------------------------------------------------- repository layout
    @Test fun initKeepsGitDataOutsideTheProjectFiles() {
        assertTrue(engine.isRepository(ws))
        assertFalse("no .git inside the work tree", File(ws.workTree, ".git").exists())
        assertTrue(File(ws.gitDir, "HEAD").isFile)
    }

    @Test fun initTwiceIsRefused() { assertEquals(GitException.Kind.ALREADY_A_REPOSITORY, kind { engine.init(ws, "main", false) }) }

    @Test fun initCanCreateGitignoreWithoutOverwriting() {
        val w = workspace("g"); engine.init(w, "main", true)
        assertTrue(File(w.workTree, ".gitignore").readText().contains("*.jks"))
        val w2 = workspace("g2"); write(w2, ".gitignore", "mine\n"); engine.init(w2, "main", true)
        assertEquals("mine\n", File(w2.workTree, ".gitignore").readText())
    }

    @Test fun notARepositoryIsReported() {
        val w = workspace("none")
        assertFalse(engine.isRepository(w))
        assertEquals(GitException.Kind.NOT_A_REPOSITORY, kind { engine.status(w) })
    }

    // -------------------------------------------------------------------- status, stage, unstage, commit
    @Test fun stageCommitCycle() {
        write(ws, "a.txt", "one")
        var st = engine.status(ws)
        assertNull(st.headSha); assertEquals("main", st.branch)
        assertEquals(ChangeKind.UNTRACKED, st.untracked.single().unstaged)

        engine.stage(ws, listOf("a.txt"))
        st = engine.status(ws)
        assertEquals(ChangeKind.ADDED, st.staged.single().staged)

        val c = engine.commit(ws, "first", me)
        assertEquals("first", c.summary); assertEquals("Tester", c.authorName)
        st = engine.status(ws)
        assertTrue(st.isClean); assertEquals(c.sha, st.headSha)

        write(ws, "a.txt", "two")
        assertEquals(ChangeKind.MODIFIED, engine.status(ws).unstaged.single().unstaged)
        File(ws.workTree, "a.txt").delete()
        assertEquals(ChangeKind.DELETED, engine.status(ws).unstaged.single().unstaged)
        engine.stage(ws, listOf("a.txt"))
        assertEquals(ChangeKind.DELETED, engine.status(ws).staged.single().staged)
    }

    @Test fun unstageWorksBeforeAndAfterTheFirstCommit() {
        write(ws, "a.txt", "1"); engine.stage(ws, listOf("a.txt"))
        engine.unstage(ws, listOf("a.txt"))
        assertTrue(engine.status(ws).staged.isEmpty())
        engine.stageAll(ws); engine.unstageAll(ws)
        assertTrue(engine.status(ws).staged.isEmpty())

        commitAll(ws, "c1")
        write(ws, "a.txt", "2"); engine.stage(ws, listOf("a.txt"))
        engine.unstage(ws, listOf("a.txt"))
        val st = engine.status(ws)
        assertTrue(st.staged.isEmpty()); assertEquals("2", File(ws.workTree, "a.txt").readText())
        engine.stageAll(ws); engine.unstageAll(ws)
        assertTrue(engine.status(ws).staged.isEmpty())
    }

    @Test fun editorTempFilesAreNeverStaged() {
        write(ws, "a.txt", "1"); write(ws, "b.txt.almus-tmp", "half written")
        engine.stageAll(ws)
        val st = engine.status(ws)
        assertEquals(listOf("a.txt"), st.staged.map { it.path })
        assertTrue(st.changes.none { it.path.endsWith(".almus-tmp") })
    }

    @Test fun stagedTextReadsTheIndexNotTheWorkTree() {
        write(ws, "a.txt", "staged version"); engine.stage(ws, listOf("a.txt"))
        write(ws, "a.txt", "later edit")
        write(ws, "bin.dat", "x\u0000y"); engine.stage(ws, listOf("bin.dat"))
        val text = engine.stagedText(ws, listOf("a.txt", "bin.dat", "missing"))
        assertEquals("staged version", text["a.txt"]); assertNull(text["bin.dat"]); assertNull(text["missing"])
    }

    // -------------------------------------------------------------------- history
    @Test fun historyAndCommitFiles() {
        write(ws, "a.txt", "1"); commitAll(ws, "one")
        write(ws, "b.txt", "2"); File(ws.workTree, "a.txt").delete(); commitAll(ws, "two")
        val log = engine.log(ws, 0, 10)
        assertEquals(listOf("two", "one"), log.map { it.summary })
        assertEquals(1, log[0].parents.size); assertTrue(log[1].parents.isEmpty())
        assertEquals(1, engine.log(ws, 1, 10).size)
        val files = engine.commitFiles(ws, log[0].sha).associate { it.path to it.kind }
        assertEquals(ChangeKind.ADDED, files["b.txt"]); assertEquals(ChangeKind.DELETED, files["a.txt"])
        assertEquals(ChangeKind.ADDED, engine.commitFiles(ws, log[1].sha).single().kind)
        assertEquals(GitException.Kind.NOT_FOUND, kind { engine.commitFiles(ws, "deadbeefdeadbeef") })
    }

    @Test fun emptyRepositoryHasNoHistory() { assertTrue(engine.log(ws, 0, 10).isEmpty()) }

    // -------------------------------------------------------------------- branches
    @Test fun createSwitchAndListBranches() {
        assertEquals(GitException.Kind.NO_COMMITS, kind { engine.createBranch(ws, "x", true) })
        write(ws, "a.txt", "1"); commitAll(ws, "one")
        engine.createBranch(ws, "feature", checkout = true)
        assertEquals("feature", engine.status(ws).branch)
        engine.checkout(ws, "main")
        val branches = engine.branches(ws)
        assertEquals(setOf("main", "feature"), branches.map { it.name }.toSet())
        assertTrue(branches.single { it.name == "main" }.isCurrent)
        assertEquals(GitException.Kind.ALREADY_EXISTS, kind { engine.createBranch(ws, "feature", false) })
        assertEquals(GitException.Kind.NOT_FOUND, kind { engine.checkout(ws, "nope") })
    }

    @Test fun switchingRefusesToOverwriteLocalChanges() {
        write(ws, "a.txt", "base"); commitAll(ws, "base")
        engine.createBranch(ws, "other", true)
        write(ws, "a.txt", "other side"); commitAll(ws, "other change")
        engine.checkout(ws, "main")
        write(ws, "a.txt", "uncommitted work")
        assertEquals(GitException.Kind.UNCOMMITTED_CHANGES, kind { engine.checkout(ws, "other") })
        assertEquals("uncommitted work", File(ws.workTree, "a.txt").readText())
        assertEquals("main", engine.status(ws).branch)
    }

    @Test fun deletingBranchesIsSafe() {
        write(ws, "a.txt", "1"); commitAll(ws, "one")
        engine.createBranch(ws, "merged", false)
        engine.createBranch(ws, "work", true)
        write(ws, "w.txt", "w"); commitAll(ws, "work only")
        engine.checkout(ws, "main")

        val merged = engine.deletePreview(ws, "merged")
        assertTrue(merged.isMerged); assertEquals(0, merged.unmergedCommits)
        val work = engine.deletePreview(ws, "work")
        assertFalse(work.isMerged); assertEquals(1, work.unmergedCommits); assertFalse(work.existsOnRemote)
        assertTrue(engine.deletePreview(ws, "main").isCurrent)

        assertEquals(GitException.Kind.NOT_MERGED, kind { engine.deleteBranch(ws, "work", force = false) })
        assertTrue(engine.branches(ws).any { it.name == "work" })
        engine.deleteBranch(ws, "merged", force = false)
        engine.deleteBranch(ws, "work", force = true)
        assertEquals(listOf("main"), engine.branches(ws).map { it.name })
        assertEquals(GitException.Kind.INVALID_INPUT, kind { engine.deleteBranch(ws, "main", force = true) })
        assertEquals(GitException.Kind.NOT_FOUND, kind { engine.deletePreview(ws, "ghost") })
    }

    // -------------------------------------------------------------------- merge
    @Test fun fastForwardAndTrueMerge() {
        write(ws, "a.txt", "1"); commitAll(ws, "base")
        engine.createBranch(ws, "f1", true)
        write(ws, "f1.txt", "x"); commitAll(ws, "f1")
        engine.checkout(ws, "main")
        assertEquals(MergeStatus.FAST_FORWARD, engine.merge(ws, "f1", "f1", me).status)
        assertTrue(File(ws.workTree, "f1.txt").exists())
        assertEquals(MergeStatus.UP_TO_DATE, engine.merge(ws, "f1", "f1", me).status)

        engine.createBranch(ws, "f2", true)
        write(ws, "f2.txt", "y"); commitAll(ws, "f2")
        engine.checkout(ws, "main")
        write(ws, "m.txt", "m"); commitAll(ws, "main moves")
        val out = engine.merge(ws, "f2", "f2", me)
        assertEquals(MergeStatus.MERGED, out.status)
        assertTrue(engine.log(ws, 0, 1).single().isMerge)
        assertTrue(File(ws.workTree, "f2.txt").exists() && File(ws.workTree, "m.txt").exists())
    }

    private fun makeConflict() {
        write(ws, "c.txt", "line\n"); commitAll(ws, "base")
        engine.createBranch(ws, "theirs", true)
        write(ws, "c.txt", "theirs\n"); commitAll(ws, "theirs edit")
        engine.checkout(ws, "main")
        write(ws, "c.txt", "mine\n"); commitAll(ws, "mine edit")
    }

    @Test fun conflictsAreDetectedAndTheMergeCanBeAborted() {
        makeConflict()
        val out = engine.merge(ws, "theirs", "theirs", me)
        assertEquals(MergeStatus.CONFLICTS, out.status); assertEquals(listOf("c.txt"), out.conflicts)
        val st = engine.status(ws)
        assertEquals(GitRepoState.MERGING, st.state)
        assertEquals(listOf("c.txt"), st.conflicted.map { it.path })
        assertTrue(GitConflicts.hasMarkers(File(ws.workTree, "c.txt").readText()))

        engine.abortMerge(ws)
        val after = engine.status(ws)
        assertEquals(GitRepoState.CLEAN_STATE, after.state); assertTrue(after.isClean)
        assertEquals("mine\n", File(ws.workTree, "c.txt").readText())
    }

    @Test fun resolvingWithTheirSideAllowsTheMergeCommit() {
        makeConflict()
        engine.merge(ws, "theirs", "theirs", me)
        engine.resolveWithSide(ws, "c.txt", ConflictSide.THEIRS)
        assertEquals("theirs\n", File(ws.workTree, "c.txt").readText())
        assertTrue(engine.status(ws).conflicted.isEmpty())
        val c = engine.commit(ws, "merge theirs", me)
        assertTrue(c.isMerge)
        assertEquals(GitRepoState.CLEAN_STATE, engine.status(ws).state)
    }

    @Test fun resolvingByHandAndStaging() {
        makeConflict()
        engine.merge(ws, "theirs", "theirs", me)
        write(ws, "c.txt", "both\n")
        engine.stage(ws, listOf("c.txt"))
        assertTrue(engine.status(ws).conflicted.isEmpty())
        assertTrue(engine.commit(ws, "manual merge", me).isMerge)
    }

    @Test fun mergeRefusesWhenLocalChangesWouldBeLost() {
        write(ws, "a.txt", "1"); commitAll(ws, "base")
        engine.createBranch(ws, "o", true)
        write(ws, "a.txt", "o"); commitAll(ws, "o")
        engine.checkout(ws, "main")
        write(ws, "a.txt", "dirty")
        assertEquals(GitException.Kind.UNCOMMITTED_CHANGES, kind { engine.merge(ws, "o", "o", me) })
        assertEquals("dirty", File(ws.workTree, "a.txt").readText())
    }

    // -------------------------------------------------------------------- remotes (a local bare repository stands in for GitHub)
    private fun bareRemote(): String {
        val dir = File(tmp.root, "remote.git")
        Git.init().setBare(true).setDirectory(dir).setInitialBranch("main").call().close()
        return dir.absolutePath
    }

    @Test fun pushFetchPullRoundTrip() {
        val url = bareRemote()
        write(ws, "a.txt", "1"); commitAll(ws, "one")
        engine.setRemote(ws, "origin", url)
        assertEquals(listOf("origin"), engine.remotes(ws).map { it.name })
        val preview = engine.commitsToPush(ws, "origin")
        assertEquals(1, preview.commits); assertTrue(preview.createsRemoteBranch)
        val pushed = engine.push(ws, "origin", setUpstream = true, ctx = GitContext())
        assertFalse(pushed.upToDate)
        assertEquals("origin/main", engine.status(ws).upstream)
        assertTrue(engine.push(ws, "origin", false, GitContext()).upToDate)

        val other = workspace("clone")
        engine.clone(other, url, "main", GitContext())
        assertFalse(File(other.workTree, ".git").exists())
        assertEquals("1", File(other.workTree, "a.txt").readText())
        assertEquals("main", engine.status(other).branch)

        write(ws, "b.txt", "2"); commitAll(ws, "two")
        engine.push(ws, "origin", false, GitContext())
        engine.fetch(other, "origin", GitContext())
        assertEquals(1, engine.status(other).behind)
        assertEquals(MergeStatus.FAST_FORWARD, engine.merge(other, "refs/remotes/origin/main", "origin/main", me).status)
        assertEquals("2", File(other.workTree, "b.txt").readText())
        assertEquals(0, engine.status(other).behind)
    }

    @Test fun pushThatIsNotAFastForwardIsRefusedNeverForced() {
        val url = bareRemote()
        write(ws, "a.txt", "1"); commitAll(ws, "one")
        engine.setRemote(ws, "origin", url); engine.push(ws, "origin", true, GitContext())
        val other = workspace("clone"); engine.clone(other, url, "main", GitContext())

        write(other, "o.txt", "o"); commitAll(other, "other"); engine.push(other, "origin", false, GitContext())
        write(ws, "w.txt", "w"); commitAll(ws, "mine")
        assertEquals(GitException.Kind.REJECTED, kind { engine.push(ws, "origin", false, GitContext()) })
        // the remote still has the other commit
        val remoteLog = Git.open(File(url)).use { g -> g.log().call().map { it.shortMessage } }
        assertEquals(listOf("other", "one"), remoteLog)
    }

    @Test fun missingRemoteIsReported() {
        write(ws, "a.txt", "1"); commitAll(ws, "one")
        assertEquals(GitException.Kind.NO_REMOTE, kind { engine.fetch(ws, "origin", GitContext()) })
        assertEquals(GitException.Kind.NO_REMOTE, kind { engine.push(ws, "origin", false, GitContext()) })
    }

    @Test fun credentialsInAnAddressAreNeverShown() {
        engine.setRemote(ws, "origin", "https://user:SECRETPW@github.com/o/r.git")
        val shown = engine.remotes(ws).single().url
        assertFalse(shown.contains("SECRETPW")); assertFalse(shown.contains("user"))
    }

    @Test fun cloneOfAMissingRepositoryFailsCleanly() {
        val w = workspace("bad")
        assertNotNull(kind { engine.clone(w, File(tmp.root, "does-not-exist.git").absolutePath, null, GitContext()) })
    }

    @Test fun detachedHeadCannotPush() {
        write(ws, "a.txt", "1"); commitAll(ws, "one")
        engine.setRemote(ws, "origin", bareRemote())
        val sha = engine.log(ws, 0, 1).single().sha
        org.eclipse.jgit.storage.file.FileRepositoryBuilder().setGitDir(ws.gitDir).setWorkTree(ws.workTree).build().use { repo ->
            Git(repo).checkout().setName(sha).call()
        }
        assertEquals(GitException.Kind.DETACHED_HEAD, kind { engine.push(ws, "origin", false, GitContext()) })
    }
}
