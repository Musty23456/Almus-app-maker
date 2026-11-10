package com.almus.appcreatorpro.preview

enum class NodeType { LINEAR, FRAME, CONSTRAINT, SCROLL, TEXT, BUTTON, EDIT, IMAGE, SWITCH, CHECK, PROGRESS, SPACER, CARD, LIST, TOOLBAR, PLACEHOLDER }

enum class SizeKind { MATCH, WRAP, EXACT }

data class SizeSpec(val kind: SizeKind, val dp: Float = 0f) {
    companion object {
        val MATCH = SizeSpec(SizeKind.MATCH)
        val WRAP = SizeSpec(SizeKind.WRAP)
        fun exact(dp: Float) = SizeSpec(SizeKind.EXACT, dp)
    }
}

data class Edges(val left: Float = 0f, val top: Float = 0f, val right: Float = 0f, val bottom: Float = 0f) {
    val horizontal: Float get() = left + right
    val vertical: Float get() = top + bottom

    companion object {
        val ZERO = Edges()
        fun all(v: Float) = Edges(v, v, v, v)
    }
}

/** Gravity flags, shared by Android `gravity`/`layout_gravity` and Compose alignments. */
object Grav {
    const val START = 1
    const val END = 2
    const val TOP = 4
    const val BOTTOM = 8
    const val CENTER_H = 16
    const val CENTER_V = 32
    const val CENTER = CENTER_H or CENTER_V

    fun parse(s: String?): Int {
        var g = 0
        for (part in s.orEmpty().split('|')) {
            g = g or when (part.trim()) {
                "start", "left" -> START
                "end", "right" -> END
                "top" -> TOP
                "bottom" -> BOTTOM
                "center_horizontal" -> CENTER_H
                "center_vertical" -> CENTER_V
                "center" -> CENTER
                else -> 0
            }
        }
        return g
    }

    fun has(g: Int, flag: Int) = g and flag != 0
}

/** The parent-and-sibling anchors of one ConstraintLayout child. Chains, guidelines, barriers and ratios are not supported. */
data class Anchors(
    val startToStart: String? = null, val startToEnd: String? = null, val endToEnd: String? = null, val endToStart: String? = null,
    val topToTop: String? = null, val topToBottom: String? = null, val bottomToBottom: String? = null, val bottomToTop: String? = null,
    val hBias: Float = 0.5f, val vBias: Float = 0.5f,
)

/**
 * One drawn view, common to the XML and the Compose previews, so one layout engine and one renderer serve both.
 * [sourcePath] and [line] let a tap on the preview select the matching line in the editor.
 */
data class PreviewNode(
    val type: NodeType,
    val tag: String,
    val id: String? = null,
    val text: String? = null,
    val hint: String? = null,
    val width: SizeSpec = SizeSpec.WRAP,
    val height: SizeSpec = SizeSpec.WRAP,
    val margin: Edges = Edges.ZERO,
    val padding: Edges = Edges.ZERO,
    /** Orientation of LINEAR; scroll axis of SCROLL. */
    val vertical: Boolean = true,
    val weight: Float = 0f,
    /** How this node places its children (Android `gravity`, Compose arrangement/alignment). */
    val gravity: Int = 0,
    /** How this node is placed by its parent (Android `layout_gravity`, Compose `align`). */
    val layoutGravity: Int = 0,
    val spacing: Float = 0f,
    val background: Int? = null,
    val textColor: Int? = null,
    val textSizeSp: Float = 14f,
    val bold: Boolean = false,
    val invisible: Boolean = false,
    val corner: Float = 0f,
    val anchors: Anchors? = null,
    val children: List<PreviewNode> = emptyList(),
    val sourcePath: String? = null,
    val line: Int? = null,
)

/** A node with its final position and size in dp, relative to the screen's top-left corner. */
data class LaidOut(
    val node: PreviewNode,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val children: List<LaidOut> = emptyList(),
    /** Content was cut off by a scroll container or by the screen. */
    val clipped: Boolean = false,
) {
    /** The deepest node under the point, for tap-to-source. */
    fun hit(px: Float, py: Float): LaidOut? {
        if (px < x || py < y || px > x + w || py > y + h) return null
        for (c in children.asReversed()) c.hit(px, py)?.let { return it }
        return this
    }

    fun count(): Int = 1 + children.sumOf { it.count() }
}
