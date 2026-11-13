package com.almus.appcreatorpro.ai

import com.almus.appcreatorpro.domain.model.ProjectType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 24: project profile, prompts, follow-ups, suggestions, code review and the Smart mode of the chat engine. */
@OptIn(ExperimentalCoroutinesApi::class)
class Phase24AssistantTest {
    // ---- a small Compose project ---------------------------------------------------------------------------------------------
    private val vmSource = """
        package com.demo.app.ui

        import androidx.lifecycle.ViewModel
        import kotlinx.coroutines.flow.MutableStateFlow
        import kotlinx.coroutines.flow.StateFlow

        class LoginViewModel(
            private val repo: UserRepository,
        ) : ViewModel() {
            private val _state = MutableStateFlow(LoginState())
            val state: StateFlow<LoginState> = _state

            fun submit(name: String) {
                if (name.isBlank()) {
                    return
                }
                _state.value = LoginState(name)
            }
        }
    """.trimIndent()

    private val screenSource = """
        package com.demo.app.ui

        import androidx.compose.runtime.Composable
        import androidx.compose.material3.Text
        import androidx.compose.ui.Modifier

        /** Shows the name. */
        @Composable
        fun GreetingScreen(
            name: String,
            modifier: Modifier = Modifier,
        ) {
            Text(
                text = name,
                modifier = modifier,
            )
        }
    """.trimIndent()

    private val gradle = """
        plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") version "2.0.21" }
        android { namespace = "com.demo.app"; compileSdk = 35
            defaultConfig { minSdk = 26; targetSdk = 35 }
            buildFeatures { compose = true } }
        dependencies {
            implementation("androidx.compose.material3:material3:1.3.0")
            implementation("com.squareup.retrofit2:retrofit:2.11.0")
            implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
        }
    """.trimIndent()

    private fun composeFiles() = mapOf(
        "app/build.gradle.kts" to gradle,
        "app/src/main/AndroidManifest.xml" to "<manifest package=\"com.demo.app\"/>",
        "app/src/main/java/com/demo/app/ui/LoginViewModel.kt" to vmSource,
        "app/src/main/java/com/demo/app/ui/GreetingScreen.kt" to screenSource,
        "app/src/main/java/com/demo/app/data/UserRepository.kt" to "package com.demo.app.data\n\nclass UserRepository\n",
        "app/src/main/res/values/strings.xml" to "<resources/>",
        ".env" to "SECRET=abc",
    )

    private fun profileOf(files: Map<String, String>, type: ProjectType? = ProjectType.ANDROID_KOTLIN): ProjectProfile {
        val ff = FakeProjectFiles(files)
        return runBlocking { ProjectProfileProvider(ff, "Demo", type).profile() }
    }

    @Test fun `profile detects language toolkit package architecture libraries and conventions`() {
        val p = profileOf(composeFiles())
        assertEquals("Kotlin (Android)", p.language)
        assertEquals(UiToolkit.COMPOSE, p.uiToolkit)
        assertEquals("com.demo.app", p.packageName)
        assertTrue(p.architecture.any { it.startsWith("MVVM (ViewModel + StateFlow)") })
        assertTrue("Repository pattern" in p.architecture)
        assertTrue(p.layers.containsAll(listOf("ui", "data")))
        assertTrue(p.hasLibrary("Retrofit") && p.hasLibrary("Material 3") && p.hasLibrary("Kotlin coroutines"))
        assertFalse(p.hasLibrary("Room"))
        assertEquals("4 spaces", p.conventions.indent)
        assertEquals(true, p.conventions.trailingCommas)
        assertTrue(p.conventions.notes.any { it.contains("modifier: Modifier = Modifier") })
        assertTrue(p.conventions.notes.any { it.contains("_state") })
        assertTrue(p.sdk!!.contains("minSdk 26") && p.sdk!!.contains("compileSdk 35") && p.sdk!!.contains("Kotlin 2.0.21"))
        assertTrue(p.usesCompose && p.isKotlin && !p.isWeb)
    }

