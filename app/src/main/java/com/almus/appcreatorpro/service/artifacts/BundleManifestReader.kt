package com.almus.appcreatorpro.service.artifacts

/** The few facts of an AAB's base manifest the manager shows. Any field can be null. */
data class BundleManifest(
    val packageName: String?,
    val versionCode: Long?,
    val versionName: String?,
    val minSdk: Int?,
    val targetSdk: Int?,
    val debuggable: Boolean?,
)

/**
 * Reads `base/manifest/AndroidManifest.xml` of an Android App Bundle. In a bundle this file is not text and not the
 * binary XML of an APK, but a Protocol Buffers message (aapt2's `XmlNode`). Only the handful of fields needed are
 * decoded; everything else is skipped. Never throws: a file that is not understood gives null.
 *
 * XmlNode { element = 1 | text = 2 }
 * XmlElement { namespace_declaration = 1, namespace_uri = 2, name = 3, attribute = 4, child = 5 }
 * XmlAttribute { namespace_uri = 1, name = 2, value = 3 }
 */
object BundleManifestReader {
    private const val MAX_DEPTH = 16

    fun parse(bytes: ByteArray): BundleManifest? = try {
        val root = readNode(Reader(bytes, 0, bytes.size), 0)
        if (root == null || root.name != "manifest") null else toManifest(root)
    } catch (e: Exception) {
        null
    }

    private class Element(val name: String, val attributes: Map<String, String>, val children: List<Element>)

    private fun toManifest(root: Element): BundleManifest {
        val uses = root.children.firstOrNull { it.name == "uses-sdk" }
        val app = root.children.firstOrNull { it.name == "application" }
        return BundleManifest(
            packageName = root.attributes["package"],
            versionCode = root.attributes["versionCode"]?.toLongOrNull(),
            versionName = root.attributes["versionName"],
            minSdk = uses?.attributes?.get("minSdkVersion")?.toIntOrNull(),
            targetSdk = uses?.attributes?.get("targetSdkVersion")?.toIntOrNull(),
            debuggable = app?.attributes?.get("debuggable")?.let { it == "true" || it == "1" || it == "0xffffffff" },
        )
    }

    /** Reads one XmlNode and returns its element (null for a text node). */
    private fun readNode(node: Reader, depth: Int): Element? {
        if (depth > MAX_DEPTH) throw IllegalStateException("too deep")
        var element: Element? = null
        while (node.hasMore()) {
            val tag = node.varint().toInt()
            val field = tag ushr 3
            val wire = tag and 7
            if (field == 1 && wire == 2) element = readElement(node.lengthDelimited(), depth) else node.skip(wire)
        }
        return element
    }

    private fun readElement(r: Reader, depth: Int): Element {
        var name = ""
        val attributes = LinkedHashMap<String, String>()
        val children = ArrayList<Element>()
        while (r.hasMore()) {
            val tag = r.varint().toInt()
            val field = tag ushr 3
            val wire = tag and 7
            when {
                field == 3 && wire == 2 -> name = r.string()
                field == 4 && wire == 2 -> readAttribute(r.lengthDelimited(), attributes)
                field == 5 && wire == 2 -> readNode(r.lengthDelimited(), depth + 1)?.let { children += it }
                else -> r.skip(wire)
            }
        }
        return Element(name, attributes, children)
    }

    private fun readAttribute(r: Reader, into: MutableMap<String, String>) {
        var name = ""
        var value = ""
        while (r.hasMore()) {
            val tag = r.varint().toInt()
            val field = tag ushr 3
            val wire = tag and 7
            when {
                field == 2 && wire == 2 -> name = r.string()
                field == 3 && wire == 2 -> value = r.string()
                else -> r.skip(wire)
            }
        }
        if (name.isNotEmpty() && name !in into) into[name] = value
    }

    private class Reader(private val data: ByteArray, private var pos: Int, private val end: Int) {
        fun hasMore(): Boolean = pos < end

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (pos >= end || shift > 63) throw IllegalStateException("bad varint")
                val b = data[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }

        fun lengthDelimited(): Reader {
            val len = varint()
            if (len < 0L || len > (end - pos).toLong()) throw IllegalStateException("bad length")
            val r = Reader(data, pos, pos + len.toInt())
            pos += len.toInt()
            return r
        }

        fun string(): String {
            val r = lengthDelimited()
            return String(data, r.pos, r.end - r.pos, Charsets.UTF_8)
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> advance(8)
                2 -> lengthDelimited()
                5 -> advance(4)
                else -> throw IllegalStateException("unsupported wire type")
            }
        }

        private fun advance(n: Int) {
            if (n > end - pos) throw IllegalStateException("truncated")
            pos += n
        }
    }
}
