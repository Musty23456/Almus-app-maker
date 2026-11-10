package com.almus.appcreatorpro.preview

/**
 * Draws an OUTLINE of a @Composable function from its source. Compose code is never compiled or run here. The builder
 * reads the nesting of well-known composables (Column, Row, Box, Text, Button, TextField, Scaffold, ...) and a small
 * subset of modifiers (size, padding, weight, background, alignment) and places them with [LayoutEngine].
 *
 * What it cannot do, and says so: it does not evaluate state, parameters, `if`/`when`/`for`/`items` (everything inside
 * is drawn once, as if every branch ran), themes, shapes, animations, custom layouts or composables whose source is not
 * in the project. Values it cannot read are shown as ‹name›. Composables defined in the project are expanded up to 4
 * levels deep, with their parameters unknown.
 */
class ComposeOutlineBuilder(
    private val resources: ResourceTable,
    private val palette: PreviewPalette,
    private val sources: Map<String, String>,
) {
    private val warnings = ArrayList<String>()
    private val warnedOnce = HashSet<String>()
    private val tokCache = HashMap<String, List<Tok>>()
    private var controlFlowSeen = false
    private var budget = 2000

    private val fnIndex: Map<String, Pair<String, ComposableFn>> by lazy {
        val m = LinkedHashMap<String, Pair<String, ComposableFn>>()
        for (p in sources.keys.sorted()) for (f in ComposeSource.functions(tokens(p))) m.putIfAbsent(f.name, p to f)
        m
    }

    private fun tokens(path: String): List<Tok> = tokCache.getOrPut(path) { KtLexer.tokenize(sources[path].orEmpty()) }

    private fun warnOnce(key: String, msg: String) { if (warnedOnce.add(key) && warnings.size < 60) warnings += msg }

    fun build(path: String, function: String): BuiltTree {
        warnings.clear(); warnedOnce.clear(); controlFlowSeen = false; budget = 2000
        if (path !in sources) return BuiltTree(null, listOf("$path: the file was not found."))
        val fn = ComposeSource.functions(tokens(path)).firstOrNull { it.name == function }
            ?: return BuiltTree(null, listOf("$path: no @Composable function named $function was found."))
        val nodes = Walker(tokens(path), path, 0, setOf(function)).body(fn.tokenIndex)
        if (controlFlowSeen) warnings += "Conditional and repeated UI (if, when, for, items) is not evaluated: everything inside is drawn once, as if every branch ran."
        if (nodes.isEmpty()) return BuiltTree(null, warnings + "No known composables were found in $function; there is nothing to draw.")
        val content = if (nodes.size == 1) nodes[0] else PreviewNode(NodeType.LINEAR, "Column", vertical = true, children = nodes, sourcePath = path, line = null)
        val root = PreviewNode(NodeType.FRAME, "Preview", width = SizeSpec.MATCH, height = SizeSpec.MATCH, background = palette.background, children = listOf(content), sourcePath = path, line = null)
        return BuiltTree(root, warnings.toList())
    }

    private data class Mod(val w: SizeSpec? = null, val h: SizeSpec? = null, val padding: Edges? = null, val bg: Int? = null, val corner: Float = 0f, val weight: Float = 0f, val align: Int = 0)
    private class Arg(val name: String?, val from: Int, val to: Int)

    private inner class Walker(val toks: List<Tok>, val path: String, val depth: Int, val stack: Set<String>) {

        fun body(funIdx: Int): List<PreviewNode> {
            var j = funIdx + 1
            if (toks.getOrNull(j)?.isSym("<") == true) { while (j < toks.size && !toks[j].isSym(">")) j++; j++ }
            val open = j + 1
            if (toks.getOrNull(open)?.isSym("(") != true) return emptyList()
            val close = KtLexer.matching(toks, open)
            if (close < 0) return emptyList()
            var k = close + 1
            while (k < toks.size && !toks[k].isSym("{") && !toks[k].isSym("=")) k++
            if (k >= toks.size) return emptyList()
            if (toks[k].isSym("{")) {
                val end = KtLexer.matching(toks, k)
                return sequence(k + 1, if (end < 0) toks.size else end)
            }
            var depthBr = 0
            var e = k + 1
            while (e < toks.size) {
                val t = toks[e]
                if (t.isSym("{")) depthBr++ else if (t.isSym("}")) depthBr-- 
                if (depthBr <= 0 && (t.isId("fun") || t.isSym("@"))) break
                e++
            }
            return sequence(k + 1, e)
        }

        private fun isKnown(name: String) = name in KNOWN

        fun sequence(from: Int, to: Int): List<PreviewNode> {
            val out = ArrayList<PreviewNode>()
            var i = from
            while (i < to && i < toks.size) {
                val t = toks[i]
                if (t.kind == TokKind.ID) {
                    if (t.text in CONTROL) controlFlowSeen = true
                    val prev = toks.getOrNull(i - 1)
                    val next = toks.getOrNull(i + 1)
                    val afterDot = prev != null && (prev.isSym(".") || prev.isId("fun"))
                    val callable = !afterDot && t.text.firstOrNull()?.isUpperCase() == true
                    val expandable = callable && !isKnown(t.text) && fnIndex.containsKey(t.text) && t.text !in stack && depth < 4
                    if (callable && (isKnown(t.text) || expandable) && next != null && (next.isSym("(") || (next.isSym("{") && t.text in LAMBDA_ONLY))) {
                        val (node, after) = call(i, to, expandable)
                        if (node != null) out += node
                        i = maxOf(after, i + 1)
                        continue
                    }
                }
                i++
            }
            return out
        }

        private fun call(i: Int, limit: Int, expandable: Boolean): Pair<PreviewNode?, Int> {
            val name = toks[i].text
            var k = i + 1
            var args: List<Arg> = emptyList()
            if (toks[k].isSym("(")) {
                val close = KtLexer.matching(toks, k)
                if (close < 0 || close >= limit) return null to (i + 1)
                args = splitArgs(k + 1, close)
                k = close + 1
            }
            var lam: Pair<Int, Int>? = null
            if (k < limit && toks.getOrNull(k)?.isSym("{") == true) {
                val close = KtLexer.matching(toks, k)
                if (close > 0) { lam = (k + 1) to close; k = close + 1 }
            }
            if (--budget < 0) return null to k
            val node = if (expandable) expandUser(name, args) else makeNode(name, toks[i].line, args, lam)
            return node to k
        }

        private fun expandUser(name: String, args: List<Arg>): PreviewNode? {
            val (p, fn) = fnIndex[name] ?: return null
            val inner = Walker(tokens(p), p, depth + 1, stack + name).body(fn.tokenIndex)
            if (inner.isEmpty()) return PreviewNode(NodeType.PLACEHOLDER, name, text = name, background = palette.placeholder, sourcePath = path, line = toks.getOrNull(0)?.line)
            val root = if (inner.size == 1) inner[0] else PreviewNode(NodeType.LINEAR, name, vertical = true, children = inner, sourcePath = p, line = fn.line)
            val m = modifierOf(args)
            return root.copy(
                width = m.w ?: root.width, height = m.h ?: root.height, padding = m.padding ?: root.padding,
                weight = if (m.weight > 0f) m.weight else root.weight, layoutGravity = if (m.align != 0) m.align else root.layoutGravity,
                background = m.bg ?: root.background,
            )
        }

        // ---- arguments

        private fun splitArgs(from: Int, to: Int): List<Arg> {
            val out = ArrayList<Arg>()
            var depthBr = 0
            var start = from
            fun make(a: Int, b: Int): Arg =
                if (b - a >= 3 && toks[a].kind == TokKind.ID && toks[a + 1].isSym("=") && !toks[a + 2].isSym("=")) Arg(toks[a].text, a + 2, b) else Arg(null, a, b)
            for (k in from until to) {
                val t = toks[k]
                if (t.kind != TokKind.SYM) continue
                if (t.text == "(" || t.text == "{" || t.text == "[") depthBr++
                else if (t.text == ")" || t.text == "}" || t.text == "]") depthBr--
                else if (t.text == "," && depthBr == 0) { if (k > start) out += make(start, k); start = k + 1 }
            }
            if (start < to) out += make(start, to)
            return out
        }

        private fun named(args: List<Arg>, n: String): Arg? = args.firstOrNull { it.name == n }

        private fun slot(args: List<Arg>, n: String): List<PreviewNode> {
            val a = named(args, n) ?: return emptyList()
            if (!toks[a.from].isSym("{")) return emptyList()
            val close = KtLexer.matching(toks, a.from)
            return if (close < 0) emptyList() else sequence(a.from + 1, close)
        }

        private fun slotText(args: List<Arg>, n: String): String? {
            val a = named(args, n) ?: return null
            if (!toks[a.from].isSym("{")) return null
            val close = KtLexer.matching(toks, a.from).let { if (it < 0) a.to else it }
            var k = a.from + 1
            while (k < close) {
                val t = toks[k]
                if (t.kind == TokKind.STR) return unescape(t.text)
                if (t.isId("stringResource")) return textFrom(k, close)
                k++
            }
            return null
        }

        private fun unescape(s: String) = s.replace("\\n", "\n").replace("\\\"", "\"").replace("\\'", "'")

        private fun textFrom(a: Int, b: Int): String {
            if (a >= b) return ""
            val first = toks[a]
            if (first.kind == TokKind.STR && (b - a == 1 || !toks[a + 1].isSym("+"))) return unescape(first.text)
            if (first.isId("stringResource")) {
                var name: String? = null
                for (k in a until b) if (toks[k].kind == TokKind.ID && k > a + 1 && toks.getOrNull(k - 1)?.isSym(".") == true) name = toks[k].text
                if (name != null) return resources.string("@string/$name") ?: "@string/$name"
            }
            return "\u2039" + toks.subList(a, b).joinToString("") { it.text }.take(24) + "\u203A"
        }

        private fun textArg(args: List<Arg>): String? {
            val a = named(args, "text") ?: args.firstOrNull { it.name == null } ?: return null
            if (toks[a.from].isSym("{")) return null
            return textFrom(a.from, a.to)
        }

        private fun dpOf(a: Int, b: Int): Float? {
            if (b - a == 3 && toks[a].kind == TokKind.NUM && toks[a + 1].isSym(".") && (toks[a + 2].isId("dp") || toks[a + 2].isId("sp"))) return numOf(toks[a].text)
            if (b - a == 1 && toks[a].kind == TokKind.NUM) return numOf(toks[a].text)
            if (b - a >= 4 && toks[a].isId("Dp") && toks[a + 1].isSym("(")) return numOf(toks[a + 2].text)
            return null
        }

        private fun numOf(t: String): Float? = t.trimEnd('f', 'F', 'L', 'l').replace("_", "").toFloatOrNull()

        private fun dpOf(a: Arg?): Float? = a?.let { dpOf(it.from, it.to) }

        private fun lastId(a: Int, b: Int): String? = (b - 1 downTo a).firstOrNull { toks[it].kind == TokKind.ID }?.let { toks[it].text }

        private fun colorOf(a: Int, b: Int): Int? {
            if (a >= b) return null
            val t = toks[a]
            if (t.isId("Color") && toks.getOrNull(a + 1)?.isSym("(") == true && toks.getOrNull(a + 2)?.kind == TokKind.NUM) {
                val raw = toks[a + 2].text.replace("_", "")
                return if (raw.startsWith("0x") || raw.startsWith("0X")) raw.drop(2).trimEnd('L', 'l').toLongOrNull(16)?.toInt() else null
            }
            if (t.isId("Color") && toks.getOrNull(a + 1)?.isSym(".") == true) {
                return when (lastId(a, b)) {
                    "White" -> 0xFFFFFFFF.toInt(); "Black" -> 0xFF000000.toInt(); "Red" -> 0xFFFF0000.toInt(); "Green" -> 0xFF00FF00.toInt()
                    "Blue" -> 0xFF0000FF.toInt(); "Yellow" -> 0xFFFFFF00.toInt(); "Cyan" -> 0xFF00FFFF.toInt(); "Magenta" -> 0xFFFF00FF.toInt()
                    "Gray" -> 0xFF888888.toInt(); "LightGray" -> 0xFFCCCCCC.toInt(); "DarkGray" -> 0xFF444444.toInt(); "Transparent" -> 0x00000000
                    else -> null
                }
            }
            if (t.isId("MaterialTheme")) {
                return when (lastId(a, b)) {
                    "primary" -> palette.primary; "onPrimary" -> palette.onPrimary; "background" -> palette.background; "surface" -> palette.surface
                    "onSurface", "onBackground" -> palette.onSurface; "surfaceVariant", "secondaryContainer", "primaryContainer" -> palette.placeholder
                    "outline" -> palette.outline; "error" -> if (palette === PreviewPalette.DARK) 0xFFF2B8B5.toInt() else 0xFFB3261E.toInt()
                    else -> null
                }
            }
            if (t.isId("colorResource")) return lastId(a, b)?.let { resources.color("@color/$it") }
            return null
        }

        // ---- modifiers

        private fun modifierOf(args: List<Arg>): Mod {
            val a = named(args, "modifier") ?: return Mod()
            var k = a.from
            while (k < a.to && !(toks[k].isId("Modifier") || toks[k].isId("modifier"))) k++
            k++
            var m = Mod()
            while (k < a.to && toks[k].isSym(".")) {
                val nameTok = toks.getOrNull(k + 1) ?: break
                if (nameTok.kind != TokKind.ID) break
                var next = k + 2
                var margs: List<Arg> = emptyList()
                if (next < a.to && toks[next].isSym("(")) {
                    val c = KtLexer.matching(toks, next)
                    if (c < 0 || c >= a.to) break
                    margs = splitArgs(next + 1, c)
                    next = c + 1
                }
                if (next < a.to && toks[next].isSym("{")) { val c = KtLexer.matching(toks, next); next = if (c < 0) a.to else c + 1 }
                m = applyModifier(m, nameTok.text, margs)
                k = next
            }
            return m
        }

        private fun applyModifier(m: Mod, name: String, a: List<Arg>): Mod = when (name) {
            "fillMaxSize" -> m.copy(w = SizeSpec.MATCH, h = SizeSpec.MATCH)
            "fillMaxWidth" -> m.copy(w = SizeSpec.MATCH)
            "fillMaxHeight" -> m.copy(h = SizeSpec.MATCH)
            "width", "requiredWidth" -> dpOf(a.firstOrNull())?.let { m.copy(w = SizeSpec.exact(it)) } ?: m
            "height", "requiredHeight" -> dpOf(a.firstOrNull())?.let { m.copy(h = SizeSpec.exact(it)) } ?: m
            "size", "requiredSize" -> {
                val w = dpOf(a.getOrNull(0)); val h = dpOf(a.getOrNull(1) ?: a.getOrNull(0))
                m.copy(w = w?.let { SizeSpec.exact(it) } ?: m.w, h = h?.let { SizeSpec.exact(it) } ?: m.h)
            }
            "padding" -> m.copy(padding = paddingOf(a) ?: m.padding)
            "background" -> {
                val c = a.firstOrNull()?.let { colorOf(it.from, it.to) }
                val shape = a.getOrNull(1) ?: named(a, "shape")
                m.copy(bg = c ?: m.bg, corner = shape?.let { cornerOf(it.from, it.to) } ?: m.corner)
            }
            "clip" -> m.copy(corner = a.firstOrNull()?.let { cornerOf(it.from, it.to) } ?: m.corner)
            "weight" -> m.copy(weight = a.firstOrNull()?.let { numOf(toks[it.from].text) } ?: m.weight)
            "align" -> m.copy(align = a.firstOrNull()?.let { alignOf(it.from, it.to) } ?: m.align)
            "aspectRatio", "offset", "absoluteOffset" -> { warnOnce("mod-$name", "Modifier.$name is not applied by the preview."); m }
            else -> m
        }

        private fun cornerOf(a: Int, b: Int): Float {
            if (toks[a].isId("CircleShape")) return 999f
            if (toks[a].isId("RoundedCornerShape") && toks.getOrNull(a + 1)?.isSym("(") == true) {
                val close = KtLexer.matching(toks, a + 1)
                if (close > 0) return dpOf(a + 2, close) ?: 8f
            }
            return 0f
        }

        private fun paddingOf(a: List<Arg>): Edges? {
            val first = a.firstOrNull() ?: return null
            if (toks[first.from].isId("PaddingValues") && toks.getOrNull(first.from + 1)?.isSym("(") == true) {
                val close = KtLexer.matching(toks, first.from + 1)
                if (close > 0) return paddingOf(splitArgs(first.from + 2, close))
            }
            val named = a.filter { it.name != null }
            if (named.isNotEmpty()) {
                val v = named.associate { it.name!! to dpOf(it) }
                val all = v["all"]
                val h = v["horizontal"] ?: all
                val vt = v["vertical"] ?: all
                return Edges(v["start"] ?: h ?: 0f, v["top"] ?: vt ?: 0f, v["end"] ?: h ?: 0f, v["bottom"] ?: vt ?: 0f)
            }
            val pos = a.map { dpOf(it) ?: 0f }
            return when (pos.size) {
                1 -> Edges.all(pos[0])
                2 -> Edges(pos[0], pos[1], pos[0], pos[1])
                4 -> Edges(pos[0], pos[1], pos[2], pos[3])
                else -> null
            }
        }

        private fun alignOf(a: Int, b: Int): Int = when (lastId(a, b)) {
            "Center" -> Grav.CENTER
            "CenterHorizontally" -> Grav.CENTER_H
            "CenterVertically" -> Grav.CENTER_V
            "TopStart" -> Grav.TOP or Grav.START
            "TopCenter" -> Grav.TOP or Grav.CENTER_H
            "TopEnd" -> Grav.TOP or Grav.END
            "CenterStart" -> Grav.CENTER_V or Grav.START
            "CenterEnd" -> Grav.CENTER_V or Grav.END
            "BottomStart" -> Grav.BOTTOM or Grav.START
            "BottomCenter" -> Grav.BOTTOM or Grav.CENTER_H
            "BottomEnd" -> Grav.BOTTOM or Grav.END
            "Start" -> Grav.START
            "End" -> Grav.END
            "Top" -> Grav.TOP
            "Bottom" -> Grav.BOTTOM
            else -> 0
        }

        private fun arrangementOf(a: Arg?, vertical: Boolean): Pair<Int, Float> {
            if (a == null) return 0 to 0f
            val id = lastId(a.from, a.to)
            var spacing = 0f
            if (toks[a.from].isId("Arrangement") && toks.getOrNull(a.from + 1)?.isSym(".") == true && toks.getOrNull(a.from + 2)?.isId("spacedBy") == true) {
                val close = KtLexer.matching(toks, a.from + 3)
                if (close > 0) spacing = dpOf(a.from + 4, minOf(close, a.to)) ?: 0f
                return 0 to spacing
            }
            return when (id) {
                "Center" -> (if (vertical) Grav.CENTER_V else Grav.CENTER_H) to 0f
                "Bottom", "End" -> (if (vertical) Grav.BOTTOM else Grav.END) to 0f
                "SpaceBetween", "SpaceAround", "SpaceEvenly" -> { warnOnce("arr-$id", "Arrangement.$id is not computed; children are packed at the start."); 0 to 0f }
                else -> 0 to 0f
            }
        }

        private fun typographySize(a: Arg?): Float? = a?.let {
            when (lastId(it.from, it.to)) {
                "displayLarge" -> 57f; "displayMedium" -> 45f; "displaySmall" -> 36f
                "headlineLarge" -> 32f; "headlineMedium" -> 28f; "headlineSmall" -> 24f
                "titleLarge" -> 22f; "titleMedium" -> 16f; "titleSmall" -> 14f
                "bodyLarge" -> 16f; "bodyMedium" -> 14f; "bodySmall" -> 12f
                "labelLarge" -> 14f; "labelMedium" -> 12f; "labelSmall" -> 11f
                else -> null
            }
        }

        // ---- nodes

        private fun makeNode(name: String, line: Int, args: List<Arg>, lam: Pair<Int, Int>?): PreviewNode? {
            val mod = modifierOf(args)
            fun base(type: NodeType) = PreviewNode(
                type = type, tag = name, width = mod.w ?: SizeSpec.WRAP, height = mod.h ?: SizeSpec.WRAP, padding = mod.padding ?: Edges.ZERO,
                background = mod.bg, corner = mod.corner, weight = mod.weight, layoutGravity = mod.align, sourcePath = path, line = line,
            )
            val kids: List<PreviewNode> by lazy { lam?.let { sequence(it.first, it.second) } ?: emptyList() }
            val contentPadding = named(args, "contentPadding")?.let { paddingOf(listOf(it)) }
            when (name) {
                "Column", "LazyColumn", "ScrollableColumn" -> {
                    if (name != "Column") warnOnce("lazy", "Lazy lists are drawn once with the items written in the code; real lists scroll and recycle items.")
                    val (g, sp) = arrangementOf(named(args, "verticalArrangement"), true)
                    val ha = named(args, "horizontalAlignment")?.let { alignOf(it.from, it.to) } ?: 0
                    val b = base(NodeType.LINEAR)
                    return b.copy(vertical = true, gravity = g or ha, spacing = sp, padding = contentPadding ?: b.padding, children = kids)
                }
                "Row", "LazyRow" -> {
                    if (name != "Row") warnOnce("lazy", "Lazy lists are drawn once with the items written in the code; real lists scroll and recycle items.")
                    val (g, sp) = arrangementOf(named(args, "horizontalArrangement"), false)
                    val va = named(args, "verticalAlignment")?.let { alignOf(it.from, it.to) } ?: 0
                    val b = base(NodeType.LINEAR)
                    return b.copy(vertical = false, gravity = g or va, spacing = sp, padding = contentPadding ?: b.padding, children = kids)
                }
                "LazyVerticalGrid", "LazyHorizontalGrid", "LazyVerticalStaggeredGrid" -> {
                    warnOnce("grid", "Grids are drawn as a single column.")
                    return base(NodeType.LINEAR).copy(vertical = true, children = kids)
                }
                "Box", "BoxWithConstraints" -> return base(NodeType.FRAME).copy(gravity = named(args, "contentAlignment")?.let { alignOf(it.from, it.to) } ?: 0, children = kids)
                "Surface" -> return base(NodeType.CARD).copy(background = mod.bg ?: palette.background, children = kids)
                "Card", "ElevatedCard", "OutlinedCard" -> return base(NodeType.CARD).copy(background = mod.bg ?: palette.surface, corner = if (mod.corner > 0f) mod.corner else 12f, children = kids)
                "Scaffold" -> {
                    val top = slot(args, "topBar")
                    val bottom = slot(args, "bottomBar")
                    val fab = slot(args, "floatingActionButton")
                    val content = PreviewNode(NodeType.FRAME, "content", width = SizeSpec.MATCH, height = SizeSpec.MATCH, weight = 1f, children = kids, sourcePath = path, line = line)
                    val column = PreviewNode(NodeType.LINEAR, "Scaffold", width = SizeSpec.MATCH, height = SizeSpec.MATCH, vertical = true, children = top + content + bottom, sourcePath = path, line = line)
                    val fabs = fab.map { it.copy(layoutGravity = Grav.BOTTOM or Grav.END, margin = Edges(0f, 0f, 16f, if (bottom.isEmpty()) 16f else 96f)) }
                    return base(NodeType.FRAME).copy(width = mod.w ?: SizeSpec.MATCH, height = mod.h ?: SizeSpec.MATCH, background = mod.bg ?: palette.background, children = listOf(column) + fabs)
                }
                "Text" -> {
                    val size = named(args, "fontSize")?.let { dpOf(it) } ?: typographySize(named(args, "style")) ?: 14f
                    val bold = named(args, "fontWeight")?.let { w -> lastId(w.from, w.to)?.let { it.contains("Bold") || it == "W700" || it == "W600" || it == "SemiBold" } } ?: false
                    val color = named(args, "color")?.let { colorOf(it.from, it.to) }
                    return base(NodeType.TEXT).copy(text = textArg(args) ?: "", textSizeSp = size, bold = bold, textColor = color)
                }
                "Button", "FilledTonalButton", "ElevatedButton", "OutlinedButton", "TextButton" -> {
                    val filled = name == "Button" || name == "ElevatedButton"
                    val textColor = if (filled) palette.onPrimary else if (name == "FilledTonalButton") palette.onSurface else palette.primary
                    val children = kids.map { if (it.type == NodeType.TEXT && it.textColor == null) it.copy(textColor = textColor) else it }
                    return base(NodeType.BUTTON).copy(
                        padding = mod.padding ?: Edges(24f, 8f, 24f, 8f), gravity = Grav.CENTER, corner = 20f, textColor = textColor,
                        background = mod.bg ?: when (name) { "Button", "ElevatedButton" -> palette.primary; "FilledTonalButton" -> palette.placeholder; else -> null },
                        text = if (children.isEmpty()) "Button" else null, children = children,
                    )
                }
                "IconButton" -> return base(NodeType.FRAME).copy(width = mod.w ?: SizeSpec.exact(48f), height = mod.h ?: SizeSpec.exact(48f), gravity = Grav.CENTER, children = kids)
                "FloatingActionButton", "SmallFloatingActionButton", "LargeFloatingActionButton", "ExtendedFloatingActionButton" -> {
                    val small = name.startsWith("Small")
                    val ext = name.startsWith("Extended")
                    val size = if (small) 40f else if (name.startsWith("Large")) 96f else 56f
                    return base(NodeType.BUTTON).copy(
                        width = mod.w ?: if (ext) SizeSpec.WRAP else SizeSpec.exact(size), height = mod.h ?: SizeSpec.exact(size), gravity = Grav.CENTER,
                        padding = if (ext) Edges(16f, 0f, 20f, 0f) else Edges.ZERO, corner = 16f, background = mod.bg ?: palette.placeholder, children = kids,
                    )
                }
                "Icon" -> return base(NodeType.IMAGE).copy(width = mod.w ?: SizeSpec.exact(24f), height = mod.h ?: SizeSpec.exact(24f), background = null, text = "icon")
                "Image", "AsyncImage" -> return base(NodeType.IMAGE).copy(background = mod.bg ?: palette.placeholder)
                "Spacer" -> return base(NodeType.SPACER)
                "Divider", "HorizontalDivider" -> return base(NodeType.SPACER).copy(width = mod.w ?: SizeSpec.MATCH, height = SizeSpec.exact(1f), background = palette.outline)
                "TextField", "OutlinedTextField", "BasicTextField" -> {
                    val value = named(args, "value")?.takeIf { toks[it.from].kind == TokKind.STR }?.let { textFrom(it.from, it.to) }
                    val hint = slotText(args, "label") ?: slotText(args, "placeholder")
                    return base(NodeType.EDIT).copy(text = value?.takeIf { it.isNotEmpty() }, hint = hint, textSizeSp = 16f, textColor = palette.onSurface)
                }
                "Switch" -> return base(NodeType.SWITCH)
                "Checkbox", "RadioButton", "TriStateCheckbox" -> return base(NodeType.CHECK)
                "Slider", "RangeSlider" -> return base(NodeType.PLACEHOLDER).copy(text = "Slider", background = palette.placeholder, width = mod.w ?: SizeSpec.MATCH)
                "CircularProgressIndicator" -> return base(NodeType.PROGRESS)
                "LinearProgressIndicator" -> return base(NodeType.PROGRESS).copy(tag = "LinearProgress", width = mod.w ?: SizeSpec.MATCH)
                "TopAppBar", "CenterAlignedTopAppBar", "MediumTopAppBar", "LargeTopAppBar", "SmallTopAppBar" ->
                    return base(NodeType.TOOLBAR).copy(text = slotText(args, "title") ?: "", width = mod.w ?: SizeSpec.MATCH, height = mod.h ?: SizeSpec.exact(64f), background = mod.bg ?: palette.surface, textSizeSp = 22f, textColor = palette.onSurface)
                "NavigationBar", "BottomAppBar", "TabRow", "NavigationRail" ->
                    return base(NodeType.LINEAR).copy(vertical = false, width = mod.w ?: SizeSpec.MATCH, height = mod.h ?: SizeSpec.exact(80f), gravity = Grav.CENTER_V, background = mod.bg ?: palette.surface, children = kids)
                "NavigationBarItem", "NavigationRailItem", "Tab", "LeadingIconTab" ->
                    return base(NodeType.PLACEHOLDER).copy(text = slotText(args, "label") ?: slotText(args, "text") ?: name, weight = 1f, layoutGravity = Grav.CENTER_V, background = null)
                "ListItem" -> {
                    val lead = slot(args, "leadingContent")
                    val trail = slot(args, "trailingContent")
                    val texts = listOfNotNull(slotText(args, "headlineContent"), slotText(args, "supportingContent")).mapIndexed { i, t ->
                        PreviewNode(NodeType.TEXT, "Text", text = t, textSizeSp = if (i == 0) 16f else 14f, sourcePath = path, line = line)
                    }
                    val mid = PreviewNode(NodeType.LINEAR, "ListItem", vertical = true, weight = 1f, children = texts, sourcePath = path, line = line)
                    return base(NodeType.LINEAR).copy(vertical = false, width = mod.w ?: SizeSpec.MATCH, padding = mod.padding ?: Edges(16f, 12f, 16f, 12f), gravity = Grav.CENTER_V, children = lead + mid + trail)
                }
                else -> return base(NodeType.PLACEHOLDER).copy(text = slotText(args, "label") ?: name, background = palette.placeholder, children = emptyList())
            }
        }
    }

    companion object {
        private val CONTROL = setOf("if", "when", "for", "while", "forEach", "forEachIndexed", "items", "itemsIndexed", "repeat")
        private val LAMBDA_ONLY = setOf("Column", "Row", "Box", "Card", "Surface", "LazyColumn", "LazyRow", "ElevatedCard", "OutlinedCard", "BoxWithConstraints", "Scaffold", "NavigationBar", "BottomAppBar", "NavigationRail")
        private val KNOWN = setOf(
            "Column", "LazyColumn", "ScrollableColumn", "Row", "LazyRow", "LazyVerticalGrid", "LazyHorizontalGrid", "LazyVerticalStaggeredGrid", "Box", "BoxWithConstraints",
            "Surface", "Card", "ElevatedCard", "OutlinedCard", "Scaffold", "Text", "Button", "FilledTonalButton", "ElevatedButton", "OutlinedButton", "TextButton",
            "IconButton", "FloatingActionButton", "SmallFloatingActionButton", "LargeFloatingActionButton", "ExtendedFloatingActionButton", "Icon", "Image", "AsyncImage",
            "Spacer", "Divider", "HorizontalDivider", "TextField", "OutlinedTextField", "BasicTextField", "Switch", "Checkbox", "RadioButton", "TriStateCheckbox",
            "Slider", "RangeSlider", "CircularProgressIndicator", "LinearProgressIndicator", "TopAppBar", "CenterAlignedTopAppBar", "MediumTopAppBar", "LargeTopAppBar",
            "SmallTopAppBar", "NavigationBar", "BottomAppBar", "TabRow", "NavigationRail", "NavigationBarItem", "NavigationRailItem", "Tab", "LeadingIconTab", "ListItem",
            "AssistChip", "FilterChip", "SuggestionChip", "InputChip", "Badge", "AlertDialog", "ModalBottomSheet",
        )
    }
}
