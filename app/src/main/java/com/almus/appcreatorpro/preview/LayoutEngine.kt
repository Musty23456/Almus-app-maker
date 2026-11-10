package com.almus.appcreatorpro.preview

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * A small layout engine for the approximate previews. It follows the SHAPE of Android's measure/layout rules for
 * LinearLayout (weights, gravity), FrameLayout, scroll containers and the simplest ConstraintLayout constraints, and
 * of Compose Column/Row/Box. Text is measured with an ESTIMATE (average glyph width), so wrapping and heights are
 * approximate. This is not the Android layout system.
 */
object LayoutEngine {
    const val BIG = 100_000f
    const val GLYPH_WIDTH = 0.52f
    const val LINE_HEIGHT = 1.25f

    fun layout(root: PreviewNode, widthDp: Float, heightDp: Float, fontScale: Float = 1f): LaidOut {
        val e = Engine(fontScale)
        val (w, h) = e.resolve(root, widthDp, heightDp)
        return e.place(root, 0f, 0f, w, h)
    }

    private class Engine(val fs: Float) {
        private fun frameLike(n: PreviewNode) = n.type == NodeType.FRAME || n.type == NodeType.CARD || n.type == NodeType.SCROLL || n.type == NodeType.CONSTRAINT ||
            ((n.type == NodeType.BUTTON || n.type == NodeType.TOOLBAR) && n.children.isNotEmpty())

        private fun textBox(n: PreviewNode, maxW: Float): Pair<Float, Float> {
            val t = n.text ?: n.hint ?: ""
            val sp = n.textSizeSp * fs
            val avail = max(1f, maxW - n.padding.horizontal)
            val explicitLines = t.split('\n')
            var lines = 0
            var widest = 0f
            for (l in explicitLines) {
                val lw = l.length * sp * GLYPH_WIDTH
                widest = max(widest, lw)
                lines += max(1, ceil(lw / avail).toInt())
            }
            return min(widest, avail) to lines * sp * LINE_HEIGHT
        }

        /** Border-box size of [n] for the space it is offered (margins already removed). */
        fun resolve(n: PreviewNode, availW: Float, availH: Float, wrapMatchW: Boolean = false, wrapMatchH: Boolean = false): Pair<Float, Float> {
            val wSpec = if (wrapMatchW && n.width.kind == SizeKind.MATCH) SizeSpec.WRAP else n.width
            val hSpec = if (wrapMatchH && n.height.kind == SizeKind.MATCH) SizeSpec.WRAP else n.height
            val wFixed: Float? = when (wSpec.kind) { SizeKind.MATCH -> availW; SizeKind.EXACT -> wSpec.dp; SizeKind.WRAP -> null }
            val nat = natural(n, wFixed ?: availW, availH)
            val w = wFixed ?: min(nat.first, availW)
            val h = when (hSpec.kind) { SizeKind.MATCH -> availH; SizeKind.EXACT -> hSpec.dp; SizeKind.WRAP -> nat.second }
            return w to h
        }

        /** Size the node wants when it wraps its content, given the widest it may be. */
        private fun natural(n: PreviewNode, maxW: Float, maxH: Float): Pair<Float, Float> = when {
            n.children.isNotEmpty() && n.type == NodeType.LINEAR -> linearNatural(n, maxW, maxH)
            n.children.isNotEmpty() && frameLike(n) -> frameNatural(n, maxW, maxH)
            else -> leafNatural(n, maxW)
        }

        private fun leafNatural(n: PreviewNode, maxW: Float): Pair<Float, Float> = when (n.type) {
            NodeType.TEXT -> textBox(n, maxW).let { (w, h) -> (w + n.padding.horizontal) to (h + n.padding.vertical) }
            NodeType.BUTTON -> textBox(n, maxW).let { (w, h) -> max(64f, w + 32f + n.padding.horizontal) to max(40f, h + 20f + n.padding.vertical) }
            NodeType.EDIT -> textBox(n, maxW).let { (w, h) -> max(160f, w + 32f) to max(48f, h + 24f) }
            NodeType.IMAGE -> 48f to 48f
            NodeType.SWITCH -> 52f to 32f
            NodeType.CHECK -> 40f to 40f
            NodeType.PROGRESS -> if (n.tag.contains("Linear", ignoreCase = true) || n.tag.contains("horizontal", ignoreCase = true)) 120f to 4f else 40f to 40f
            NodeType.SPACER -> 0f to 0f
            NodeType.TOOLBAR -> maxW to 56f
            NodeType.LIST -> min(maxW, 200f) to 200f
            NodeType.PLACEHOLDER -> textBox(n, maxW).let { (w, h) -> max(80f, w + 16f) to max(36f, h + 16f) }
            else -> n.padding.horizontal to n.padding.vertical
        }

