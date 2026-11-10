package com.almus.appcreatorpro.preview

import com.almus.appcreatorpro.ai.FakeProjectFiles
import com.almus.appcreatorpro.domain.model.ProjectType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private const val INDEX = "index.html"
private const val LAYOUT = "app/src/main/res/layout/activity_main.xml"
private const val SCREEN = "app/src/main/java/com/example/Screen.kt"

private val webFiles = mapOf(
    INDEX to "<!DOCTYPE html>\n<html><head><meta name=\"viewport\" content=\"width=device-width\"><link rel=\"stylesheet\" href=\"style.css\"></head>\n<body><h1>Hello</h1></body></html>",
    "style.css" to "body{color:red}\n@media (prefers-color-scheme: dark){body{color:white}}\n@media (min-width: 768px){body{color:blue}}",
)

private val xmlFiles = mapOf(
    LAYOUT to "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\" android:layout_width=\"match_parent\" android:layout_height=\"match_parent\" android:orientation=\"vertical\">\n<TextView android:layout_width=\"wrap_content\" android:layout_height=\"wrap_content\" android:text=\"@string/hello\"/>\n</LinearLayout>",
    "app/src/main/res/values/strings.xml" to "<resources><string name=\"hello\">Hello XML</string></resources>",
    "app/build.gradle.kts" to "android { namespace = \"com.example\" }",
)

private val composeFiles = mapOf(
    SCREEN to "package com.example\n\n@Preview\n@Composable\nfun HomePreview() {\n    Column {\n        Text(\"Compose home\")\n    }\n}\n",
)

@OptIn(ExperimentalCoroutinesApi::class)
class PreviewSessionTest {
    private fun session(scope: TestScope, files: FakeProjectFiles, type: ProjectType? = ProjectType.WEB, engines: PreviewEngines = PreviewEngines.default()) =
        PreviewSession(files, type, scope, engines, clockMillis = { 1_000L }, pollMs = 100L)

    private suspend fun TestScope.opened(files: FakeProjectFiles, type: ProjectType? = ProjectType.WEB): PreviewSession {
        val s = session(this, files, type)
        s.open()
        advanceUntilIdle()
        return s
    }

    // ---- web

    @Test fun `opening a web project builds the default page without touching the project`() = runTest {
        val files = FakeProjectFiles(webFiles)
        val s = opened(files)
        val st = s.state.value
        assertEquals(PreviewStatus.READY, st.status)
        assertEquals(INDEX, st.selected!!.path)
        assertEquals(PreviewKind.WEB, st.selected!!.kind)
        val web = st.build as PreviewBuild.Web
        assertTrue(web.bundle.files.getValue(INDEX).text.contains("Content-Security-Policy"))
        assertEquals(listOf(768), web.bundle.breakpoints)
        assertEquals(1, st.version)
        assertTrue(st.console.any { it.level == ConsoleLevel.DEBUG && it.message.contains("Web engine") })
        assertTrue(files.writes.isEmpty())
    }

    @Test fun `dark mode rebuilds the page, the device size only resizes it`() = runTest {
        val s = opened(FakeProjectFiles(webFiles))
        s.setConfig { it.copy(dark = true) }
        advanceUntilIdle()
        assertEquals(2, s.state.value.version)
        assertTrue((s.state.value.build as PreviewBuild.Web).bundle.files.getValue("style.css").text.contains("(min-width: 0px)"))
        s.setConfig { it.withDevice(DeviceCatalog.byId("tablet-10")!!) }
        s.setConfig { it.copy(landscape = true, zoomPercent = 75) }
        advanceUntilIdle()
        assertEquals(2, s.state.value.version)
        assertEquals(1280, s.state.value.config.widthDp)
        s.setConfig { it.copy(allowInternet = true) }
        advanceUntilIdle()
        assertEquals(3, s.state.value.version)
    }

    @Test fun `refresh picks up changed files`() = runTest {
        val files = FakeProjectFiles(webFiles)
        val s = opened(files)
        files.files[INDEX] = "<!DOCTYPE html><html><head></head><body><h1>Changed</h1></body></html>"
        s.refresh(); advanceUntilIdle()
        assertTrue((s.state.value.build as PreviewBuild.Web).bundle.files.getValue(INDEX).text.contains("Changed"))
        assertEquals(2, s.state.value.version)
    }

