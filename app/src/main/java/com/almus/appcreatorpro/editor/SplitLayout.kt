package com.almus.appcreatorpro.editor

enum class SplitOrientation { SIDE_BY_SIDE, STACKED }

/**
 * Phase 11: which files are shown in the editor panes. A pure state class: the editor UI renders one or two panes from
 * it. The primary pane is the existing single editor. The secondary pane (split screen) shows another file, or the same
 * file at another scroll position; edits go to the same open file state, so both views of one file always agree.
 */
data class SplitLayout(
    val primary: String? = null,
    val secondary: String? = null,
    val orientation: SplitOrientation = SplitOrientation.SIDE_BY_SIDE,
    /** Share of the space for the primary pane, 0.2..0.8. */
    val ratio: Float = 0.5f,
    val focusSecondary: Boolean = false,
) {
    val isSplit: Boolean get() = secondary != null
    val focusedPath: String? get() = if (focusSecondary && secondary != null) secondary else primary

    /** Opens [path] in the focused pane. */
    fun open(path: String): SplitLayout =
        if (focusSecondary && secondary != null) copy(secondary = path) else copy(primary = path)

    /** Starts a split showing [path] in the second pane (default: the primary file again) and focuses it. */
    fun split(path: String? = null): SplitLayout {
        val first = primary ?: return this
        return copy(secondary = path ?: first, focusSecondary = true)
    }

    fun unsplit(): SplitLayout = copy(secondary = null, focusSecondary = false)

    fun swap(): SplitLayout {
        val s = secondary ?: return this
        return copy(primary = s, secondary = primary, focusSecondary = !focusSecondary)
    }

    fun focus(secondaryPane: Boolean): SplitLayout = copy(focusSecondary = secondaryPane && secondary != null)

    fun withRatio(value: Float): SplitLayout = copy(ratio = value.coerceIn(0.2f, 0.8f))

    fun toggleOrientation(): SplitLayout =
        copy(orientation = if (orientation == SplitOrientation.SIDE_BY_SIDE) SplitOrientation.STACKED else SplitOrientation.SIDE_BY_SIDE)

    /** A file was closed: it leaves the panes; a closed primary is replaced by the secondary. */
    fun onClosed(path: String, remaining: List<String>): SplitLayout {
        var next = this
        if (next.secondary == path) next = next.unsplit()
        if (next.primary == path) next = next.copy(primary = remaining.firstOrNull { it != next.secondary } ?: remaining.firstOrNull())
        return next
    }

    /** A file was renamed. */
    fun onRenamed(from: String, to: String): SplitLayout =
        copy(primary = if (primary == from) to else primary, secondary = if (secondary == from) to else secondary)

    /**
     * Split view needs room. Below about 600 dp wide it is a bad experience, so phones in portrait should not split
     * side by side; stacked is the option that works on narrow screens.
     */
    fun suggestedOrientation(widthDp: Int, heightDp: Int): SplitOrientation =
        if (widthDp >= 840 || (widthDp >= 600 && widthDp >= heightDp)) SplitOrientation.SIDE_BY_SIDE else SplitOrientation.STACKED

    companion object {
        /** Minimum width in dp where a minimap is worth its space. */
        const val MINIMAP_MIN_WIDTH_DP = 600
        fun showMinimap(widthDp: Int): Boolean = widthDp >= MINIMAP_MIN_WIDTH_DP
    }
}
