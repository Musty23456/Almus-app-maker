package com.almus.appcreatorpro.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/** Width classes that follow the Material window-size breakpoints (600dp and 840dp). */
enum class LayoutClass { Compact, Medium, Expanded }

fun layoutClassFor(widthDp: Int): LayoutClass = when {
    widthDp < 600 -> LayoutClass.Compact
    widthDp < 840 -> LayoutClass.Medium
    else -> LayoutClass.Expanded
}

/** How many equal columns a grid of cards should use for [layout]. */
fun gridColumnsFor(layout: LayoutClass): Int = when (layout) {
    LayoutClass.Compact -> 2
    LayoutClass.Medium -> 3
    LayoutClass.Expanded -> 3
}

/** How many stat tiles fit on one row for [layout]. */
fun statColumnsFor(layout: LayoutClass): Int = when (layout) {
    LayoutClass.Compact -> 2
    LayoutClass.Medium -> 4
    LayoutClass.Expanded -> 4
}

@Composable
fun rememberLayoutClass(): LayoutClass = layoutClassFor(LocalConfiguration.current.screenWidthDp)