    @Test fun `profile never reads hidden or secret files and never renders file text`() {
        val p = profileOf(composeFiles())
        assertTrue(p.inspected.none { it.endsWith(".env") })
        val text = p.render()
        assertFalse(text.contains("SECRET")); assertFalse(text.contains("fun submit")); assertFalse(text.contains("MutableStateFlow(LoginState"))
        assertTrue(text.contains("Jetpack Compose") && text.contains("com.demo.app") && text.length <= ProjectProfile.MAX_RENDERED_CHARS)
    }

    @Test fun `xml views java and web projects are told apart`() {
        val xml = profileOf(
            mapOf(
                "app/build.gradle" to "android { namespace 'x' }\ndependencies { implementation 'com.google.android.material:material:1.9.0' }",
                "app/src/main/java/x/MainActivity.java" to "package x;\npublic class MainActivity extends AppCompatActivity {\n    void a() {\n        setContentView(R.layout.main);\n    }\n}\n",
                "app/src/main/res/layout/main.xml" to "<a android:id=\"@+id/btn_ok\"/><b android:id=\"@+id/btn_no\"/><c android:id=\"@+id/tv_title\"/>",
            ),
            ProjectType.ANDROID_JAVA,
        )
        assertEquals("Java (Android)", xml.language); assertEquals(UiToolkit.XML_VIEWS, xml.uiToolkit); assertTrue(xml.hasLibrary("Material Components"))
        assertTrue(xml.conventions.notes.any { it.contains("type prefix") })
        val web = profileOf(mapOf("index.html" to "<html></html>", "app.js" to "const a = 'x'; const b = 'y'; const c = 'z'; const d = 'w'; const e = 'v';"), ProjectType.WEB)
        assertTrue(web.isWeb); assertEquals(UiToolkit.WEB, web.uiToolkit)
        assertTrue(web.conventions.notes.any { it.contains("single quotes") })
    }

    @Test fun `an empty or unreadable project gives an unknown profile instead of failing`() {
        val p = profileOf(emptyMap(), null)
        assertEquals("unknown", p.language); assertEquals(UiToolkit.UNKNOWN, p.uiToolkit)
        assertTrue(p.render().contains("Language: unknown"))
    }

    @Test fun `the profile is cached and rebuilt when the files change`() = runBlocking {
        val ff = FakeProjectFiles(composeFiles())
        var now = 0L
        val provider = ProjectProfileProvider(ff, "Demo", ProjectType.ANDROID_KOTLIN, { now }, 1000L)
        val a = provider.profile()
        assertTrue(a === provider.profile())
        ff.files["app/src/main/java/com/demo/app/data/Extra.kt"] = "package com.demo.app.data\n"
        assertFalse(a === provider.profile())
        val b = provider.profile(); now = 5000L
        assertFalse(b === provider.profile())
    }

    // ---- prompts -----------------------------------------------------------------------------------------------------------------
    private fun req(intent: CodingIntent, lang: ChatLanguage = ChatLanguage.ENGLISH, paths: List<String> = emptyList(), follow: Boolean = false) =
        CodingRequest(intent, lang, profileOf(composeFiles()), paths, followUp = follow, carriedOver = if (follow) paths else emptyList())

    @Test fun `the instruction block carries project facts and the rules`() {
        val t = CodingPrompts.build(req(CodingIntent.UI_COMPONENT))
        assertTrue(t.contains("Jetpack Compose") && t.contains("com.demo.app") && t.contains("Retrofit"))
        assertTrue(t.contains("File: <project-relative path>") && t.contains("COMPLETE files"))
        assertTrue(t.contains("@Preview") && t.contains("Modifier"))
        assertTrue(t.contains("Never write secrets"))
        assertFalse(t.contains("No file is attached")) // creating something needs no existing file
    }

    @Test fun `every intent has its own task text and a missing file is called out`() {
        val texts = CodingIntent.entries.map { CodingPrompts.intentRules(it, null, false) }
        assertEquals(CodingIntent.entries.size, texts.toSet().size)
        assertTrue(CodingPrompts.build(req(CodingIntent.FIND_BUGS)).contains("No file is attached"))
        assertFalse(CodingPrompts.build(req(CodingIntent.FIND_BUGS, paths = listOf("a.kt"))).contains("No file is attached"))
        assertTrue(CodingPrompts.build(req(CodingIntent.API_INTEGRATION)).contains("INTERNET permission"))
        assertTrue(CodingPrompts.build(req(CodingIntent.OPTIMIZE)).contains("Behaviour must stay identical"))
    }

