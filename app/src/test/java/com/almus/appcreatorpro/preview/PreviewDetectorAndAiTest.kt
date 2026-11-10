package com.almus.appcreatorpro.preview

import com.almus.appcreatorpro.ai.AiTaskKind
import com.almus.appcreatorpro.ai.FakeAiBackend
import com.almus.appcreatorpro.ai.FakeProjectFiles
import com.almus.appcreatorpro.ai.Parsed
import com.almus.appcreatorpro.domain.model.ProjectType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PreviewDetectorAndAiTest {
    private fun json(v: Any?) = com.almus.appcreatorpro.service.github.MiniJson.stringify(v)
    private fun answer(vararg files: Pair<String, String>, summary: String = "A screen") =
        json(mapOf("summary" to summary, "files" to files.map { mapOf("path" to it.first, "content" to it.second) }, "notes" to listOf("note")))

    // ---- detector

    @Test fun `targets are found for web, xml, compose and flutter, with the index page first`() {
        val paths = listOf("docs/a.html", "index.html", "build/x.html", "app/src/main/res/layout/activity_main.xml", "app/src/main/res/layout-land/activity_main.xml", "app/src/main/res/values/strings.xml", "pubspec.yaml", "lib/main.dart", ".env")
        val compose = mapOf("app/src/main/java/A.kt" to "@Composable\nfun Plain() {}\n@Preview\n@Composable\nfun Shown() {}")
        val t = PreviewDetector.detect(paths, compose)
        assertEquals(listOf("index.html", "docs/a.html"), t.filter { it.kind == PreviewKind.WEB }.map { it.path })
        assertEquals(listOf("app/src/main/res/layout/activity_main.xml", "app/src/main/res/layout-land/activity_main.xml"), t.filter { it.kind == PreviewKind.ANDROID_XML }.map { it.path })
        assertEquals(listOf("Shown", "Plain"), t.filter { it.kind == PreviewKind.ANDROID_COMPOSE }.map { it.function })
        assertEquals("lib/main.dart", t.single { it.kind == PreviewKind.FLUTTER }.path)
        assertTrue(t.none { it.path.startsWith("build/") })
        assertEquals(t.map { it.id }.toSet().size, t.size)
    }

    @Test fun `the default target depends on the project type`() {
        val t = PreviewDetector.detect(
            listOf("index.html", "app/src/main/res/layout/a.xml"), mapOf("A.kt" to "@Preview\n@Composable\nfun P() {}"),
        )
        assertEquals(PreviewKind.WEB, PreviewDetector.defaultTarget(t, ProjectType.WEB)!!.kind)
        assertEquals(PreviewKind.ANDROID_COMPOSE, PreviewDetector.defaultTarget(t, ProjectType.ANDROID_KOTLIN)!!.kind)
        val xmlOnly = PreviewDetector.detect(listOf("index.html", "app/src/main/res/layout/a.xml"), emptyMap())
        assertEquals(PreviewKind.ANDROID_XML, PreviewDetector.defaultTarget(xmlOnly, ProjectType.ANDROID_JAVA)!!.kind)
        assertNull(PreviewDetector.defaultTarget(emptyList(), ProjectType.WEB))
    }

    @Test fun `only previewable files count as relevant`() {
        assertTrue(PreviewDetector.isPreviewRelevant("index.html"))
        assertTrue(PreviewDetector.isPreviewRelevant("app/src/main/res/layout/a.xml"))
        assertTrue(PreviewDetector.isPreviewRelevant("A.kt"))
        assertFalse(PreviewDetector.isPreviewRelevant("AndroidManifest.xml"))
        assertFalse(PreviewDetector.isPreviewRelevant("logo.png"))
        assertFalse(PreviewDetector.isPreviewRelevant("build/a.html"))
        assertFalse(PreviewDetector.isPreviewRelevant(".env"))
    }

    @Test fun `the fingerprint changes with content and ignores irrelevant files`() = runTest {
        val files = FakeProjectFiles(mapOf("index.html" to "<p>a</p>", "notes.txt" to "x"))
        val a = PreviewDetector.fingerprint(files)
        assertEquals(a, PreviewDetector.fingerprint(files))
        files.files["notes.txt"] = "changed"
        assertEquals(a, PreviewDetector.fingerprint(files))
        files.files["index.html"] = "<p>b</p>"
        assertNotEquals(a, PreviewDetector.fingerprint(files))
    }

    // ---- native readiness

    @Test fun `a project without a screenshot renderer is told what is missing`() {
        val paths = setOf("app/src/main/res/layout/a.xml", "app/build.gradle.kts")
        val r = NativeRenderReadinessChecker.check(mapOf("app/build.gradle.kts" to "plugins { id(\"com.android.application\") }"), paths)
        assertFalse(r.projectReady)
        assertNull(r.renderer)
        assertTrue(r.items.first { it.label == "Screenshot renderer in the build" }.detail.contains("Paparazzi"))
        assertFalse(r.items.first { it.label == NativeRenderReadiness.CLOUD_JOB }.ok)
    }

    @Test fun `a prepared project is project-ready, but the cloud job is still missing`() {
        val gradle = "plugins { id(\"app.cash.paparazzi\") version \"1.3.4\" }\nandroid { buildFeatures { compose = true } }"
        val kt = "@Preview\n@Composable\nfun A() {}\n@Preview\n@Composable\nfun B() {}"
        val r = NativeRenderReadinessChecker.check(mapOf("app/build.gradle.kts" to gradle, "app/src/main/java/A.kt" to kt), setOf("app/src/test/java/ATest.kt"))
        assertEquals("Paparazzi", r.renderer)
        assertTrue(r.projectReady)
        assertTrue(r.items.first { it.label == "@Preview functions" }.detail.contains("2"))
        assertTrue(r.items.any { it.label == NativeRenderReadiness.CLOUD_JOB && !it.ok })
    }

    @Test fun `the other renderers are recognized and compose without previews is flagged`() {
        assertEquals("Compose Preview Screenshot Testing", NativeRenderReadinessChecker.check(mapOf("a.gradle.kts" to "plugins { id(\"com.android.compose.screenshot\") }"), emptySet()).renderer)
        assertEquals("Roborazzi", NativeRenderReadinessChecker.check(mapOf("a.gradle.kts" to "id(\"io.github.takahirom.roborazzi\")"), emptySet()).renderer)
        val r = NativeRenderReadinessChecker.check(mapOf("A.kt" to "@Composable\nfun A() {}"), emptySet())
        assertFalse(r.items.first { it.label == "@Preview functions" }.ok)
    }

    @Test fun `no native renderer claims to be available`() {
        assertTrue(UnavailableNativeRender.availability() is NativeRenderAvailability.Unavailable)
        assertTrue(PreviewEngines.default().forKind(PreviewKind.FLUTTER) is FlutterPreviewEngine)
        assertEquals(PreviewKind.values().toSet(), listOf(PreviewKind.WEB, PreviewKind.ANDROID_XML, PreviewKind.ANDROID_COMPOSE, PreviewKind.FLUTTER).toSet())
        assertEquals(PreviewFidelity.WEB_ENGINE, PreviewKind.WEB.fidelity)
        assertEquals(PreviewFidelity.APPROXIMATE, PreviewKind.ANDROID_XML.fidelity)
        assertEquals(PreviewFidelity.APPROXIMATE, PreviewKind.ANDROID_COMPOSE.fidelity)
        assertEquals(PreviewFidelity.UNAVAILABLE, PreviewKind.FLUTTER.fidelity)
    }

    // ---- devices and config

    @Test fun `the device catalog is consistent and custom sizes are clamped`() {
        assertEquals(DeviceCatalog.all.map { it.id }.toSet().size, DeviceCatalog.all.size)
        assertNotNull(DeviceCatalog.byId("phone-medium"))
        assertEquals("phone-medium", DeviceCatalog.default.id)
        assertNull(DeviceCatalog.byId("nope"))
        assertTrue(DeviceCatalog.all.any { it.category == DeviceCategory.TABLET } && DeviceCatalog.all.any { it.category == DeviceCategory.FOLDABLE })
        val c = DeviceCatalog.custom(10, 99999)
        assertEquals(DeviceCatalog.MIN_DP, c.widthDp)
        assertEquals(DeviceCatalog.MAX_DP, c.heightDp)
    }

    private fun assertNotNull(a: Any?) = org.junit.Assert.assertNotNull(a)

    @Test fun `orientation swaps the size and the frame takes space from the content`() {
        val p = PreviewConfig(device = DeviceCatalog.default)
        assertEquals(412, p.widthDp)
        assertEquals(915, p.heightDp)
        assertEquals(915 - 24 - 24, p.contentHeightDp)
        val l = p.copy(landscape = true)
        assertEquals(915, l.widthDp)
        assertEquals(412, l.heightDp)
        assertEquals(915, p.copy(showFrame = false).contentHeightDp)
        assertFalse(p.allowInternet)
        assertTrue(p.autoRefresh)
    }

    @Test fun `custom width resets orientation, text and zoom are clamped`() {
        val c = PreviewConfig(landscape = true).withCustomSize(500, 700)
        assertFalse(c.landscape)
        assertEquals(500, c.widthDp)
        assertEquals(2.0f, c.withFontScale(9f).fontScale, 0.001f)
        assertEquals(0.85f, c.withFontScale(0.1f).fontScale, 0.001f)
        assertEquals(300, c.withZoom(9999).zoomPercent)
        assertNull(c.withZoom(null).zoomPercent)
    }

    // ---- ai: paths, parser

    @Test fun `file rules per target`() {
        assertNull(UiPathRules.check(PreviewKind.WEB, "index.html"))
        assertNull(UiPathRules.check(PreviewKind.WEB, "css/site.css"))
        assertTrue(UiPathRules.check(PreviewKind.WEB, "run.sh") != null)
        assertTrue(UiPathRules.check(PreviewKind.WEB, "../x.html") != null)
        assertTrue(UiPathRules.check(PreviewKind.WEB, ".github/x.js") != null)
        assertNull(UiPathRules.check(PreviewKind.ANDROID_XML, "app/src/main/res/layout/main_screen.xml"))
        assertNull(UiPathRules.check(PreviewKind.ANDROID_XML, "app/src/main/res/values-night/colors.xml"))
        assertTrue(UiPathRules.check(PreviewKind.ANDROID_XML, "app/src/main/AndroidManifest.xml") != null)
        assertTrue(UiPathRules.check(PreviewKind.ANDROID_XML, "app/src/main/res/layout/Main.xml") != null)
        assertNull(UiPathRules.check(PreviewKind.ANDROID_COMPOSE, "app/src/main/java/com/example/Screen.kt"))
        assertNull(UiPathRules.check(PreviewKind.ANDROID_COMPOSE, "app/src/main/res/values/strings.xml"))
        assertTrue(UiPathRules.check(PreviewKind.ANDROID_COMPOSE, "app/build.gradle.kts") != null)
        assertTrue(UiPathRules.check(PreviewKind.FLUTTER, "lib/main.dart") != null)
    }

    @Test fun `a good answer becomes a draft`() {
        val d = (UiDraftParser.parse(answer("index.html" to "<p>x</p>", "style.css" to "a{}"), false, PreviewKind.WEB) as Parsed.Ok).value
        assertEquals(setOf("index.html", "style.css"), d.files.keys)
        assertEquals("A screen", d.summary)
        assertEquals(listOf("note"), d.notes)
        assertTrue(d.rejected.isEmpty())
    }

    @Test fun `unsafe, secret, duplicate, empty and oversized files are refused and reported`() {
        val text = answer(
            "index.html" to "<p>ok</p>",
            "../evil.html" to "<p>x</p>",
            "run.sh" to "rm -rf /",
            "key.js" to "const k = 'AIzaSyA1234567890abcdefghijklmnopqrstuv'",
            "INDEX.html" to "<p>dup</p>",
            "empty.css" to "  ",
            "big.js" to "a".repeat(UiPathRules.MAX_FILE_CHARS + 1),
            "bin.js" to "a\u0000b",
        )
        val d = (UiDraftParser.parse(text, false, PreviewKind.WEB) as Parsed.Ok).value
        assertEquals(setOf("index.html"), d.files.keys)
        assertEquals(7, d.rejected.size)
        assertTrue(d.rejected.any { it.contains("secret") })
        assertTrue(d.rejected.any { it.contains("twice") })
    }

    @Test fun `too many files are cut, and an answer with nothing usable is bad`() {
        val many = (1..12).map { "p$it.html" to "<p>$it</p>" }.toTypedArray()
        val d = (UiDraftParser.parse(answer(*many), false, PreviewKind.WEB) as Parsed.Ok).value
        assertEquals(UiPathRules.MAX_FILES, d.files.size)
        assertEquals(12 - UiPathRules.MAX_FILES, d.rejected.size)
        assertTrue(UiDraftParser.parse(answer("run.sh" to "x"), false, PreviewKind.WEB) is Parsed.Bad)
        assertTrue(UiDraftParser.parse("{\"summary\":\"x\"}", false, PreviewKind.WEB) is Parsed.Bad)
        assertTrue(UiDraftParser.parse("not json", false, PreviewKind.WEB) is Parsed.Bad)
        assertTrue(UiDraftParser.parse("{\"files\": [", true, PreviewKind.WEB) is Parsed.Bad)
    }

    @Test fun `external loads and unusual APIs are flagged but not refused`() {
        val d = (UiDraftParser.parse(answer("index.html" to "<script src=\"https://cdn.example/x.js\"></script>", "app/src/main/java/A.kt" to "val p = ProcessBuilder(\"ls\")"), false, PreviewKind.ANDROID_COMPOSE) as Parsed.Ok).value
        assertEquals(setOf("app/src/main/java/A.kt"), d.files.keys)
        assertTrue(d.warnings.any { it.contains("ProcessBuilder") })
        val web = (UiDraftParser.parse(answer("index.html" to "<img src=\"https://x.example/a.png\">"), false, PreviewKind.WEB) as Parsed.Ok).value
        assertTrue(web.warnings.any { it.contains("internet") })
    }

    // ---- ai: generator

    private val files = FakeProjectFiles(mapOf("index.html" to "<p>current</p>"))
    private suspend fun gen(backend: FakeAiBackend, kind: PreviewKind = PreviewKind.WEB, request: String = "A login screen with two fields", current: UiDraft? = null) =
        PreviewUiGenerator(backend, waitMs = {}).generate(kind, request, files, listOf("index.html"), current, "Notes", ProjectType.WEB)

    @Test fun `the generator asks for preview_ui and returns a draft`() = runTest {
        val backend = FakeAiBackend().apply { answerText(answer("index.html" to "<p>new</p>")) }
        val r = gen(backend) as UiGenResult.Ok
        assertEquals("<p>new</p>", r.draft.files.getValue("index.html"))
        assertEquals(AiTaskKind.PREVIEW_UI, backend.requests[0].task)
        val prompt = backend.requests[0].messages.last().content
        assertTrue(prompt.contains("TARGET: web"))
        assertTrue(prompt.contains("login screen"))
        assertTrue(prompt.contains("<p>current</p>"))
    }

    @Test fun `refining sends the current draft so the AI can change it`() = runTest {
        val backend = FakeAiBackend().apply { answerText(answer("index.html" to "<p>v2</p>")) }
        val current = UiDraft(mapOf("index.html" to "<p>v1 draft</p>"), "s", emptyList())
        gen(backend, request = "Make the button bigger", current = current)
        val prompt = backend.requests[0].messages.last().content
        assertTrue(prompt.contains("CURRENT DRAFT"))
        assertTrue(prompt.contains("<p>v1 draft</p>"))
    }

    @Test fun `an unusable answer is asked for again once`() = runTest {
        val backend = FakeAiBackend().apply { answer(FakeAiBackend.Script.Text("sorry"), FakeAiBackend.Script.Text(answer("index.html" to "<p>ok</p>"))) }
        assertTrue(gen(backend) is UiGenResult.Ok)
        assertEquals(2, backend.requests.size)
        assertEquals(3, backend.requests[1].messages.size)
        val bad = FakeAiBackend().apply { answerText("still not json") }
        assertTrue(gen(bad) is UiGenResult.Failed)
        assertEquals(2, bad.requests.size)
    }

    @Test fun `short requests and flutter are refused without calling the AI`() = runTest {
        val backend = FakeAiBackend()
        assertTrue(gen(backend, request = "ui") is UiGenResult.Failed)
        assertTrue(gen(backend, kind = PreviewKind.FLUTTER) is UiGenResult.Failed)
        assertTrue(gen(backend, request = "x".repeat(3001)) is UiGenResult.Failed)
        assertTrue(backend.requests.isEmpty())
    }

    @Test fun `transient errors are retried and hard errors reported`() = runTest {
        val flaky = FakeAiBackend().apply {
            answer(FakeAiBackend.Script.Fail(com.almus.appcreatorpro.ai.AiException(com.almus.appcreatorpro.ai.AiErrorKind.UNAVAILABLE, "down", true)), FakeAiBackend.Script.Text(answer("index.html" to "<p>ok</p>")))
        }
        assertTrue(gen(flaky) is UiGenResult.Ok)
        val denied = FakeAiBackend().apply { answer(FakeAiBackend.Script.Fail(com.almus.appcreatorpro.ai.AiException(com.almus.appcreatorpro.ai.AiErrorKind.UNAUTHORIZED, "bad code", false))) }
        val r = gen(denied) as UiGenResult.Failed
        assertEquals("bad code", r.message)
        assertFalse(r.retryable)
    }

    @Test fun `a secret in the project is not sent to the AI`() = runTest {
        val secretFiles = FakeProjectFiles(mapOf("index.html" to "<script>const k='AIzaSyA1234567890abcdefghijklmnopqrstuv'</script>", ".env" to "TOKEN=abc"))
        val backend = FakeAiBackend().apply { answerText(answer("index.html" to "<p>ok</p>")) }
        PreviewUiGenerator(backend, waitMs = {}).generate(PreviewKind.WEB, "A page with a title", secretFiles, listOf("index.html", ".env"), null, "N", ProjectType.WEB)
        val sent = backend.requests[0].messages.joinToString { it.content }
        assertFalse(sent.contains("AIzaSyA1234567890"))
        assertFalse(sent.contains("TOKEN=abc"))
    }
}