        private fun outer(n: PreviewNode, w: Float, h: Float) = (w + n.margin.horizontal) to (h + n.margin.vertical)

        private fun linearNatural(n: PreviewNode, maxW: Float, maxH: Float): Pair<Float, Float> {
            val innerW = max(0f, maxW - n.padding.horizontal)
            val innerH = if (maxH >= BIG) BIG else max(0f, maxH - n.padding.vertical)
            var main = 0f
            var cross = 0f
            val kids = n.children
            for (c in kids) {
                val (cw, ch) = resolve(c, max(0f, innerW - c.margin.horizontal), max(0f, innerH - c.margin.vertical), wrapMatchW = true, wrapMatchH = true)
                val (ow, oh) = outer(c, if (c.weight > 0f && !n.vertical && c.width.kind != SizeKind.WRAP) 0f else cw, if (c.weight > 0f && n.vertical && c.height.kind != SizeKind.WRAP) 0f else ch)
                if (n.vertical) { main += oh; cross = max(cross, ow) } else { main += ow; cross = max(cross, oh) }
            }
            main += n.spacing * max(0, kids.size - 1)
            return if (n.vertical) (cross + n.padding.horizontal) to (main + n.padding.vertical) else (main + n.padding.horizontal) to (cross + n.padding.vertical)
        }

        private fun frameNatural(n: PreviewNode, maxW: Float, maxH: Float): Pair<Float, Float> {
            val innerW = max(0f, maxW - n.padding.horizontal)
            val scrollsV = n.type == NodeType.SCROLL && n.vertical
            val scrollsH = n.type == NodeType.SCROLL && !n.vertical
            val innerH = if (scrollsV || maxH >= BIG) BIG else max(0f, maxH - n.padding.vertical)
            val offerW = if (scrollsH) BIG else innerW
            var w = 0f
            var h = 0f
            for (c in n.children) {
                val (cw, ch) = resolve(c, max(0f, offerW - c.margin.horizontal), max(0f, innerH - c.margin.vertical), wrapMatchW = true, wrapMatchH = true)
                val (ow, oh) = outer(c, cw, ch)
                w = max(w, ow); h = max(h, oh)
            }
            return min(w + n.padding.horizontal, if (scrollsH) BIG else maxW) to (h + n.padding.vertical)
        }

        // ---- placement

        fun place(n: PreviewNode, x: Float, y: Float, w: Float, h: Float): LaidOut {
            if (n.children.isEmpty()) return LaidOut(n, x, y, w, h)
            val cx = x + n.padding.left
            val cy = y + n.padding.top
            val cw = max(0f, w - n.padding.horizontal)
            val ch = max(0f, h - n.padding.vertical)
            return when {
                n.type == NodeType.LINEAR -> placeLinear(n, x, y, w, h, cx, cy, cw, ch)
                n.type == NodeType.CONSTRAINT -> placeConstraint(n, x, y, w, h, cx, cy, cw, ch)
                else -> placeFrame(n, x, y, w, h, cx, cy, cw, ch)
            }
        }

        private fun crossOffset(g: Int, cross: Float, size: Float, startFlag: Int, endFlag: Int, centerFlag: Int): Float = when {
            Grav.has(g, centerFlag) -> (cross - size) / 2f
            Grav.has(g, endFlag) -> cross - size
            Grav.has(g, startFlag) -> 0f
            else -> 0f
        }

