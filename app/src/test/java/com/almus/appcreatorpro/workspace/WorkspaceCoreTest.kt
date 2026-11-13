package com.almus.appcreatorpro.workspace

import com.almus.appcreatorpro.ai.AiRole
import com.almus.appcreatorpro.ai.ChatTurn
import com.almus.appcreatorpro.ai.TurnState
import com.almus.appcreatorpro.data.storage.InMemoryKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceCoreTest {

    // ---- repository references
    @Test fun `owner slash repo and github links are understood`() {
        assertEquals(RepoReference.Ref("octocat", "Hello-World"), RepoReference.parse("octocat/Hello-World"))
        assertEquals(RepoReference.Ref("octocat", "Hello-World"), RepoReference.parse("https://github.com/octocat/Hello-World"))
        assertEquals(RepoReference.Ref("octocat", "Hello-World"), RepoReference.parse("https://github.com/octocat/Hello-World.git"))
        assertEquals(RepoReference.Ref("octocat", "Hello-World"), RepoReference.parse("github.com/octocat/Hello-World/tree/main/app?x=1#y"))
    }

    @Test fun `plain words and bad names are not references`() {
        assertNull(RepoReference.parse("android app"))
        assertNull(RepoReference.parse("single"))
        assertNull(RepoReference.parse("a/b/c"))
        assertNull(RepoReference.parse("-bad/repo"))
        assertNull(RepoReference.parse("owner/.."))
        assertNull(RepoReference.parse("owner/re po"))
        assertNull(RepoReference.parse(""))
    }

    // ---- plans and stages
    private fun turn(id: String, role: AiRole, text: String, state: TurnState = TurnState.DONE) = ChatTurn(id, role, text, state)
    private val plan = "${WorkspacePlans.PLAN_OPEN}\nGoal: nicer UI\n1. Update theme\n${WorkspacePlans.PLAN_CLOSE}"

    @Test fun `a plan needs both markers`() {
        assertEquals("Goal: x", WorkspacePlans.extract("intro\n${WorkspacePlans.PLAN_OPEN}\nGoal: x\n${WorkspacePlans.PLAN_CLOSE}\nok?"))
        assertNull(WorkspacePlans.extract("${WorkspacePlans.PLAN_OPEN}\nGoal: cut off"))
        assertNull(WorkspacePlans.extract("no plan here"))
        val split = WorkspacePlans.split("before ${WorkspacePlans.PLAN_OPEN} half")
        assertNull(split.plan)
        assertFalse(split.before.contains(WorkspacePlans.PLAN_OPEN))
    }

    @Test fun `stage follows the conversation and approval needs a complete plan`() {
        val q = turn("1", AiRole.USER, "I want to improve the UI")
        val questions = turn("2", AiRole.MODEL, "I'll inspect the UI. Which screens?")
        assertEquals(WorkspaceStage.DISCUSSING, WorkspacePlans.stageOf(emptyList()))
        assertEquals(WorkspaceStage.DISCUSSING, WorkspacePlans.stageOf(listOf(q, questions)))

        val planned = listOf(q, questions, turn("3", AiRole.USER, "all screens"), turn("4", AiRole.MODEL, plan))
        assertEquals(WorkspaceStage.PLAN_READY, WorkspacePlans.stageOf(planned))
        assertTrue(WorkspacePlans.currentPlan(planned)!!.contains("Update theme"))

        val approved = planned + turn("5", AiRole.USER, WorkspacePlans.APPROVAL_TEXT)
        assertEquals(WorkspaceStage.APPROVED, WorkspacePlans.stageOf(approved))
        assertTrue(WorkspacePlans.currentPlan(approved)!!.contains("Update theme"))

        // A new message after approval means the approval no longer applies.
        val changed = approved + turn("6", AiRole.USER, "also add dark mode")
        assertEquals(WorkspaceStage.DISCUSSING, WorkspacePlans.stageOf(changed))
        assertNull(WorkspacePlans.currentPlan(changed))

        // An approval line that does not follow a plan is ignored.
        assertEquals(WorkspaceStage.DISCUSSING, WorkspacePlans.stageOf(listOf(q, questions, turn("9", AiRole.USER, WorkspacePlans.APPROVAL_TEXT))))
        // A plan that was cut off or failed is never approvable.
        assertEquals(WorkspaceStage.DISCUSSING, WorkspacePlans.stageOf(listOf(q, turn("4", AiRole.MODEL, plan, TurnState.CANCELLED))))
    }

    @Test fun `agent goal carries the plan and respects the size limit`() {
        val ws = workspace()
        val goal = WorkspacePlans.agentGoal(ws, "step ".repeat(2000), 500)
        assertTrue(goal.length <= 500)
        assertTrue(goal.contains("octocat/hello"))
        assertTrue(WorkspacePlans.agentGoal(ws, "short plan", 4000).endsWith("short plan"))
    }

    // ---- store
    private fun workspace(id: String = "w1", opened: Long = 10L, branch: String = "main") = RepoWorkspace(
        id = id, owner = "octocat", repoName = "hello", branch = branch, commitSha = "abcdef1234567", commitTitle = "Init",
        isPrivate = true, defaultBranch = "main", description = null, htmlUrl = "https://github.com/octocat/hello",
        projectId = "p-$id", importedAtMillis = 1L, lastOpenedAtMillis = opened, lastSyncedAtMillis = 1L,
    )

    @Test fun `store keeps workspaces ordered by last opened and survives a restart`() {
        val kv = InMemoryKeyValueStore()
        var now = 100L
        val store = WorkspaceStore(kv) { now }
        store.save(workspace("a", opened = 10L))
        store.save(workspace("b", opened = 20L, branch = "dev"))
        assertEquals(listOf("b", "a"), store.workspaces.value.map { it.id })
        now = 500L
        store.markOpened("a")
        assertEquals(listOf("a", "b"), store.recent(5).map { it.id })
        assertEquals("a", store.activeId())

        val again = WorkspaceStore(kv)
        assertEquals(listOf("a", "b"), again.workspaces.value.map { it.id })
        assertNotNull(again.find("OCTOCAT", "Hello", "dev"))
        assertEquals("b", again.findByProject("p-b")?.id)
        assertEquals(true, again.get("a")?.isPrivate)
        assertNull(again.get("a")?.description)
    }

    @Test fun `removing forgets the record only and clears the active id`() {
        val kv = InMemoryKeyValueStore()
        val store = WorkspaceStore(kv)
        store.save(workspace("a"))
        store.setActive("a")
        store.remove("a")
        assertTrue(store.workspaces.value.isEmpty())
        assertNull(store.activeId())
        assertTrue(WorkspaceStore(kv).workspaces.value.isEmpty())
    }

    @Test fun `a damaged record is skipped instead of breaking the list`() {
        val kv = InMemoryKeyValueStore()
        kv.putString("workspace.bad", "{not json")
        kv.putString("workspace.partial", "{\"id\":\"x\"}")
        val store = WorkspaceStore(kv)
        store.save(workspace("ok"))
        assertEquals(listOf("ok"), WorkspaceStore(kv).workspaces.value.map { it.id })
    }

    @Test fun `stored text never contains an access token field`() {
        val text = WorkspaceStore.encode(workspace())
        assertFalse(text.contains("token", ignoreCase = true))
    }

    // ---- analyzer
    @Test fun `analyzer finds compose, hints and key files without reading source text into the digest`() {
        val files = mapOf(
            "README.md" to "# Hello",
            "settings.gradle.kts" to "rootProject.name = \"x\"",
            "app/build.gradle.kts" to "dependencies { implementation(\"androidx.compose.material3:material3:1.2.0\")\n implementation(\"com.squareup.retrofit2:retrofit:2.9.0\") }",
            "app/src/main/AndroidManifest.xml" to "<manifest/>",
            "app/src/main/java/com/x/MainActivity.kt" to "import androidx.compose.runtime.Composable\nclass MainActivity",
            "app/src/main/java/com/x/ui/HomeScreen.kt" to "import androidx.compose.material3.Text",
            "app/src/main/java/com/x/ui/theme/Theme.kt" to "import androidx.compose.material3.MaterialTheme",
            "app/build/generated/Foo.kt" to "ignored",
            "app/google-services.json" to "{}",
        )
        val d = WorkspaceAnalyzer.analyze(files.keys.toList()) { files[it] }
        assertEquals("Jetpack Compose", d.uiToolkit)
        assertTrue(d.architectureHints.any { it.contains("Retrofit") })
        assertTrue(d.keyFiles.contains("README.md"))
        assertTrue(d.keyFiles.contains("app/src/main/AndroidManifest.xml"))
        assertTrue(d.keyFiles.size <= WorkspaceDigest.MAX_KEY_FILES)
        assertTrue(d.uiFiles.contains("app/src/main/java/com/x/ui/HomeScreen.kt"))
        assertFalse(d.uiFiles.any { it.contains("/build/") })
        assertFalse(d.render().contains("MaterialTheme"))
        assertEquals(7, d.fileCount) // build output and the credentials file are not counted
    }

    @Test fun `analyzer recognizes xml layouts and unreadable files do not break it`() {
        val paths = listOf("app/src/main/res/layout/activity_main.xml", "app/src/main/java/a/Main.java", "app/build.gradle")
        val d = WorkspaceAnalyzer.analyze(paths) { null }
        assertEquals("XML layouts (Views)", d.uiToolkit)
        assertEquals(1, d.javaFiles)
    }
}
