package com.almus.appcreatorpro.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XmlLayoutBuilderTest {
    private val ns = "xmlns:android=\"http://schemas.android.com/apk/res/android\" xmlns:app=\"http://schemas.android.com/apk/res-auto\" xmlns:tools=\"http://schemas.android.com/tools\""
    private val res = ResourceTable(strings = mapOf("app_name" to "Notes"), colors = mapOf("brand" to "#FF0000"), dimens = mapOf("pad" to "8dp"))
    private fun build(xml: String, layouts: Map<String, LayoutFile> = emptyMap(), dark: Boolean = false, resources: ResourceTable = res) =
        XmlLayoutBuilder(resources, layouts, PreviewPalette.of(dark)).build(LayoutFile("app/src/main/res/layout/main.xml", xml))

    private val basic = """<LinearLayout $ns
    android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="vertical" android:padding="16dp">
    <TextView android:id="@+id/title" android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="@string/app_name"/>
    <Button android:layout_width="match_parent" android:layout_height="wrap_content" android:text="Go"/>
</LinearLayout>"""

    @Test fun `a simple layout becomes a tree with text, ids, padding and source lines`() {
        val t = build(basic)
        val root = t.root!!
        assertEquals(NodeType.LINEAR, root.type)
        assertTrue(root.vertical)
        assertEquals(16f, root.padding.left, 0.001f)
        assertEquals(SizeKind.MATCH, root.width.kind)
        val title = root.children[0]
        assertEquals("Notes", title.text)
        assertEquals("title", title.id)
        assertEquals(NodeType.TEXT, title.type)
        assertEquals(3, title.line)
        assertEquals("app/src/main/res/layout/main.xml", title.sourcePath)
        assertEquals(NodeType.BUTTON, root.children[1].type)
        assertTrue(t.warnings.isEmpty())
    }

    @Test fun `the built tree lays out inside the screen`() {
        val l = LayoutEngine.layout(build(basic).root!!, 360f, 700f)
        assertEquals(16f, l.children[0].x, 0.01f)
        assertEquals(16f, l.children[0].y, 0.01f)
        assertEquals(33.5f, l.children[1].y, 0.01f)
        assertEquals(328f, l.children[1].w, 0.01f)
    }

    @Test fun `a LinearLayout without orientation is horizontal like in Android`() {
        val t = build("<LinearLayout $ns android:layout_width=\"match_parent\" android:layout_height=\"wrap_content\"><TextView android:layout_width=\"wrap_content\" android:layout_height=\"wrap_content\" android:text=\"a\"/></LinearLayout>")
        assertFalse(t.root!!.vertical)
    }

    @Test fun `gone views are removed, invisible ones keep their place, and tools text is used for design time`() {
        val t = build("""<FrameLayout $ns android:layout_width="match_parent" android:layout_height="match_parent">
            <TextView android:layout_width="wrap_content" android:layout_height="wrap_content" android:visibility="gone" android:text="x"/>
            <TextView android:layout_width="wrap_content" android:layout_height="wrap_content" android:visibility="invisible" android:text="y"/>
            <TextView android:layout_width="wrap_content" android:layout_height="wrap_content" tools:text="Sample"/>
        </FrameLayout>""")
        val kids = t.root!!.children
        assertEquals(2, kids.size)
        assertTrue(kids[0].invisible)
        assertEquals("Sample", kids[1].text)
    }

    @Test fun `weights, zero dp, dimens, colors and gravity are read`() {
        val t = build("""<LinearLayout $ns android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="horizontal" android:gravity="center_vertical">
            <View android:layout_width="0dp" android:layout_height="@dimen/pad" android:layout_weight="1" android:background="@color/brand" android:layout_gravity="end|bottom" android:layout_margin="4dp" android:layout_marginStart="10dp"/>
        </LinearLayout>""")
        val v = t.root!!.children[0]
        assertEquals(SizeKind.EXACT, v.width.kind)
        assertEquals(0f, v.width.dp, 0.001f)
        assertEquals(8f, v.height.dp, 0.001f)
        assertEquals(1f, v.weight, 0.001f)
        assertEquals(0xFFFF0000.toInt(), v.background)
        assertTrue(Grav.has(v.layoutGravity, Grav.END) && Grav.has(v.layoutGravity, Grav.BOTTOM))
        assertTrue(Grav.has(t.root!!.gravity, Grav.CENTER_V))
        assertEquals(10f, v.margin.left, 0.001f)
        assertEquals(4f, v.margin.right, 0.001f)
    }

    @Test fun `include pulls in another layout and reports a missing one`() {
        val part = LayoutFile("app/src/main/res/layout/part.xml", "<TextView $ns android:layout_width=\"wrap_content\" android:layout_height=\"wrap_content\" android:text=\"Part\"/>")
        val xml = "<FrameLayout $ns android:layout_width=\"match_parent\" android:layout_height=\"match_parent\"><include layout=\"@layout/part\"/><include layout=\"@layout/missing\"/></FrameLayout>"
        val t = build(xml, mapOf("part" to part))
        assertEquals(1, t.root!!.children.size)
        assertEquals("Part", t.root!!.children[0].text)
        assertEquals("app/src/main/res/layout/part.xml", t.root!!.children[0].sourcePath)
        assertTrue(t.warnings.any { it.contains("missing") })
    }

    @Test fun `an include loop stops instead of running forever`() {
        val a = LayoutFile("a.xml", "<FrameLayout $ns android:layout_width=\"match_parent\" android:layout_height=\"match_parent\"><include layout=\"@layout/a\"/></FrameLayout>")
        val t = XmlLayoutBuilder(res, mapOf("a" to a), PreviewPalette.LIGHT).build(a)
        assertNotNull(t.root)
        assertTrue(t.warnings.any { it.contains("6 levels") })
    }

    @Test fun `unknown and custom views become labelled placeholders with a warning`() {
        val t = build("<FrameLayout $ns android:layout_width=\"match_parent\" android:layout_height=\"match_parent\"><com.example.ChartView android:layout_width=\"100dp\" android:layout_height=\"100dp\"/></FrameLayout>")
        val c = t.root!!.children[0]
        assertEquals(NodeType.PLACEHOLDER, c.type)
        assertEquals("ChartView", c.text)
        assertTrue(t.warnings.single().contains("com.example.ChartView"))
    }

    @Test fun `lists and relative layouts are flagged as approximations`() {
        val t = build("<RelativeLayout $ns android:layout_width=\"match_parent\" android:layout_height=\"match_parent\"><androidx.recyclerview.widget.RecyclerView android:layout_width=\"match_parent\" android:layout_height=\"match_parent\"/></RelativeLayout>")
        assertEquals(NodeType.LIST, t.root!!.children[0].type)
        assertTrue(t.warnings.any { it.contains("RelativeLayout") })
        assertTrue(t.warnings.any { it.contains("adapter") })
    }

    @Test fun `constraint anchors are read and unsupported constraints are reported`() {
        val t = build("""<androidx.constraintlayout.widget.ConstraintLayout $ns android:layout_width="match_parent" android:layout_height="match_parent">
            <TextView android:id="@+id/a" android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="A"
                app:layout_constraintStart_toStartOf="parent" app:layout_constraintEnd_toEndOf="parent" app:layout_constraintTop_toTopOf="parent" app:layout_constraintHorizontal_bias="0.2"/>
            <TextView android:id="@+id/b" android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="B"
                app:layout_constraintTop_toBottomOf="@id/a" app:layout_constraintHorizontal_chainStyle="packed"/>
        </androidx.constraintlayout.widget.ConstraintLayout>""")
        assertEquals(NodeType.CONSTRAINT, t.root!!.type)
        val a = t.root!!.children[0]
        assertEquals("parent", a.anchors!!.startToStart)
        assertEquals(0.2f, a.anchors!!.hBias, 0.001f)
        assertEquals("a", t.root!!.children[1].anchors!!.topToBottom)
        assertTrue(t.warnings.any { it.contains("chainStyle") })
        val l = LayoutEngine.layout(t.root!!, 360f, 700f)
        assertTrue(l.children[1].y >= l.children[0].y + l.children[0].h - 0.01f)
    }

    @Test fun `widgets map to their node types`() {
        val t = build("""<LinearLayout $ns android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="vertical">
            <EditText android:layout_width="match_parent" android:layout_height="wrap_content" android:hint="Email"/>
            <com.google.android.material.button.MaterialButton android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="Ok"/>
            <ImageView android:layout_width="48dp" android:layout_height="48dp"/>
            <Switch android:layout_width="wrap_content" android:layout_height="wrap_content"/>
            <CheckBox android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="Agree"/>
            <ProgressBar android:layout_width="wrap_content" android:layout_height="wrap_content"/>
            <com.google.android.material.floatingactionbutton.FloatingActionButton android:layout_width="wrap_content" android:layout_height="wrap_content"/>
            <androidx.cardview.widget.CardView android:layout_width="match_parent" android:layout_height="wrap_content"><TextView android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="In card"/></androidx.cardview.widget.CardView>
        </LinearLayout>""")
        assertEquals(
            listOf(NodeType.EDIT, NodeType.BUTTON, NodeType.IMAGE, NodeType.SWITCH, NodeType.CHECK, NodeType.PROGRESS, NodeType.BUTTON, NodeType.CARD),
            t.root!!.children.map { it.type },
        )
        assertEquals("Email", t.root!!.children[0].hint)
        assertEquals(56f, t.root!!.children[6].height.dp, 0.001f)
        assertEquals("In card", t.root!!.children[7].children[0].text)
    }

    @Test fun `the screen background follows light and dark`() {
        assertEquals(PreviewPalette.LIGHT.background, build(basic).root!!.background)
        assertEquals(PreviewPalette.DARK.background, build(basic, dark = true).root!!.background)
        assertEquals(PreviewPalette.DARK.onSurface, build(basic, dark = true).root!!.children[0].textColor)
    }

    @Test fun `data binding layouts use the real root`() {
        val t = build("<layout $ns><data><variable name=\"x\" type=\"String\"/></data><FrameLayout android:layout_width=\"match_parent\" android:layout_height=\"match_parent\"/></layout>")
        assertEquals(NodeType.FRAME, t.root!!.type)
    }

    @Test fun `invalid XML gives no tree and a clear warning`() {
        val t = build("<LinearLayout><TextView></LinearLayout>")
        assertNull(t.root)
        assertTrue(t.warnings.single().contains("XML error"))
        assertTrue(t.warnings.single().contains("main.xml"))
    }

    @Test fun `layouts can be rebuilt with the same builder`() {
        val b = XmlLayoutBuilder(res, emptyMap(), PreviewPalette.LIGHT)
        val f = LayoutFile("x.xml", "<TextView $ns android:layout_width=\"wrap_content\" android:layout_height=\"wrap_content\" android:text=\"A\" android:foo=\"1\"/>")
        assertEquals(b.build(f).root, b.build(f).root)
    }
}