        private fun placeLinear(n: PreviewNode, x: Float, y: Float, w: Float, h: Float, cx: Float, cy: Float, cw: Float, ch: Float): LaidOut {
            val vertical = n.vertical
            val mainAvail = if (vertical) ch else cw
            val crossAvail = if (vertical) cw else ch
            val kids = n.children
            val sizes = ArrayList<Pair<Float, Float>>()   // border-box (w,h) per child
            var used = n.spacing * max(0, kids.size - 1)
            var totalWeight = 0f
            for (c in kids) {
                val weighted = c.weight > 0f
                val mainSpec = if (vertical) c.height else c.width
                val (rw, rh) = resolve(
                    c, max(0f, (if (vertical) crossAvail else mainAvail) - c.margin.horizontal), max(0f, (if (vertical) mainAvail else crossAvail) - c.margin.vertical),
                    wrapMatchW = !vertical && weighted, wrapMatchH = vertical && weighted,
                )
                var cwid = rw
                var chei = rh
                if (weighted && mainSpec.kind != SizeKind.WRAP) { if (vertical) chei = 0f else cwid = 0f }
                sizes += cwid to chei
                used += if (vertical) chei + c.margin.vertical else cwid + c.margin.horizontal
                if (weighted) totalWeight += c.weight
            }
            val extra = max(0f, mainAvail - used)
            if (totalWeight > 0f) {
                for ((i, c) in kids.withIndex()) if (c.weight > 0f) {
                    val share = extra * c.weight / totalWeight
                    val (sw, sh) = sizes[i]
                    sizes[i] = if (vertical) sw to (sh + share) else (sw + share) to sh
                }
                used += extra
            }
            var cursor = if (totalWeight > 0f) 0f else when {
                vertical && Grav.has(n.gravity, Grav.CENTER_V) -> extra / 2f
                vertical && Grav.has(n.gravity, Grav.BOTTOM) -> extra
                !vertical && Grav.has(n.gravity, Grav.CENTER_H) -> extra / 2f
                !vertical && Grav.has(n.gravity, Grav.END) -> extra
                else -> 0f
            }
            val laid = ArrayList<LaidOut>()
            var clipped = false
            for ((i, c) in kids.withIndex()) {
                var (sw, sh) = sizes[i]
                val g = if (c.layoutGravity != 0) c.layoutGravity else n.gravity
                if (vertical) {
                    if (c.width.kind == SizeKind.MATCH) sw = max(0f, cw - c.margin.horizontal)
                    val ox = crossOffset(g, cw, sw + c.margin.horizontal, Grav.START, Grav.END, Grav.CENTER_H)
                    laid += place(c, cx + ox + c.margin.left, cy + cursor + c.margin.top, sw, sh)
                    cursor += sh + c.margin.vertical + n.spacing
                } else {
                    if (c.height.kind == SizeKind.MATCH) sh = max(0f, ch - c.margin.vertical)
                    val oy = crossOffset(g, ch, sh + c.margin.vertical, Grav.TOP, Grav.BOTTOM, Grav.CENTER_V)
                    laid += place(c, cx + cursor + c.margin.left, cy + oy + c.margin.top, sw, sh)
                    cursor += sw + c.margin.horizontal + n.spacing
                }
            }
            if (cursor - n.spacing > mainAvail + 0.5f) clipped = true
            return LaidOut(n, x, y, w, h, laid, clipped)
        }

        private fun placeFrame(n: PreviewNode, x: Float, y: Float, w: Float, h: Float, cx: Float, cy: Float, cw: Float, ch: Float): LaidOut {
            val scrollV = n.type == NodeType.SCROLL && n.vertical
            val scrollH = n.type == NodeType.SCROLL && !n.vertical
            val laid = ArrayList<LaidOut>()
            var clipped = false
            for (c in n.children) {
                val offerH = if (scrollV) BIG else max(0f, ch - c.margin.vertical)
                val offerW = if (scrollH) BIG else max(0f, cw - c.margin.horizontal)
                val (sw, sh) = resolve(c, offerW, offerH)
                val g = if (c.layoutGravity != 0) c.layoutGravity else n.gravity
                val ox = crossOffset(g, cw, sw + c.margin.horizontal, Grav.START, Grav.END, Grav.CENTER_H)
                val oy = crossOffset(g, ch, sh + c.margin.vertical, Grav.TOP, Grav.BOTTOM, Grav.CENTER_V)
                if (sh + c.margin.vertical > ch + 0.5f || sw + c.margin.horizontal > cw + 0.5f) clipped = true
                laid += place(c, cx + ox + c.margin.left, cy + oy + c.margin.top, sw, sh)
            }
            return LaidOut(n, x, y, w, h, laid, clipped)
        }