    @Test fun `hausa keeps programming terms in english`() {
        val t = CodingPrompts.build(req(CodingIntent.EXPLAIN, ChatLanguage.HAUSA))
        assertTrue(t.contains("Hausa") && t.contains("ViewModel") && t.contains("never translate code"))
        assertFalse(CodingPrompts.build(req(CodingIntent.EXPLAIN)).contains("Explain in simple, clear Hausa"))
    }

    @Test fun `a follow up tells the model to return complete files and lists the carried files`() {
        val t = CodingPrompts.build(req(CodingIntent.EDIT, paths = listOf("a/B.kt"), follow = true))
        assertTrue(t.contains("continues the conversation") && t.contains("COMPLETE updated file") && t.contains("a/B.kt"))
    }

    @Test fun `skipped files are reported to the model`() {
        val r = req(CodingIntent.EXPLAIN).copy(skipped = listOf(SkippedFile(".env", "secret file")))
        assertTrue(CodingPrompts.build(r).contains(".env (secret file)"))
    }

    @Test fun `the unknown project still produces a usable block`() {
        val t = CodingPrompts.build(CodingRequest(CodingIntent.GENERATE, ChatLanguage.ENGLISH, null, emptyList()))
        assertTrue(t.contains("Use the language and framework of the attached files"))
    }

    // ---- follow-up -----------------------------------------------------------------------------------------------------------
    private fun turn(id: String, role: AiRole, text: String, paths: List<String> = emptyList(), intent: CodingIntent? = null, state: TurnState = TurnState.DONE) =
        ChatTurn(id, role, text, state, contextPaths = paths, intent = intent)

    private val history = listOf(
        turn("1", AiRole.USER, "Explain this", paths = listOf("a/Login.kt", "a/Gone.kt")),
        turn("2", AiRole.MODEL, "Sure.\n\nFile: a/Login.kt\n```kotlin\nclass Login\n```\n\nFile: a/New.kt\n```kotlin\nclass New\n```", intent = CodingIntent.EXPLAIN),
    )
    private val known = setOf("a/Login.kt", "a/Other.kt")

    @Test fun `a follow up without files gets the files of the last exchange that still exist`() {
        val c = FollowUpResolver.carryOver("make it blue", history, emptyList(), known, CodingIntent.EDIT)
        assertEquals(listOf("a/Login.kt"), c.paths); assertEquals(listOf("a/Login.kt"), c.inherited); assertTrue(c.isFollowUp)
    }

    @Test fun `files the user attached win and nothing is carried over`() {
        val c = FollowUpResolver.carryOver("make it blue", history, listOf("a/Other.kt"), known, CodingIntent.EDIT)
        assertEquals(listOf("a/Other.kt"), c.paths); assertTrue(c.inherited.isEmpty())
    }

    @Test fun `a new creation or a first message is not a follow up`() {
        assertFalse(FollowUpResolver.carryOver("Create a settings screen", history, emptyList(), known, CodingIntent.UI_COMPONENT).isFollowUp)
        assertFalse(FollowUpResolver.carryOver("make it blue", emptyList(), emptyList(), known, CodingIntent.EDIT).isFollowUp)
        assertFalse(FollowUpResolver.isFollowUp("What is a ViewModel and when do I use one in a big app like mine today", history, CodingIntent.QUESTION))
    }

    @Test fun `back references in hausa count too and the limit holds`() {
        assertTrue(FollowUpResolver.isFollowUp("Ka kuma ƙara shi a nan", history, CodingIntent.GENERATE))
        val many = listOf(turn("1", AiRole.USER, "x", paths = (1..30).map { "f$it.kt" }), history[1])
        val c = FollowUpResolver.carryOver("fix it", many, emptyList(), (1..30).map { "f$it.kt" }.toSet(), CodingIntent.EDIT, limit = 5)
        assertEquals(5, c.paths.size)
    }

    @Test fun `labelled paths come from code blocks only and the last intent is found`() {
        assertEquals(listOf("a/Login.kt", "a/New.kt"), FollowUpResolver.labelledPaths(history[1].text))
        assertEquals(CodingIntent.EXPLAIN, FollowUpResolver.lastIntent(history))
        assertNull(FollowUpResolver.lastIntent(emptyList()))
    }

