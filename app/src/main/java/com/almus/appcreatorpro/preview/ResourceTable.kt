package com.almus.appcreatorpro.preview

/**
 * Android resources that matter for a preview: strings, colors, dimensions. Read from the XML files in the `res/values` folders (and its qualifiers) with
 * [SimpleXml]. In dark mode the files under `values-night` are applied on top, like Android does.
 * Themes and styles are not evaluated: attributes that point at them (`?attr/...`) resolve to nothing.
 */
class ResourceTable(
    val strings: Map<String, String> = emptyMap(),
    val colors: Map<String, String> = emptyMap(),
    val dimens: Map<String, String> = emptyMap(),
) {
    fun string(ref: String?, depth: Int = 0): String? {
        val r = ref ?: return null
        if (r.startsWith("@string/")) {
            if (depth > 5) return null
            val v = strings[r.removePrefix("@string/")] ?: return null
            return if (v.startsWith("@string/")) string(v, depth + 1) else v
        }
        if (r.startsWith("@") || r.startsWith("?")) return null
        return r
    }

    /** ARGB, or null when it cannot be resolved (theme attributes, drawables, unknown names). */
    fun color(ref: String?, depth: Int = 0): Int? {
        val r = ref?.trim() ?: return null
        if (r.startsWith("#")) return parseHexColor(r)
        if (depth > 5) return null
        if (r.startsWith("@color/")) return color(colors[r.removePrefix("@color/")], depth + 1)
        if (r.startsWith("@android:color/")) return when (r.removePrefix("@android:color/")) {
            "white" -> 0xFFFFFFFF.toInt()
            "black" -> 0xFF000000.toInt()
            "transparent" -> 0x00000000
            "darker_gray" -> 0xFFAAAAAA.toInt()
            "holo_blue_light" -> 0xFF33B5E5.toInt()
            "holo_red_light" -> 0xFFFF4444.toInt()
            "holo_green_light" -> 0xFF99CC00.toInt()
            else -> null
        }
        return null
    }

    /** Density-independent size, or null. Accepts dp, dip, sp and px (px is taken as dp: the preview has no real density). */
    fun dp(ref: String?, depth: Int = 0): Float? {
        val r = ref?.trim() ?: return null
        if (r.startsWith("@dimen/")) return if (depth > 5) null else dp(dimens[r.removePrefix("@dimen/")], depth + 1)
        return parseDimension(r)
    }

    companion object {
        fun parseHexColor(raw: String): Int? {
            val h = raw.removePrefix("#")
            if (h.any { !(it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F') }) return null
            return when (h.length) {
                3 -> ("FF" + h.map { "$it$it" }.joinToString("")).toLong(16).toInt()
                4 -> h.map { "$it$it" }.joinToString("").toLong(16).toInt()
                6 -> ("FF$h").toLong(16).toInt()
                8 -> h.toLong(16).toInt()
                else -> null
            }
        }

        fun parseDimension(raw: String): Float? {
            val t = raw.trim()
            val unit = listOf("dip", "dp", "sp", "px", "pt", "mm", "in").firstOrNull { t.endsWith(it) }
            val num = (if (unit != null) t.removeSuffix(unit) else t).trim().toFloatOrNull() ?: return null
            return when (unit) {
                "pt" -> num * 1.33f
                "mm" -> num * 6.3f
                "in" -> num * 160f
                else -> num
            }
        }

        /**
         * [files] maps a project path to its text. Only `res/values*` files are read. [dark] applies `values-night` over
         * the default values. Problems (unreadable files) are added to [warnings].
         */
        fun fromFiles(files: Map<String, String>, dark: Boolean, warnings: MutableList<String> = ArrayList()): ResourceTable {
            val strings = LinkedHashMap<String, String>()
            val colors = LinkedHashMap<String, String>()
            val dimens = LinkedHashMap<String, String>()
            fun isValues(p: String) = Regex("(^|/)res/values(-[A-Za-z0-9+-]*)?/[^/]+\\.xml$").containsMatchIn(p)
            fun qualifier(p: String): String = Regex("(^|/)res/(values[^/]*)/").find(p)?.groupValues?.get(2).orEmpty()
            val ordered = files.keys.filter { isValues(it) }.sortedWith(compareBy<String>({ qualifier(it) != "values" }, { it }))
            for (path in ordered) {
                val q = qualifier(path)
                val night = q.contains("night")
                val plain = q == "values"
                if (!(plain || (dark && night))) continue
                if (night && !dark) continue
                when (val r = SimpleXml.parse(files.getValue(path))) {
                    is XmlResult.Error -> warnings += "$path: ${r.message} (line ${r.line})"
                    is XmlResult.Ok -> if (r.root.name == "resources") for (e in r.root.children) {
                        val n = e.attr("name") ?: continue
                        when (e.name) {
                            "string" -> strings[n] = e.text
                            "color" -> colors[n] = e.text
                            "dimen" -> dimens[n] = e.text
                        }
                    }
                }
            }
            return ResourceTable(strings, colors, dimens)
        }
    }
}
