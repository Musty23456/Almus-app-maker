package com.almus.appcreatorpro.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutEngineTest {
    private val eps = 0.01f
    private fun text(t: String, size: Float = 14f, w: SizeSpec = SizeSpec.WRAP, h: SizeSpec = SizeSpec.WRAP, id: String? = null) =
        PreviewNode(NodeType.TEXT, "TextView", id = id, text = t, textSizeSp = size, width = w, height = h)

    private fun box(w: Float, h: Float, vararg extra: (PreviewNode) -> PreviewNode): PreviewNode =
        extra.fold(PreviewNode(NodeType.SPACER, "View", width = SizeSpec.exact(w), height = SizeSpec.exact(h))) { n, f -> f(n) }

    private fun vertical(vararg kids: PreviewNode, padding: Edges = Edges.ZERO, gravity: Int = 0, spacing: Float = 0f) =
        PreviewNode(NodeType.LINEAR, "LinearLayout", width = SizeSpec.MATCH, height = SizeSpec.MATCH, vertical = true, padding = padding, gravity = gravity, spacing = spacing, children = kids.toList())

    @Test fun `vertical linear stacks children using estimated text height`() {
        val l = LayoutEngine.layout(vertical(text("Hi"), text("There")), 360f, 640f)
        assertEquals(0f, l.children[0].y, eps)
        assertEquals(17.5f, l.children[0].h, eps)
        assertEquals(17.5f, l.children[1].y, eps)
        assertEquals(360f, l.w, eps)
        assertEquals(640f, l.h, eps)
    }

    @Test fun `padding and margins offset a child`() {
        val child = box(50f, 20f, { it.copy(margin = Edges(5f, 7f, 0f, 0f)) })
        val l = LayoutEngine.layout(vertical(child, padding = Edges.all(10f)), 360f, 640f)
        assertEquals(15f, l.children[0].x, eps)
        assertEquals(17f, l.children[0].y, eps)
    }

    @Test fun `weights share the remaining space`() {
        val fixed = box(10f, 100f)
        val a = PreviewNode(NodeType.SPACER, "View", width = SizeSpec.MATCH, height = SizeSpec.exact(0f), weight = 1f)
        val b = a.copy(weight = 3f)
        val l = LayoutEngine.layout(vertical(fixed, a, b), 300f, 400f)
        assertEquals(75f, l.children[1].h, eps)
        assertEquals(225f, l.children[2].h, eps)
        assertEquals(100f, l.children[1].y, eps)
        assertEquals(175f, l.children[2].y, eps)
        assertEquals(300f, l.children[1].w, eps)
    }

    @Test fun `horizontal linear weights and vertical centering`() {
        val a = PreviewNode(NodeType.SPACER, "View", width = SizeSpec.exact(0f), height = SizeSpec.exact(20f), weight = 1f)
        val b = a.copy(weight = 1f)
        val row = PreviewNode(NodeType.LINEAR, "LinearLayout", width = SizeSpec.MATCH, height = SizeSpec.MATCH, vertical = false, gravity = Grav.CENTER_V, children = listOf(a, b))
        val l = LayoutEngine.layout(row, 200f, 100f)
        assertEquals(100f, l.children[0].w, eps)
        assertEquals(100f, l.children[1].x, eps)
        assertEquals(40f, l.children[0].y, eps)
    }

    @Test fun `spacing and end gravity`() {
        val l = LayoutEngine.layout(vertical(box(10f, 10f), box(10f, 10f), spacing = 5f, gravity = Grav.BOTTOM), 100f, 100f)
        assertEquals(75f, l.children[0].y, eps)
        assertEquals(90f, l.children[1].y, eps)
    }

    @Test fun `layout gravity centers a child in a frame`() {
        val frame = PreviewNode(NodeType.FRAME, "FrameLayout", width = SizeSpec.MATCH, height = SizeSpec.MATCH, children = listOf(box(50f, 50f, { it.copy(layoutGravity = Grav.CENTER) })))
        val l = LayoutEngine.layout(frame, 200f, 200f)
        assertEquals(75f, l.children[0].x, eps)
        assertEquals(75f, l.children[0].y, eps)
    }

    @Test fun `long text wraps and the height follows`() {
        val l = LayoutEngine.layout(PreviewNode(NodeType.FRAME, "F", width = SizeSpec.MATCH, height = SizeSpec.MATCH, children = listOf(text("x".repeat(100)))), 200f, 400f)
        assertEquals(200f, l.children[0].w, eps)
        assertEquals(70f, l.children[0].h, eps)
    }

    @Test fun `font scale makes text taller`() {
        val root = PreviewNode(NodeType.FRAME, "F", width = SizeSpec.MATCH, height = SizeSpec.MATCH, children = listOf(text("Hi")))
        assertEquals(35f, LayoutEngine.layout(root, 200f, 400f, 2f).children[0].h, eps)
    }

    @Test fun `a wrap-content parent is as wide as its widest non-match child`() {
        val inner = PreviewNode(NodeType.LINEAR, "L", vertical = true, children = listOf(box(80f, 10f), box(120f, 10f), PreviewNode(NodeType.SPACER, "V", width = SizeSpec.MATCH, height = SizeSpec.exact(5f))))
        val root = PreviewNode(NodeType.FRAME, "F", width = SizeSpec.MATCH, height = SizeSpec.MATCH, children = listOf(inner))
        val l = LayoutEngine.layout(root, 300f, 300f)
        assertEquals(120f, l.children[0].w, eps)
        assertEquals(120f, l.children[0].children[2].w, eps)
        assertEquals(25f, l.children[0].h, eps)
    }

    @Test fun `scroll containers give content unlimited height and report clipping`() {
        val content = PreviewNode(NodeType.LINEAR, "L", width = SizeSpec.MATCH, vertical = true, children = (1..20).map { text("row $it") })
        val scroll = PreviewNode(NodeType.SCROLL, "ScrollView", width = SizeSpec.MATCH, height = SizeSpec.exact(200f), children = listOf(content))
        val l = LayoutEngine.layout(scroll, 300f, 600f)
        assertEquals(350f, l.children[0].h, eps)
        assertTrue(l.clipped)
    }

    @Test fun `constraint layout centers between parent edges using the bias`() {
        val a = Anchors(startToStart = "parent", endToEnd = "parent", topToTop = "parent", bottomToBottom = "parent")
        val child = box(100f, 40f, { it.copy(anchors = a) })
        val l = LayoutEngine.layout(PreviewNode(NodeType.CONSTRAINT, "C", width = SizeSpec.MATCH, height = SizeSpec.MATCH, children = listOf(child)), 300f, 500f)
        assertEquals(100f, l.children[0].x, eps)
        assertEquals(230f, l.children[0].y, eps)
        val biased = LayoutEngine.layout(PreviewNode(NodeType.CONSTRAINT, "C", width = SizeSpec.MATCH, height = SizeSpec.MATCH, children = listOf(child.copy(anchors = a.copy(hBias = 0f, vBias = 1f)))), 300f, 500f)
        assertEquals(0f, biased.children[0].x, eps)
        assertEquals(460f, biased.children[0].y, eps)
    }

    @Test fun `constraint layout fills with 0dp between anchors and follows sibling anchors`() {
        val fill = PreviewNode(NodeType.SPACER, "V", width = SizeSpec.exact(0f), height = SizeSpec.exact(30f), anchors = Anchors(startToStart = "parent", endToEnd = "parent", topToTop = "parent"))
        assertEquals(300f, LayoutEngine.layout(PreviewNode(NodeType.CONSTRAINT, "C", width = SizeSpec.MATCH, height = SizeSpec.MATCH, children = listOf(fill)), 300f, 500f).children[0].w, eps)
        val a = box(100f, 50f, { it.copy(id = "a", anchors = Anchors(startToStart = "parent", topToTop = "parent")) })
        val b = box(80f, 20f, { it.copy(id = "b", anchors = Anchors(startToStart = "parent", topToBottom = "a")) })
        val l = LayoutEngine.layout(PreviewNode(NodeType.CONSTRAINT, "C", width = SizeSpec.MATCH, height = SizeSpec.MATCH, children = listOf(b, a)), 300f, 500f)
        val laidB = l.children.first { it.node.id == "b" }
        assertEquals(50f, laidB.y, eps)
        assertEquals(0f, laidB.x, eps)
    }

    @Test fun `hit testing finds the deepest view and nothing outside`() {
        val l = LayoutEngine.layout(vertical(text("Hi", id = "t")), 360f, 640f)
        assertEquals("t", l.hit(5f, 5f)!!.node.id)
        assertSame(l, l.hit(300f, 500f))
        assertNull(l.hit(400f, 10f))
        assertEquals(2, l.count())
    }

    @Test fun `buttons and edit fields have sensible minimum sizes`() {
        val btn = PreviewNode(NodeType.BUTTON, "Button", text = "Go")
        val edit = PreviewNode(NodeType.EDIT, "EditText", hint = "Name", width = SizeSpec.MATCH)
        val l = LayoutEngine.layout(vertical(btn, edit), 360f, 640f)
        assertTrue(l.children[0].w >= 64f)
        assertTrue(l.children[0].h >= 40f)
        assertTrue(l.children[1].h >= 48f)
        assertEquals(360f, l.children[1].w, eps)
    }

    @Test fun `an empty container and zero-size screens do not crash`() {
        val l = LayoutEngine.layout(PreviewNode(NodeType.LINEAR, "L", width = SizeSpec.MATCH, height = SizeSpec.MATCH), 0f, 0f)
        assertEquals(0f, l.w, eps)
        assertFalse(l.clipped)
        assertNotNull(LayoutEngine.layout(vertical(text("a")), 1f, 1f))
    }
}