    // ---- suggestions -----------------------------------------------------------------------------------------------------------
    @Test fun `every suggestion is read back as the intent and language it was made for`() {
        val profile = profileOf(composeFiles())
        val contexts = listOf(
            Triple(profile, emptyList<ChatTurn>(), emptyList<String>()),
            Triple(null, emptyList(), emptyList()),
            Triple(profile, history, emptyList()),
            Triple(profile, emptyList(), listOf("app/src/main/java/com/demo/app/ui/LoginViewModel.kt")),
            Triple(profile, emptyList(), listOf("a.kt", "b.kt")),
        ) + CodingIntent.entries.map { i -> Triple(profile, listOf(turn("1", AiRole.USER, "q"), turn("2", AiRole.MODEL, "```kotlin\nx\n```", intent = i)), emptyList<String>()) }
        val problems = ArrayList<String>()
        var count = 0
        for ((pr, turns, att) in contexts) for (lang in ChatLanguage.entries) {
            for (s in SuggestionEngine.suggest(lang, pr, turns, att, limit = 10)) {
                count++
                val g = IntentDetector.detect(s.prompt, att.isNotEmpty())
                if (g.intent != s.intent) problems += "[$lang] \"${s.prompt}\": wanted ${s.intent}, read ${g.intent}"
                if (g.language != lang) problems += "[$lang] \"${s.prompt}\": language read as ${g.language}"
            }
        }
        assertTrue(count > 40)
        assertEquals(problems.joinToString("\n"), emptyList<String>(), problems)
    }

    @Test fun `suggestions follow the project and the conversation`() {
        val compose = profileOf(composeFiles())
        val start = SuggestionEngine.suggest(ChatLanguage.ENGLISH, compose, emptyList(), emptyList(), 10)
        assertTrue(start.any { it.prompt.contains("login screen") } && start.any { it.prompt.contains("composable") })
        assertTrue(start.any { it.prompt.contains("API endpoint") }) // the project already has Retrofit
        val web = profileOf(mapOf("index.html" to "<p/>"), ProjectType.WEB)
        assertTrue(SuggestionEngine.suggest(ChatLanguage.ENGLISH, web, emptyList(), emptyList(), 10).any { it.prompt.contains("login page") })
        assertTrue(SuggestionEngine.suggest(ChatLanguage.ENGLISH, null, history, emptyList()).any { it.intent == CodingIntent.FIND_BUGS })
        val withFile = SuggestionEngine.suggest(ChatLanguage.ENGLISH, compose, history, listOf("a/Login.kt"))
        assertTrue(withFile.all { it.prompt.contains("Login.kt") })
        assertEquals(2, SuggestionEngine.suggest(ChatLanguage.ENGLISH, null, history, emptyList(), limit = 2).size)
    }

    @Test fun `file suggestions understand hausa words and stop when files are attached`() {
        val paths = listOf("app/src/main/java/com/demo/app/ui/LoginScreen.kt", "app/src/main/java/com/demo/app/data/UserRepository.kt")
        assertEquals(paths[0], SuggestionEngine.suggestFiles("Gyara shafin shiga", paths, emptyList()).first())
        assertTrue(SuggestionEngine.suggestFiles("Gyara shafin shiga", paths, listOf("x.kt")).isEmpty())
    }

    // ---- reviewing generated code ----------------------------------------------------------------------------------------------
    private fun answer(vararg blocks: Pair<String?, String>): ChatTurn {
        val text = blocks.joinToString("\n\n") { (path, code) -> (if (path != null) "File: $path\n" else "") + "```kotlin\n$code\n```" }
        return ChatTurn("t1", AiRole.MODEL, text, TurnState.DONE)
    }

    private val existing = (1..30).joinToString("\n") { "val line$it = $it" } + "\n"

