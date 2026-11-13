package com.almus.appcreatorpro.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Short durations only: the UI should feel responsive, never slow. */
object Motion {
    const val Fast = 120
    const val Standard = 200
    const val Slow = 320

    /** 0 when the user turned animations off, otherwise [base]. Pure so it can be unit tested. */
    fun duration(base: Int, reducedMotion: Boolean): Int = if (reducedMotion) 0 else base

    fun <T> spec(base: Int, reducedMotion: Boolean): FiniteAnimationSpec<T> = tween(durationMillis = duration(base, reducedMotion))
}

/**
 * True when the user set "Animator duration scale" to off (or turned on "Remove animations"). Read once per composition
 * scope; a change takes effect the next time the screen is shown, which is enough for a system-wide setting.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (e: Exception) {
            false
        }
    }
}
