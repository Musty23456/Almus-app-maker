package com.almus.appcreatorpro.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposeOutlineTest {
    private val path = "app/src/main/java/com/example/Screen.kt"
    private val source = """package com.example

@Composable
fun Greeting(name: String) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Hello", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Button(onClick = {}) { Text("Save") }
        OutlinedTextField(value = "", onValueChange = {}, label = { Text("Email") })
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    Greeting("A")
}
"""
    private val res = ResourceTable(strings = mapOf("title" to "Resource title"), colors = mapOf("brand" to "#00FF00"))
    private fun build(fn: String, src: String = source, resources: ResourceTable = res, dark: Boolean = false, extra: Map<String, String> = emptyMap()) =
        ComposeOutlineBuilder(resources, PreviewPalette.of(dark), mapOf(path to src) + extra).build(path, fn)

    private fun all(n: PreviewNode): List<PreviewNode> = listOf(n) + n.children.flatMap { all(it) }

    // ---- lexer

    @Test fun `the lexer keeps dp literals, strings with templates, and drops comments`() {
        val toks = KtLexer.tokenize("val a = 16.dp // c\n/* x\ny */ Text(\"Hi \${a.b(\"x\")}\", 0xFF112233)")
        assertEquals(listOf("16", ".", "dp"), toks.filter { it.line == 1 }.map { it.text }.drop(3))
        val str = toks.first { it.kind == TokKind.STR }
        assertEquals("Hi \${a.b(\"x\")}", str.text)
        assertEquals(3, str.line)
        assertEquals("0xFF112233", toks.last { it.kind == TokKind.NUM }.text)
        assertTrue(toks.none { it.text == "c" || it.text == "x" && it.kind == TokKind.ID })
    }

    @Test fun `matching brackets are found and unbalanced code gives minus one`() {
        val toks = KtLexer.tokenize("f(a, g(b)) { x }")
        assertEquals(8, KtLexer.matching(toks, 1))
        assertEquals(-1, KtLexer.matching(KtLexer.tokenize("f(a"), 1))
    }

    @Test fun `composable functions and their preview annotations are found`() {
        val fns = ComposeSource.functions(KtLexer.tokenize(source))
        assertEquals(listOf("Greeting", "GreetingPreview"), fns.map { it.name })
        assertFalse(fns[0].isPreview)
        assertTrue(fns[1].isPreview)
        assertFalse(ComposeSource.functions(KtLexer.tokenize("fun plain() {}\n@Composable val x = 1\nfun other() {}")).isNotEmpty())
    }

    // ---- outline

    @Test fun `a column with text, a button and a text field becomes a tree`() {
        val t = build("Greeting")
        val col = all(t.root!!).first { it.tag == "Column" }
        assertEquals(NodeType.LINEAR, col.type)
        assertTrue(col.vertical)
        assertEquals(SizeKind.MATCH, col.width.kind)
        assertEquals(16f, col.padding.left, 0.001f)
        assertEquals(8f, col.spacing, 0.001f)
        val hello = col.children[0]
        assertEquals("Hello", hello.text)
        assertEquals(24f, hello.textSizeSp, 0.001f)
        assertTrue(hello.bold)
        val button = col.children[1]
        assertEquals(NodeType.BUTTON, button.type)
        assertEquals("Save", button.children[0].text)
        val field = col.children[2]
        assertEquals(NodeType.EDIT, field.type)
        assertEquals("Email", field.hint)
    }

    @Test fun `source lines are kept for tap-to-source`() {
        val t = build("Greeting")
        val hello = all(t.root!!).first { it.text == "Hello" }
        assertEquals(source.lines().indexOfFirst { it.contains("Text(\"Hello\"") } + 1, hello.line)
        assertEquals(path, hello.sourcePath)
    }

    @Test fun `project composables are expanded, a preview function shows what it calls`() {
        val t = build("GreetingPreview")
        assertTrue(all(t.root!!).any { it.text == "Hello" })
        assertNull(build("Nope").root)
        assertTrue(build("Nope").warnings.single().contains("no @Composable function named Nope"))
    }

    @Test fun `recursion between composables stops`() {
        val src = "@Composable\nfun A() { Column { B() } }\n@Composable\nfun B() { Column { A() } }\n"
        val t = build("A", src)
        assertNotNull(t.root)
        assertTrue(all(t.root!!).size < 20)
    }

    @Test fun `values that are not literals are shown in angle marks and string resources are resolved`() {
        val src = "@Composable\nfun S(title: String) {\n Column {\n  Text(title)\n  Text(stringResource(R.string.title))\n  Text(stringResource(R.string.missing))\n }\n}"
        val texts = all(build("S", src).root!!).filter { it.type == NodeType.TEXT }.map { it.text }
        assertEquals(listOf("\u2039title\u203A", "Resource title", "@string/missing"), texts)
    }

    @Test fun `colors, weights, sizes and alignment in modifiers are read`() {
        val src = """@Composable
fun M() {
    Row(modifier = Modifier.fillMaxWidth().height(56.dp).background(Color(0xFF112233)), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.weight(1f).size(20.dp, 10.dp).background(Color.Red))
        Spacer(modifier = Modifier.width(12.dp))
        Box(modifier = Modifier.background(MaterialTheme.colorScheme.primary).align(Alignment.BottomEnd))
        Box(modifier = Modifier.background(colorResource(R.color.brand)).padding(horizontal = 4.dp, vertical = 2.dp))
    }
}"""
        val row = all(build("M", src).root!!).first { it.tag == "Row" }
        assertFalse(row.vertical)
        assertEquals(0xFF112233.toInt(), row.background)
        assertEquals(56f, row.height.dp, 0.001f)
        assertTrue(Grav.has(row.gravity, Grav.CENTER_H) && Grav.has(row.gravity, Grav.CENTER_V))
        val (a, sp, c, d) = row.children
        assertEquals(1f, a.weight, 0.001f)
        assertEquals(20f, a.width.dp, 0.001f)
        assertEquals(10f, a.height.dp, 0.001f)
        assertEquals(0xFFFF0000.toInt(), a.background)
        assertEquals(12f, sp.width.dp, 0.001f)
        assertEquals(PreviewPalette.LIGHT.primary, c.background)
        assertTrue(Grav.has(c.layoutGravity, Grav.BOTTOM) && Grav.has(c.layoutGravity, Grav.END))
        assertEquals(0xFF00FF00.toInt(), d.background)
        assertEquals(4f, d.padding.left, 0.001f)
        assertEquals(2f, d.padding.top, 0.001f)
    }

    @Test fun `scaffold places the top bar, the content and the bottom bar and overlays the button`() {
        val src = """@Composable
fun Shell() {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Inbox") }) },
        bottomBar = { NavigationBar { NavigationBarItem(selected = true, onClick = {}, icon = {}, label = { Text("Home") }) } },
        floatingActionButton = { FloatingActionButton(onClick = {}) { Icon(Icons.Default.Add, null) } },
    ) { padding ->
        Column(Modifier.padding(padding)) { Text("Body") }
    }
}"""
        val t = build("Shell", src)
        val nodes = all(t.root!!)
        assertEquals("Inbox", nodes.first { it.type == NodeType.TOOLBAR }.text)
        assertTrue(nodes.any { it.text == "Home" })
        assertTrue(nodes.any { it.text == "Body" })
        val fab = nodes.first { it.type == NodeType.BUTTON }
        assertTrue(Grav.has(fab.layoutGravity, Grav.END))
        val l = LayoutEngine.layout(t.root!!, 360f, 700f)
        val laidToolbar = findNode(l) { it.node.type == NodeType.TOOLBAR }!!
        val laidBody = findNode(l) { it.node.text == "Body" }!!
        assertTrue(laidBody.y > laidToolbar.y + laidToolbar.h - 0.01f)
    }

    private fun findNode(l: LaidOut, pred: (LaidOut) -> Boolean): LaidOut? = if (pred(l)) l else l.children.firstNotNullOfOrNull { findNode(it, pred) }

    @Test fun `control flow is not evaluated and the preview says so`() {
        val src = "@Composable\nfun C(flag: Boolean) {\n Column {\n  if (flag) { Text(\"A\") } else { Text(\"B\") }\n  items(list) { Text(\"Row\") }\n }\n}"
        val t = build("C", src)
        assertEquals(listOf("A", "B", "Row"), all(t.root!!).filter { it.type == NodeType.TEXT }.map { it.text })
        assertTrue(t.warnings.any { it.contains("not evaluated") })
    }

    @Test fun `lazy lists, grids and spacing arrangements are flagged`() {
        val src = "@Composable\nfun L() { LazyColumn { item { Text(\"x\") } }\n Column(verticalArrangement = Arrangement.SpaceBetween) { Text(\"y\") } }"
        val t = build("L", src)
        assertTrue(t.warnings.any { it.contains("Lazy lists") })
        assertTrue(t.warnings.any { it.contains("SpaceBetween") })
    }

    @Test fun `expression bodies and unknown composables are handled`() {
        val src = "@Composable\nfun E() = Column { Text(\"One\"); FancyChart(data) }\n@Composable\nfun Next() { Text(\"Other\") }"
        val nodes = all(build("E", src).root!!)
        assertTrue(nodes.any { it.text == "One" })
        assertFalse(nodes.any { it.text == "Other" })
    }

    @Test fun `dark mode changes the palette`() {
        val src = "@Composable\nfun D() { Button(onClick = {}) { Text(\"Go\") } }"
        assertEquals(PreviewPalette.DARK.primary, all(build("D", src, dark = true).root!!).first { it.type == NodeType.BUTTON }.background)
        assertEquals(PreviewPalette.DARK.background, build("D", src, dark = true).root!!.background)
    }

    @Test fun `an empty composable gives no tree and an explanation`() {
        val t = build("Empty", "@Composable\nfun Empty() { val x = 1 }")
        assertNull(t.root)
        assertTrue(t.warnings.any { it.contains("nothing to draw") })
    }

    @Test fun `the outline lays out like any other tree`() {
        val l = LayoutEngine.layout(build("Greeting").root!!, 412f, 800f)
        assertTrue(l.count() > 5)
        val hello = findNode(l) { it.node.text == "Hello" }!!
        assertEquals(16f, hello.x, 0.01f)
        assertEquals(16f, hello.y, 0.01f)
    }
}