    @Test fun `a new file and a changed file are told apart and checked`() = runBlocking {
        val ff = FakeProjectFiles(mapOf("app/src/Old.kt" to "package a\n\nfun f() {\n    println(1)\n}\n"))
        val r = GeneratedCodeReviewer.review(answer("app/src/New.kt" to "package a\n\nclass New {\n}", "app/src/Old.kt" to "package a\n\nfun f() {\n    println(2)\n}"), ff, 1L)!!
        val byPath = r.files.associateBy { it.path }
        assertTrue(byPath.getValue("app/src/New.kt").isNew && !byPath.getValue("app/src/Old.kt").isNew)
        assertEquals(ReviewVerdict.OK, byPath.getValue("app/src/New.kt").verdict)
        assertEquals(1, byPath.getValue("app/src/Old.kt").change.added); assertEquals(1, byPath.getValue("app/src/Old.kt").change.removed)
        assertEquals(2, r.acceptablePaths.size)
    }

    @Test fun `broken brackets and placeholders block a file`() = runBlocking {
        val ff = FakeProjectFiles()
        val r = GeneratedCodeReviewer.review(answer("a/Bad.kt" to "package a\n\nclass Bad {\n    fun f() {\n", "a/Lazy.kt" to "package a\n\nfun f() {\n    // ...\n}"), ff, 1L)!!
        assertEquals(ReviewVerdict.BLOCKED, r.files.first { it.path == "a/Bad.kt" }.verdict)
        assertEquals(ReviewVerdict.BLOCKED, r.files.first { it.path == "a/Lazy.kt" }.verdict)
        assertTrue(r.acceptablePaths.isEmpty())
        assertNull(GeneratedCodeReviewer.changeSetFor(r, setOf("a/Bad.kt", "a/Lazy.kt"), 2L))
    }

    @Test fun `a fragment that would replace a big file is warned about and a big rewrite is not blocked`() = runBlocking {
        val ff = FakeProjectFiles(mapOf("a/Big.kt" to existing))
        val frag = GeneratedCodeReviewer.review(answer("a/Big.kt" to "val line1 = 1\nval line2 = 2"), ff, 1L)!!.files.single()
        assertEquals(ReviewVerdict.WARNINGS, frag.verdict); assertTrue(frag.canAccept)
        assertTrue(frag.problems.any { it.contains("only a part of the file") })
        val rewrite = (1..30).joinToString("\n") { "val other$it = $it" }
        val rw = GeneratedCodeReviewer.review(answer("a/Big.kt" to rewrite), ff, 1L)!!.files.single()
        assertTrue(rw.canAccept)
    }

    @Test fun `only accepted files become a change set and nothing is written`() = runBlocking {
        val ff = FakeProjectFiles(mapOf("a/Old.kt" to "package a\n"))
        val r = GeneratedCodeReviewer.review(answer("a/One.kt" to "package a\n\nclass One", "a/Two.kt" to "package a\n\nclass Two"), ff, 1L)!!
        val set = GeneratedCodeReviewer.changeSetFor(r, setOf("a/Two.kt", "not/in/review.kt"), 9L)!!
        assertEquals(listOf("a/Two.kt"), set.changes.map { it.path })
        assertTrue(ff.writes.isEmpty())
        assertNull(GeneratedCodeReviewer.changeSetFor(r, emptySet(), 9L))
    }

    @Test fun `no code block means no review and an inline file header names the file`() = runBlocking {
        val ff = FakeProjectFiles()
        assertNull(GeneratedCodeReviewer.review(ChatTurn("t", AiRole.MODEL, "just words", TurnState.DONE), ff, 1L))
        val r = GeneratedCodeReviewer.review(ChatTurn("t", AiRole.MODEL, "```kotlin\n// File: a/Hdr.kt\npackage a\n\nclass Hdr\n```", TurnState.DONE), ff, 1L)!!
        assertEquals("a/Hdr.kt", r.files.single().path)
        assertFalse(r.files.single().change.after.contains("File:"))
    }

    @Test fun `a secret in generated code is rejected with a reason and not offered`() = runBlocking {
        val ff = FakeProjectFiles()
        val r = GeneratedCodeReviewer.review(answer(".env" to "KEY=1", "a/Ok.kt" to "package a\n\nclass Ok"), ff, 1L)!!
        assertEquals(listOf("a/Ok.kt"), r.files.map { it.path }); assertTrue(r.rejected.any { it.path == ".env" })
    }

    // ---- the chat engine in Smart mode -----------------------------------------------------------------------------------------------
    private class Sent { val paths = mutableListOf<List<String>>() }

