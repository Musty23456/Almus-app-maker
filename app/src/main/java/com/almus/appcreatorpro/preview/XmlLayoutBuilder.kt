package com.almus.appcreatorpro.preview

data class LayoutFile(val path: String, val text: String)

/** The result of building a preview tree: the tree (null when nothing could be drawn) and everything that was not exact. */
data class BuiltTree(val root: PreviewNode?, val warnings: List<String>)

/**
 * Turns an Android layout XML into a [PreviewNode] tree. This reads the SOURCE: it does not inflate views, run an
 * Android runtime or apply themes and styles. Unknown and custom views become labelled placeholders, and every
 * attribute or widget that is not interpreted is reported as a warning instead of being silently dropped.
 */
class XmlLayoutBuilder(
    private val resources: ResourceTable,
    private val layouts: Map<String, LayoutFile>,
    private val palette: PreviewPalette,
) {
    private val warnings = ArrayList<String>()
    private var budget = 3000

    fun build(file: LayoutFile): BuiltTree {
        warnings.clear()
        budget = 3000
        val node = convertFile(file, 0)
        // A layout is shown filling the screen, like an activity's content.
        val root = node?.copy(width = SizeSpec.MATCH, height = SizeSpec.MATCH, background = node.background ?: palette.background)
        return BuiltTree(root, warnings.toList())
    }

    private fun warn(path: String, line: Int?, msg: String) { if (warnings.size < 80) warnings += "$path${line?.let { ":$it" } ?: ""}: $msg" }

    private fun convertFile(file: LayoutFile, depth: Int): PreviewNode? {
        if (depth > 6) { warn(file.path, null, "Layouts are included more than 6 levels deep; the rest is not drawn."); return null }
        return when (val r = SimpleXml.parse(file.text)) {
            is XmlResult.Error -> { warn(file.path, r.line, "XML error: ${r.message}"); null }
            is XmlResult.Ok -> convert(r.root, file, depth)
        }
    }

    private fun attr(e: XmlElement, name: String): String? = e.attr("android:$name") ?: e.attr(name)
    private fun appAttr(e: XmlElement, name: String): String? = e.attr("app:$name") ?: e.attr("android:$name")

    private fun simpleName(tag: String) = tag.substringAfterLast('.')

    private fun size(v: String?): SizeSpec = when (v) {
        null -> SizeSpec.WRAP
        "match_parent", "fill_parent" -> SizeSpec.MATCH
        "wrap_content" -> SizeSpec.WRAP
        else -> resources.dp(v)?.let { SizeSpec.exact(it) } ?: SizeSpec.WRAP
    }

    private fun dp(v: String?): Float = resources.dp(v) ?: 0f

    private fun edges(e: XmlElement, base: String, layoutPrefix: Boolean): Edges {
        val p = if (layoutPrefix) "layout_" else ""
        val all = dp(attr(e, "$p$base"))
        val h = attr(e, "$p${base}Horizontal")?.let { dp(it) } ?: all
        val v = attr(e, "$p${base}Vertical")?.let { dp(it) } ?: all
        fun side(vararg names: String, fallback: Float): Float = names.firstNotNullOfOrNull { n -> attr(e, "$p$base$n")?.let { dp(it) } } ?: fallback
        return Edges(side("Left", "Start", fallback = h), side("Top", fallback = v), side("Right", "End", fallback = h), side("Bottom", fallback = v))
    }

    private fun idOf(e: XmlElement): String? = attr(e, "id")?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }

    private fun ref(v: String?): String? = v?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }

    private fun anchors(e: XmlElement, path: String): Anchors? {
        fun a(vararg names: String): String? = names.firstNotNullOfOrNull { appAttr(e, "layout_constraint$it")?.let { v -> if (v == "parent") "parent" else ref(v) } }
        val anchors = Anchors(
            startToStart = a("Start_toStartOf", "Left_toLeftOf"), startToEnd = a("Start_toEndOf", "Left_toRightOf"),
            endToEnd = a("End_toEndOf", "Right_toRightOf"), endToStart = a("End_toStartOf", "Right_toLeftOf"),
            topToTop = a("Top_toTopOf"), topToBottom = a("Top_toBottomOf"), bottomToBottom = a("Bottom_toBottomOf"), bottomToTop = a("Bottom_toTopOf"),
            hBias = appAttr(e, "layout_constraintHorizontal_bias")?.toFloatOrNull() ?: 0.5f,
            vBias = appAttr(e, "layout_constraintVertical_bias")?.toFloatOrNull() ?: 0.5f,
        )
        for (k in e.attrs.keys) {
            val n = k.substringAfter(':')
            if (n.startsWith("layout_constraint") && listOf("Chain", "Guide", "Ratio", "Barrier", "_percent", "Baseline", "Circle", "creator").any { n.contains(it) }) {
                warn(path, e.line, "${n} is not supported by the preview; the position of <${simpleName(e.name)}> may differ.")
            }
        }
        return anchors
    }

    private fun convert(e: XmlElement, file: LayoutFile, depth: Int): PreviewNode? {
        if (--budget < 0) return null
        val path = file.path
        val name = simpleName(e.name)
        if (e.name == "requestFocus" || e.name == "tag" || e.name == "data") return null
        // Data binding: <layout><data/>...the real root...</layout>
        if (e.name == "layout") return e.children.firstOrNull { it.name != "data" }?.let { convert(it, file, depth) }
        if (attr(e, "visibility") == "gone") return null

        if (e.name == "include") {
            val target = ref(e.attr("layout"))
            val inc = target?.let { layouts[it] }
            if (inc == null) { warn(path, e.line, "<include> of ${e.attr("layout")} could not be found; it is not drawn."); return null }
            return convertFile(inc, depth + 1)
        }

        val kids = e.children.mapNotNull { convert(it, file, depth) }
        val common = PreviewNode(
            type = NodeType.PLACEHOLDER, tag = name, id = idOf(e),
            width = size(attr(e, "layout_width")), height = size(attr(e, "layout_height")),
            margin = edges(e, "margin", true), padding = edges(e, "padding", false),
            weight = attr(e, "layout_weight")?.toFloatOrNull() ?: 0f,
            gravity = Grav.parse(attr(e, "gravity")), layoutGravity = Grav.parse(attr(e, "layout_gravity")),
            background = resources.color(attr(e, "background")),
            textColor = resources.color(attr(e, "textColor")),
            invisible = attr(e, "visibility") == "invisible",
            sourcePath = path, line = e.line, children = kids,
        )
        val rawText = attr(e, "text") ?: e.attr("tools:text")
        val text = resources.string(rawText) ?: rawText?.takeIf { it.startsWith("@") }
        val hint = resources.string(attr(e, "hint") ?: e.attr("tools:hint"))
        val sp = resources.dp(attr(e, "textSize")) ?: 14f
        val bold = attr(e, "textStyle")?.contains("bold") == true
        val text0 = common.copy(text = text, hint = hint, textSizeSp = sp, bold = bold)

        val built = when (name) {
            "LinearLayout", "LinearLayoutCompat" -> common.copy(type = NodeType.LINEAR, vertical = attr(e, "orientation") == "vertical")
            "AppBarLayout" -> common.copy(type = NodeType.LINEAR, vertical = true)
            "TextInputLayout" -> common.copy(type = NodeType.LINEAR, vertical = true)
            "FrameLayout", "CoordinatorLayout", "FragmentContainerView", "merge" -> {
                if (name == "FragmentContainerView") common.copy(type = NodeType.PLACEHOLDER, text = "Fragment container", background = palette.placeholder)
                else common.copy(type = NodeType.FRAME)
            }
            "RelativeLayout", "TableLayout", "GridLayout" -> {
                warn(path, e.line, "<$name> positions are not computed; its children are stacked at the top-left corner.")
                common.copy(type = NodeType.FRAME)
            }
            "ConstraintLayout" -> common.copy(type = NodeType.CONSTRAINT)
            "ScrollView", "NestedScrollView" -> common.copy(type = NodeType.SCROLL, vertical = true)
            "HorizontalScrollView" -> common.copy(type = NodeType.SCROLL, vertical = false)
            "CardView", "MaterialCardView" -> common.copy(type = NodeType.CARD, background = common.background ?: palette.surface, corner = 12f)
            "TextView", "AppCompatTextView", "MaterialTextView", "CheckedTextView" -> text0.copy(type = NodeType.TEXT, textColor = common.textColor ?: palette.onSurface)
            "Button", "AppCompatButton", "MaterialButton", "ToggleButton" -> text0.copy(type = NodeType.BUTTON, background = common.background ?: palette.primary, textColor = common.textColor ?: palette.onPrimary, corner = 20f)
            "EditText", "AppCompatEditText", "TextInputEditText", "AutoCompleteTextView", "MaterialAutoCompleteTextView" ->
                text0.copy(type = NodeType.EDIT, textSizeSp = if (attr(e, "textSize") == null) 16f else sp, textColor = common.textColor ?: palette.onSurface)
            "ImageView", "AppCompatImageView", "ShapeableImageView", "ImageButton" -> common.copy(type = NodeType.IMAGE, background = common.background ?: palette.placeholder)
            "FloatingActionButton", "ExtendedFloatingActionButton" ->
                common.copy(type = NodeType.BUTTON, text = text ?: "+", width = if (common.width.kind == SizeKind.WRAP) SizeSpec.exact(56f) else common.width, height = SizeSpec.exact(56f), background = palette.primary, textColor = palette.onPrimary, corner = 28f)
            "Switch", "SwitchCompat", "SwitchMaterial", "MaterialSwitch" -> common.copy(type = NodeType.SWITCH)
            "CheckBox", "RadioButton", "AppCompatCheckBox", "AppCompatRadioButton", "MaterialCheckBox", "MaterialRadioButton" -> text0.copy(type = NodeType.CHECK, textColor = common.textColor ?: palette.onSurface)
            "ProgressBar" -> common.copy(type = NodeType.PROGRESS, tag = if (e.attrs.values.any { it.contains("Horizontal") }) "LinearProgress" else "ProgressBar")
            "Space" -> common.copy(type = NodeType.SPACER)
            "View" -> common.copy(type = NodeType.SPACER)
            "Toolbar", "MaterialToolbar" -> text0.copy(type = NodeType.TOOLBAR, text = resources.string(attr(e, "title")) ?: text, background = common.background ?: palette.surface, textSizeSp = 20f, textColor = common.textColor ?: palette.onSurface,
                width = if (common.width.kind == SizeKind.WRAP) SizeSpec.MATCH else common.width, height = if (common.height.kind == SizeKind.WRAP) SizeSpec.exact(56f) else common.height)
            "RecyclerView", "ListView", "GridView", "ViewPager2", "ViewPager" -> {
                warn(path, e.line, "<$name> content comes from an adapter at run time; a striped placeholder is drawn.")
                common.copy(type = NodeType.LIST, children = emptyList())
            }
            else -> {
                warn(path, e.line, "<${e.name}> is not a view the preview knows; it is shown as a labelled placeholder.")
                common.copy(type = NodeType.PLACEHOLDER, text = name, background = palette.placeholder, children = emptyList())
            }
        }
        return applyAnchors(built, e, path)
    }

    private fun applyAnchors(n: PreviewNode, e: XmlElement, path: String): PreviewNode {
        val has = e.attrs.keys.any { it.substringAfter(':').startsWith("layout_constraint") }
        return if (has) n.copy(anchors = anchors(e, path)) else n
    }
}