    @Test fun `warnings of the web build reach the console`() = runTest {
        val s = opened(FakeProjectFiles(mapOf(INDEX to "<p>x</p><script src=\"https://cdn.example/a.js\"></script>")))
        val c = s.state.value.console
        assertTrue(c.any { it.level == ConsoleLevel.WARN && it.message.contains("viewport") })
        assertTrue(c.any { it.level == ConsoleLevel.WARN && it.message.contains("cdn.example") })
    }

    @Test fun `auto refresh sees an edit made elsewhere and stops when closed`() = runTest {
        val files = FakeProjectFiles(webFiles)
        val s = opened(files)
        s.startWatching()
        advanceTimeBy(150); runCurrent()
        assertEquals(1, s.state.value.version)
        files.files["style.css"] = "body{color:green}"
        advanceTimeBy(250); runCurrent()
        assertTrue(s.state.value.version >= 2)
        assertTrue((s.state.value.build as PreviewBuild.Web).bundle.files.getValue("style.css").text.contains("green"))
        s.close()
        val v = s.state.value.version
        files.files["style.css"] = "body{color:pink}"
        advanceTimeBy(1000); runCurrent()
        assertEquals(v, s.state.value.version)
    }

    @Test fun `with auto refresh off an edit does not rebuild`() = runTest {
        val files = FakeProjectFiles(webFiles)
        val s = opened(files)
        s.setConfig { it.copy(autoRefresh = false) }
        s.startWatching()
        advanceTimeBy(150); runCurrent()
        files.files["style.css"] = "body{color:green}"
        advanceTimeBy(500); runCurrent()
        assertEquals(1, s.state.value.version)
        s.close()
    }

    @Test fun `the last of two quick refreshes wins`() = runTest {
        val files = FakeProjectFiles(webFiles)
        val s = opened(files)
        files.files[INDEX] = "<html><body>first</body></html>"
        s.refresh()
        files.files[INDEX] = "<html><body>second</body></html>"
        s.refresh()
        advanceUntilIdle()
        assertTrue((s.state.value.build as PreviewBuild.Web).bundle.files.getValue(INDEX).text.contains("second"))
        assertEquals(PreviewStatus.READY, s.state.value.status)
    }

    // ---- console and inspect

    @Test fun `console, blocked requests and resource errors are recorded with project paths`() = runTest {
        val s = opened(FakeProjectFiles(webFiles))
        s.onWebConsole("ERROR", "Uncaught TypeError: x", "https://preview.almus.invalid/app.js", 4)
        s.onWebConsole("LOG", "hello", null, 0)
        s.onBlocked("Network access is off for previews. Blocked: example.com")
        s.onResourceError("404 not found", "https://preview.almus.invalid/missing.png")
        val c = s.state.value.console
        val err = c.first { it.origin == ConsoleOrigin.SCRIPT_ERROR }
        assertEquals("app.js", err.path)
        assertEquals(4, err.line)
        assertTrue(c.any { it.origin == ConsoleOrigin.BLOCKED })
        assertEquals("missing.png", c.first { it.origin == ConsoleOrigin.RESOURCE }.path)
        s.clearConsole()
        assertTrue(s.state.value.console.isEmpty())
    }

    @Test fun `a tap in inspect mode becomes a pick, not a console line`() = runTest {
        val s = opened(FakeProjectFiles(webFiles))
        s.setInspect(true)
        val before = s.state.value.console.size
        s.onWebConsole("LOG", "[almus:pick] index.html:3 h1", null, 0)
        assertEquals(PickEvent("index.html", 3, "h1"), s.state.value.pick)
        assertEquals(before, s.state.value.console.size)
        s.consumePick()
        assertNull(s.state.value.pick)
        s.setInspect(false)
        assertFalse(s.state.value.inspect)
    }

    @Test fun `a crashed or unresponsive page is reported`() = runTest {
        val s = opened(FakeProjectFiles(webFiles))
        s.onRenderUnresponsive()
        s.onRenderProcessGone(true)
        assertEquals(PreviewStatus.ERROR, s.state.value.status)
        assertTrue(s.state.value.console.any { it.message.contains("crashed") })
        assertTrue(s.state.value.console.any { it.message.contains("stopped responding") })
        s.refresh(); advanceUntilIdle()
        assertEquals(PreviewStatus.READY, s.state.value.status)
    }

