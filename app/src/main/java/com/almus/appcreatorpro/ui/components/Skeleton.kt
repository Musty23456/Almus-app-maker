package com.almus.appcreatorpro.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.almus.appcreatorpro.ui.theme.Motion
import com.almus.appcreatorpro.ui.theme.Spacing
import com.almus.appcreatorpro.ui.theme.rememberReducedMotion

/**
 * One shared pulse (a single float) for every skeleton block on the screen. It only changes layer alpha, which is applied
 * on the GPU without re-measuring or recomposing anything. With animations turned off in the system it stays still.
 */
@Composable
fun rememberSkeletonAlpha(): State<Float> {
    val reduced = rememberReducedMotion()
    if (reduced) return remember { mutableFloatStateOf(0.7f) }
    val transition = rememberInfiniteTransition(label = "skeleton")
    return transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(durationMillis = Motion.Slow * 3, delayMillis = 0), RepeatMode.Reverse),
        label = "skeletonAlpha",
    )
}

@Composable
fun SkeletonBlock(
    modifier: Modifier = Modifier,
    alpha: State<Float> = rememberSkeletonAlpha(),
    shape: Shape = MaterialTheme.shapes.small,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .graphicsLayer { this.alpha = alpha.value }
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    )
}

/** A card-shaped placeholder: a square, a title line and a shorter detail line. */
@Composable
fun SkeletonCard(modifier: Modifier = Modifier, alpha: State<Float> = rememberSkeletonAlpha()) {
    AlmusCard(modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(Spacing.lg)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            SkeletonBlock(modifier = Modifier.size(40.dp), alpha = alpha, shape = MaterialTheme.shapes.medium)
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                SkeletonBlock(modifier = Modifier.width(160.dp).height(14.dp), alpha = alpha)
                SkeletonBlock(modifier = Modifier.width(100.dp).height(12.dp), alpha = alpha)
            }
        }
    }
}

/** Placeholder list shown while a screen loads. Announces "Loading" once to screen readers. */
@Composable
fun SkeletonList(
    modifier: Modifier = Modifier,
    count: Int = 4,
    spacing: Dp = Spacing.md,
    label: String = "Loading",
) {
    val alpha = rememberSkeletonAlpha()
    Column(
        modifier = modifier.semantics {
            contentDescription = label
            liveRegion = LiveRegionMode.Polite
        },
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        repeat(count) { SkeletonCard(alpha = alpha) }
    }
}