    private fun TestScope.smart(backend: FakeAiBackend, sent: Sent, files: Map<String, String> = composeFiles(), store: ChatHistoryStore = InMemoryChatHistoryStore()): ChatEngine {
        var n = 0
        val ff = FakeProjectFiles(files)
        val assistant = ChatAssistant.forProject(ProjectProfileProvider(ff, "Demo", ProjectType.ANDROID_KOTLIN), ff)
        val ctx: suspend (List<String>) -> ContextBundle? = { p ->
            sent.paths += p
            ContextBundle("Demo", "Kotlin", emptyList(), emptyList(), 0, p.map { ContextFile(it, "// text of $it", false, 0) }, emptyList())
        }
        return ChatEngine("p1", backend, store, ctx, this, clockMillis = { 5L }, newId = { "id${n++}" }, assistant = assistant)
    }

    @Test fun `smart mode understands the request and sends the project facts and the right task`() = runTest {
        val backend = FakeAiBackend().also { it.answerText("Here is the screen") }
        val e = smart(backend, Sent())
        assertTrue(e.send("Create a modern login screen.", ChatMode.AUTO)); advanceUntilIdle()
        val req = backend.requests.single()
        assertEquals(AiTaskKind.GENERATE_CODE, req.task)
        val last = req.messages.last().content
        assertTrue(last.contains("Jetpack Compose") && last.contains("com.demo.app") && last.contains("UI component"))
        assertTrue(last.contains("USER REQUEST:\nCreate a modern login screen."))
        val answer = e.state.value.turns.last()
        assertEquals(CodingIntent.UI_COMPONENT, answer.intent); assertEquals(ChatLanguage.ENGLISH, answer.language)
        assertEquals("Create a modern login screen.", e.state.value.turns.first().text) // the user's bubble is unchanged
    }

    @Test fun `smart mode answers in hausa mode and routes an explain request`() = runTest {
        val backend = FakeAiBackend().also { it.answerText("Wannan code yana...") }
        val sent = Sent()
        val e = smart(backend, sent)
        e.send("Bayyana min wannan code", ChatMode.AUTO, listOf("app/src/main/java/com/demo/app/ui/GreetingScreen.kt")); advanceUntilIdle()
        assertEquals(AiTaskKind.EXPLAIN, backend.requests.single().task)
        assertTrue(backend.requests.single().messages.last().content.contains("Keep programming terms in English"))
        assertEquals(ChatLanguage.HAUSA, e.state.value.turns.last().language)
        assertEquals(listOf(listOf("app/src/main/java/com/demo/app/ui/GreetingScreen.kt")), sent.paths)
    }

    @Test fun `smart mode picks edit_code for one file and generate_code for several`() = runTest {
        val backend = FakeAiBackend().also { it.answerText("ok") }
        val e = smart(backend, Sent())
        val a = "app/src/main/java/com/demo/app/ui/GreetingScreen.kt"; val b = "app/src/main/java/com/demo/app/ui/LoginViewModel.kt"
        e.send("Optimize this", ChatMode.AUTO, listOf(a)); advanceUntilIdle()
        e.send("Refactor these", ChatMode.AUTO, listOf(a, b)); advanceUntilIdle()
        e.send("Optimize this", ChatMode.AUTO); advanceUntilIdle() // follow-up of the previous exchange: its files are carried over
        assertEquals(listOf(AiTaskKind.EDIT_CODE, AiTaskKind.GENERATE_CODE, AiTaskKind.GENERATE_CODE), backend.requests.map { it.task })
    }

    @Test fun `a follow up carries the files over and tells the user`() = runTest {
        val backend = FakeAiBackend().also { it.answerText("Done.\n\nFile: app/src/main/java/com/demo/app/ui/GreetingScreen.kt\n```kotlin\nx\n```") }
        val sent = Sent()
        val e = smart(backend, sent)
        val a = "app/src/main/java/com/demo/app/ui/GreetingScreen.kt"
        e.send("Explain this", ChatMode.AUTO, listOf(a)); advanceUntilIdle()
        e.send("now make it blue", ChatMode.AUTO); advanceUntilIdle()
        assertEquals(listOf(listOf(a), listOf(a)), sent.paths)
        val second = e.state.value.turns.filter { it.role == AiRole.USER }.last()
        assertTrue(second.inheritedContext); assertEquals(listOf(a), second.contextPaths)
        assertTrue(backend.requests.last().messages.last().content.contains("continues the conversation"))
        assertEquals(AiTaskKind.EDIT_CODE, backend.requests.last().task)
    }