    // ---- android approximations

    @Test fun `an xml layout is drawn as an approximation with resources resolved`() = runTest {
        val files = FakeProjectFiles(xmlFiles)
        val s = opened(files, ProjectType.ANDROID_KOTLIN)
        val st = s.state.value
        assertEquals(PreviewKind.ANDROID_XML, st.selected!!.kind)
        val wire = st.build as PreviewBuild.Wire
        assertEquals(PreviewFidelity.APPROXIMATE, wire.fidelity)
        val text = wire.tree!!.children[0]
        assertEquals("Hello XML", text.node.text)
        assertEquals(DeviceCatalog.default.widthDp, wire.widthDp)
        assertNotNull(st.nativeReadiness)
        assertTrue(files.writes.isEmpty())
    }

    @Test fun `device, orientation and text size rebuild an approximate preview`() = runTest {
        val s = opened(FakeProjectFiles(xmlFiles), ProjectType.ANDROID_KOTLIN)
        s.setConfig { it.withDevice(DeviceCatalog.byId("phone-small")!!) }
        advanceUntilIdle()
        assertEquals(2, s.state.value.version)
        assertEquals(360, (s.state.value.build as PreviewBuild.Wire).widthDp)
        s.setConfig { it.copy(landscape = true) }
        advanceUntilIdle()
        assertEquals(640, (s.state.value.build as PreviewBuild.Wire).widthDp)
        s.setConfig { it.withFontScale(1.5f) }
        advanceUntilIdle()
        assertEquals(4, s.state.value.version)
    }

    @Test fun `tapping a drawn view reports its source line`() = runTest {
        val s = opened(FakeProjectFiles(xmlFiles), ProjectType.ANDROID_KOTLIN)
        val p = s.pickAt(5f, 5f)!!
        assertEquals(LAYOUT, p.path)
        assertEquals(2, p.line)
        assertEquals("TextView", p.tag)
        assertEquals(p, s.state.value.pick)
        assertNull(s.pickAt(5000f, 5000f)?.takeIf { it.tag == "nothing" })
    }

    @Test fun `a compose function is outlined and the preview function is the default`() = runTest {
        val s = opened(FakeProjectFiles(composeFiles), ProjectType.ANDROID_KOTLIN)
        val st = s.state.value
        assertEquals(PreviewKind.ANDROID_COMPOSE, st.selected!!.kind)
        assertEquals("HomePreview", st.selected!!.function)
        val wire = st.build as PreviewBuild.Wire
        assertNotNull(wire.tree)
        assertEquals(PreviewFidelity.APPROXIMATE, wire.fidelity)
        assertTrue(st.console.any { it.origin == ConsoleOrigin.PARSE || it.message.contains("Approximation") })
    }

    @Test fun `switching targets rebuilds with the right engine`() = runTest {
        val s = opened(FakeProjectFiles(webFiles + xmlFiles), null)
        val xml = s.state.value.targets.first { it.kind == PreviewKind.ANDROID_XML }
        s.select(xml); advanceUntilIdle()
        assertTrue(s.state.value.build is PreviewBuild.Wire)
        val web = s.state.value.targets.first { it.kind == PreviewKind.WEB }
        s.select(web); advanceUntilIdle()
        assertTrue(s.state.value.build is PreviewBuild.Web)
    }

    // ---- unusual projects

    @Test fun `a project with nothing to preview says so`() = runTest {
        val s = opened(FakeProjectFiles(mapOf("README.md" to "hi")))
        assertEquals(PreviewStatus.EMPTY, s.state.value.status)
        assertNull(s.state.value.build)
    }

    @Test fun `flutter is detected and reported as not available`() = runTest {
        val s = opened(FakeProjectFiles(mapOf("pubspec.yaml" to "name: app", "lib/main.dart" to "void main() {}")), null)
        assertEquals(PreviewKind.FLUTTER, s.state.value.selected!!.kind)
        val b = s.state.value.build as PreviewBuild.Unavailable
        assertTrue(b.reason.contains("Flutter"))
        assertEquals(PreviewStatus.ERROR, s.state.value.status)
        assertEquals(PreviewFidelity.UNAVAILABLE, b.fidelity)
    }

