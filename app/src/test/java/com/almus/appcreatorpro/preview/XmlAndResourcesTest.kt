package com.almus.appcreatorpro.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XmlAndResourcesTest {
    private fun ok(text: String) = (SimpleXml.parse(text) as XmlResult.Ok).root
    private fun err(text: String) = SimpleXml.parse(text) as XmlResult.Error

    // ---- SimpleXml

    @Test fun `elements, attributes, text and positions are read`() {
        val root = ok("<?xml version=\"1.0\"?>\n<!-- c -->\n<resources a='1'>\n  <string name=\"app\">Notes &amp; more</string>\n  <item/>\n</resources>")
        assertEquals("resources", root.name)
        assertEquals("1", root.attr("a"))
        assertEquals(2, root.children.size)
        val s = root.children[0]
        assertEquals("Notes & more", s.text)
        assertEquals("app", s.attr("name"))
        assertEquals(4, s.line)
        assertEquals(3, s.column)
        assertEquals(3, root.line)
    }

    @Test fun `cdata, numeric entities and unknown entities are handled without any lookup`() {
        val root = ok("<a><![CDATA[<b>&amp;]]>&#65;&#x42;&xxe;</a>")
        assertEquals("<b>&amp;AB&xxe;", root.text)
    }

    @Test fun `a doctype or entity declaration is refused`() {
        assertTrue(err("<!DOCTYPE a [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><a>&x;</a>").message.contains("DOCTYPE"))
        assertTrue(err("<a><!ENTITY x \"y\"></a>").message.isNotEmpty())
    }

    @Test fun `malformed files report a message and a line`() {
        assertEquals(3, err("<a>\n<b>\n</a>").line)
        assertTrue(err("<a>").message.contains("not closed"))
        assertTrue(err("<a x=1/>").message.contains("quoted"))
        assertTrue(err("<a/><b/>").message.contains("more than one root"))
        assertTrue(err("").message.contains("no element"))
        assertTrue(err("<a><!-- open").message.contains("Unterminated"))
    }

    @Test fun `depth, element count and size are limited`() {
        val deep = "<a>".repeat(70) + "</a>".repeat(70)
        assertTrue(err(deep).message.contains("deeper"))
        val wide = "<r>" + "<i/>".repeat(SimpleXml.MAX_ELEMENTS + 5) + "</r>"
        assertTrue(err(wide).message.contains("more than"))
        assertTrue(err("<a>" + "x".repeat(SimpleXml.MAX_CHARS) + "</a>").message.contains("too large"))
    }

    // ---- ResourceTable

    @Test fun `colors in every hex form and references`() {
        assertEquals(0xFFFFFFFF.toInt(), ResourceTable.parseHexColor("#FFF"))
        assertEquals(0x11112222, ResourceTable.parseHexColor("#1122"))
        assertEquals(0xFF112233.toInt(), ResourceTable.parseHexColor("#112233"))
        assertEquals(0x80112233.toInt(), ResourceTable.parseHexColor("#80112233"))
        assertNull(ResourceTable.parseHexColor("#12345"))
        assertNull(ResourceTable.parseHexColor("#GGGGGG"))
        val t = ResourceTable(colors = mapOf("a" to "#112233", "b" to "@color/a", "loop" to "@color/loop"))
        assertEquals(0xFF112233.toInt(), t.color("@color/b"))
        assertNull(t.color("@color/loop"))
        assertEquals(0xFFFFFFFF.toInt(), t.color("@android:color/white"))
        assertNull(t.color("?attr/colorPrimary"))
        assertNull(t.color("@drawable/x"))
    }

    @Test fun `dimensions, strings and references`() {
        val t = ResourceTable(strings = mapOf("a" to "Hello", "b" to "@string/a"), dimens = mapOf("pad" to "12dp"))
        assertEquals(16f, t.dp("16dp")!!, 0.001f)
        assertEquals(16f, t.dp("16dip")!!, 0.001f)
        assertEquals(12f, t.dp("12sp")!!, 0.001f)
        assertEquals(12f, t.dp("@dimen/pad")!!, 0.001f)
        assertNull(t.dp("@dimen/none"))
        assertNull(t.dp("wrap_content"))
        assertEquals("Hello", t.string("@string/b"))
        assertEquals("plain", t.string("plain"))
        assertNull(t.string("@string/none"))
        assertNull(t.string("?attr/x"))
    }

    @Test fun `values files are read and values-night overrides only in dark mode`() {
        val files = mapOf(
            "app/src/main/res/values/colors.xml" to "<resources><color name=\"bg\">#FFFFFF</color><string name=\"hi\">Hi</string></resources>",
            "app/src/main/res/values-night/colors.xml" to "<resources><color name=\"bg\">#000000</color></resources>",
            "app/src/main/res/values-sw600dp/dimens.xml" to "<resources><dimen name=\"x\">99dp</dimen></resources>",
            "app/src/main/res/layout/a.xml" to "<x/>",
        )
        val light = ResourceTable.fromFiles(files, dark = false)
        val dark = ResourceTable.fromFiles(files, dark = true)
        assertEquals(0xFFFFFFFF.toInt(), light.color("@color/bg"))
        assertEquals(0xFF000000.toInt(), dark.color("@color/bg"))
        assertEquals("Hi", dark.string("@string/hi"))
        assertNull(light.dp("@dimen/x"))
    }

    @Test fun `a broken values file becomes a warning and does not stop the others`() {
        val warnings = ArrayList<String>()
        val t = ResourceTable.fromFiles(mapOf("res/values/a.xml" to "<resources><string name=\"a\">A</string>", "res/values/b.xml" to "<resources><string name=\"b\">B</string></resources>"), false, warnings)
        assertEquals("B", t.strings["b"])
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].contains("res/values/a.xml"))
    }

    @Test fun `files that are not values files are ignored`() {
        assertNotNull(ResourceTable.fromFiles(mapOf("res/layout/x.xml" to "not xml at all"), false))
        assertFalse(ResourceTable.fromFiles(mapOf("res/layout/x.xml" to "<resources><string name=\"a\">A</string></resources>"), false).strings.containsKey("a"))
    }
}