        private fun placeConstraint(n: PreviewNode, x: Float, y: Float, w: Float, h: Float, cx: Float, cy: Float, cw: Float, ch: Float): LaidOut {
            val byId = HashMap<String, LaidOut>()
            val laid = ArrayList<LaidOut>()
            // Children that depend on a sibling are placed after the sibling.
            val pending = ArrayList(n.children)
            var guard = 0
            while (pending.isNotEmpty() && guard++ < n.children.size * 2 + 2) {
                val c = pending.removeAt(0)
                val a = c.anchors
                val deps = listOfNotNull(a?.startToEnd, a?.endToStart, a?.topToBottom, a?.bottomToTop, a?.startToStart, a?.endToEnd, a?.topToTop, a?.bottomToBottom).filter { it != "parent" }
                if (deps.any { it !in byId } && deps.any { d -> pending.any { it.id == d } }) { pending.add(c); continue }
                val l = placeConstrained(c, a, byId, cx, cy, cw, ch)
                laid += l
                c.id?.let { byId[it] = l }
            }
            for (c in pending) laid += placeConstrained(c, c.anchors, byId, cx, cy, cw, ch)
            return LaidOut(n, x, y, w, h, laid)
        }

        private fun placeConstrained(c: PreviewNode, a: Anchors?, byId: Map<String, LaidOut>, cx: Float, cy: Float, cw: Float, ch: Float): LaidOut {
            // 0dp (EXACT 0) with both sides anchored means "fill the space between the anchors".
            fun left(ref: String?, toEnd: Boolean): Float? = when {
                ref == null -> null
                ref == "parent" -> if (toEnd) cw else 0f
                else -> byId[ref]?.let { if (toEnd) it.x + it.w - cx else it.x - cx }
            }
            fun top(ref: String?, toBottom: Boolean): Float? = when {
                ref == null -> null
                ref == "parent" -> if (toBottom) ch else 0f
                else -> byId[ref]?.let { if (toBottom) it.y + it.h - cy else it.y - cy }
            }
            val sL = left(a?.startToStart, false) ?: left(a?.startToEnd, true)
            val eR = left(a?.endToEnd, true) ?: left(a?.endToStart, false)
            val tT = top(a?.topToTop, false) ?: top(a?.topToBottom, true)
            val bB = top(a?.bottomToBottom, true) ?: top(a?.bottomToTop, false)
            val fillW = c.width.kind == SizeKind.EXACT && c.width.dp == 0f && sL != null && eR != null
            val fillH = c.height.kind == SizeKind.EXACT && c.height.dp == 0f && tT != null && bB != null
            val availW = if (fillW) max(0f, eR!! - sL!! - c.margin.horizontal) else max(0f, cw - c.margin.horizontal)
            val availH = if (fillH) max(0f, bB!! - tT!! - c.margin.vertical) else max(0f, ch - c.margin.vertical)
            var (sw, sh) = resolve(c, availW, availH)
            if (fillW) sw = availW
            if (fillH) sh = availH
            val hb = a?.hBias ?: 0.5f
            val vb = a?.vBias ?: 0.5f
            val px = when {
                sL != null && eR != null -> sL + c.margin.left + (eR - sL - c.margin.horizontal - sw) * hb
                eR != null -> eR - c.margin.right - sw
                sL != null -> sL + c.margin.left
                else -> c.margin.left
            }
            val py = when {
                tT != null && bB != null -> tT + c.margin.top + (bB - tT - c.margin.vertical - sh) * vb
                bB != null -> bB - c.margin.bottom - sh
                tT != null -> tT + c.margin.top
                else -> c.margin.top
            }
            return place(c, cx + px, cy + py, sw, sh)
        }
    }
}