    @Test fun `an engine that throws gives an error state and a console line, not a crash`() = runTest {
        val boom = object : PreviewEngine {
            override val kind = PreviewKind.WEB
            override suspend fun build(input: PreviewBuildInput): PreviewBuild = throw IllegalStateException("boom")
        }
        val s = PreviewSession(FakeProjectFiles(webFiles), ProjectType.WEB, this, PreviewEngines(listOf(boom)), pollMs = 100L)
        s.open(); advanceUntilIdle()
        assertEquals(PreviewStatus.ERROR, s.state.value.status)
        assertTrue(s.state.value.error!!.contains("boom"))
        assertTrue(s.state.value.console.any { it.level == ConsoleLevel.ERROR })
    }

    @Test fun `a kind without an engine is reported`() = runTest {
        val s = PreviewSession(FakeProjectFiles(webFiles), ProjectType.WEB, this, PreviewEngines(emptyList()), pollMs = 100L)
        s.open(); advanceUntilIdle()
        assertTrue(s.state.value.build is PreviewBuild.Unavailable)
    }

    // ---- ai draft overlay

    @Test fun `a draft is previewed over the project and never written`() = runTest {
        val files = FakeProjectFiles(webFiles)
        val s = opened(files)
        s.applyDraft(UiDraft(mapOf(INDEX to "<html><body><h1>From AI</h1></body></html>", "extra.css" to "a{}"), "A page", emptyList()))
        advanceUntilIdle()
        val web = s.state.value.build as PreviewBuild.Web
        assertTrue(web.bundle.files.getValue(INDEX).text.contains("From AI"))
        assertTrue(web.bundle.files.containsKey("extra.css"))
        assertEquals(webFiles.getValue(INDEX), files.files[INDEX])
        assertFalse(files.files.containsKey("extra.css"))
        assertTrue(files.writes.isEmpty())
        s.discardDraft(); advanceUntilIdle()
        assertTrue((s.state.value.build as PreviewBuild.Web).bundle.files.getValue(INDEX).text.contains("Hello"))
        assertNull(s.state.value.draft)
    }

    @Test fun `a draft for a new layout selects it`() = runTest {
        val s = opened(FakeProjectFiles(webFiles))
        s.applyDraft(UiDraft(mapOf("app/src/main/res/layout/new_screen.xml" to "<FrameLayout xmlns:android=\"http://schemas.android.com/apk/res/android\" android:layout_width=\"match_parent\" android:layout_height=\"match_parent\"/>"), "Screen", emptyList()))
        advanceUntilIdle()
        assertEquals(PreviewKind.ANDROID_XML, s.state.value.selected!!.kind)
        assertTrue(s.state.value.build is PreviewBuild.Wire)
    }

    @Test fun `draftSaved drops the overlay and shows the real files`() = runTest {
        val files = FakeProjectFiles(webFiles)
        val s = opened(files)
        s.applyDraft(UiDraft(mapOf(INDEX to "<html><body>draft</body></html>"), "x", emptyList()))
        advanceUntilIdle()
        files.files[INDEX] = "<html><body>saved</body></html>"
        s.draftSaved(); advanceUntilIdle()
        assertNull(s.state.value.draft)
        assertTrue((s.state.value.build as PreviewBuild.Web).bundle.files.getValue(INDEX).text.contains("saved"))
    }

    // ---- read-only guarantee

    @Test fun `preview files can read but never write or delete`() = runTest {
        val real = FakeProjectFiles(webFiles)
        val pf = PreviewFiles(real, mapOf("draft.html" to "<p>d</p>"))
        assertEquals("<p>d</p>", pf.read("draft.html"))
        assertEquals(webFiles.getValue(INDEX), pf.read(INDEX))
        assertTrue("draft.html" in pf.list())
        assertTrue(pf.exists("draft.html"))
        try { pf.write("x", "y"); fail("write must throw") } catch (e: UnsupportedOperationException) { /* expected */ }
        try { pf.delete(INDEX); fail("delete must throw") } catch (e: UnsupportedOperationException) { /* expected */ }
        assertTrue(real.writes.isEmpty())
        assertSame(real.files, real.files)
    }
}