    @Test fun `smart mode without a project still works`() = runTest {
        val backend = FakeAiBackend().also { it.answerText("ok") }
        var n = 0
        val e = ChatEngine("p1", backend, InMemoryChatHistoryStore(), { null }, this, clockMillis = { 5L }, newId = { "id${n++}" })
        e.send("Write a function that sorts users", ChatMode.AUTO); advanceUntilIdle()
        assertEquals(AiTaskKind.GENERATE_CODE, backend.requests.single().task)
        assertTrue(backend.requests.single().messages.last().content.contains("Function generation"))
    }

    @Test fun `the old modes send exactly what they sent before`() = runTest {
        val backend = FakeAiBackend().also { it.answerText("ok") }
        val e = smart(backend, Sent())
        e.send("Create a login screen", ChatMode.ASK); advanceUntilIdle()
        e.send("hello", ChatMode.EXPLAIN); advanceUntilIdle()
        assertEquals(listOf(AiTaskKind.CHAT, AiTaskKind.EXPLAIN), backend.requests.map { it.task })
        assertEquals("Create a login screen", backend.requests[0].messages.single().content)
        assertFalse(backend.requests[1].messages.any { it.content.contains("PROJECT PROFILE") })
        assertNull(e.state.value.turns.last().intent)
    }

    @Test fun `a failing profile does not stop the request`() = runTest {
        val backend = FakeAiBackend().also { it.answerText("ok") }
        var n = 0
        val assistant = ChatAssistant(profile = { error("boom") }, knownPaths = { error("boom") })
        val e = ChatEngine("p1", backend, InMemoryChatHistoryStore(), { null }, this, clockMillis = { 5L }, newId = { "id${n++}" }, assistant = assistant)
        e.send("Explain this", ChatMode.AUTO); advanceUntilIdle()
        assertEquals(TurnState.DONE, e.state.value.turns.last().state)
    }

    @Test fun `intent language and carried files survive saving and loading`() {
        val turns = listOf(
            ChatTurn("1", AiRole.USER, "q", TurnState.DONE, mode = ChatMode.AUTO, contextPaths = listOf("a.kt"), inheritedContext = true),
            ChatTurn("2", AiRole.MODEL, "a", TurnState.DONE, intent = CodingIntent.FIND_BUGS, language = ChatLanguage.HAUSA),
        )
        val back = ChatTurns.fromJson(ChatTurns.toJson(turns))
        assertEquals(turns, back)
        // a history saved before Phase 24 has none of the new fields
        val oldJson = ChatTurns.toJson(listOf(ChatTurn("1", AiRole.USER, "hi", TurnState.DONE))).replace(Regex(""","(intent|lang|inherited)":[^,}]*"""), "")
        assertFalse(oldJson.contains("inherited"))
        val old = ChatTurns.fromJson(oldJson).single()
        assertNull(old.intent); assertNull(old.language); assertFalse(old.inheritedContext)
    }

    @Test fun `previewIntent shows what smart mode would do`() = runTest {
        var n = 0
        val e = ChatEngine("p1", FakeAiBackend(), InMemoryChatHistoryStore(), { null }, this, clockMillis = { 5L }, newId = { "id${n++}" })
        assertEquals(CodingIntent.FIND_BUGS, e.previewIntent("why does it crash").intent)
        assertNotNull(e.previewIntent("x"))
    }

    @Test fun `apply to file creates new files and modifies existing ones`() = runBlocking {
        val ff = FakeProjectFiles(mapOf("a/Old.kt" to "package a\n"))
        val r = ChatApply.changeSet(answer("a/Old.kt" to "package a\n\nclass X", "a/Fresh.kt" to "package a\n\nclass Y"), ff, 1L)!!
        val kinds = r.changeSet!!.changes.associate { it.path to it.kind }
        assertEquals(ChangeKind.MODIFY, kinds["a/Old.kt"]); assertEquals(ChangeKind.CREATE, kinds["a/Fresh.kt"])
        assertTrue(r.changeSet!!.warnings.none { it.contains("does not exist") })
    }
}
