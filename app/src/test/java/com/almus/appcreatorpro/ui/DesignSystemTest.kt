package com.almus.appcreatorpro.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.almus.appcreatorpro.domain.model.BuildRecord
import com.almus.appcreatorpro.domain.model.BuildStatus
import com.almus.appcreatorpro.ui.home.DashboardStats
import com.almus.appcreatorpro.ui.home.greetingForHour
import com.almus.appcreatorpro.ui.navigation.Routes
import com.almus.appcreatorpro.ui.navigation.bottomBarRoutes
import com.almus.appcreatorpro.ui.theme.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** UI redesign: palette contrast (WCAG AA), responsive breakpoints, motion switch, dashboard numbers, navigation set. */
class DesignSystemTest {

    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }

    private fun assertText(name: String, fg: Color, bg: Color, min: Float = 4.5f) {
        val c = contrast(fg, bg)
        assertTrue("$name contrast was $c, needs $min", c >= min)
    }

    @Test fun lightThemeTextIsReadable() {
        val s = LightColors
        for (bg in listOf(s.background, s.surface, s.surfaceContainer, s.surfaceContainerHigh, s.surfaceContainerHighest)) {
            assertText("onSurface", s.onSurface, bg, 7f)
            assertText("onSurfaceVariant", s.onSurfaceVariant, bg)
        }
        assertText("onPrimary", s.onPrimary, s.primary)
        assertText("onPrimaryContainer", s.onPrimaryContainer, s.primaryContainer)
        assertText("onSecondary", s.onSecondary, s.secondary)
        assertText("onSecondaryContainer", s.onSecondaryContainer, s.secondaryContainer)
        assertText("onTertiary", s.onTertiary, s.tertiary)
        assertText("onTertiaryContainer", s.onTertiaryContainer, s.tertiaryContainer)
        assertText("onError", s.onError, s.error)
        assertText("onErrorContainer", s.onErrorContainer, s.errorContainer)
        assertText("outline", s.outline, s.surface, 3f)
        assertText("success text", SuccessGreen, LightCard)
        assertText("onSuccessContainer", LightOnSuccessContainer, LightSuccessContainer)
        assertText("onWarningContainer", LightOnWarningContainer, LightWarningContainer)
    }

    @Test fun darkThemeTextIsReadable() {
        val s = DarkColors
        for (bg in listOf(s.background, s.surface, s.surfaceContainer, s.surfaceContainerHigh, s.surfaceContainerHighest)) {
            assertText("onSurface", s.onSurface, bg, 7f)
            assertText("onSurfaceVariant", s.onSurfaceVariant, bg)
        }
        assertText("onPrimary", s.onPrimary, s.primary)
        assertText("primary on card", s.primary, DarkCard)
        assertText("onPrimaryContainer", s.onPrimaryContainer, s.primaryContainer)
        assertText("onSecondary", s.onSecondary, s.secondary)
        assertText("onSecondaryContainer", s.onSecondaryContainer, s.secondaryContainer)
        assertText("onTertiary", s.onTertiary, s.tertiary)
        assertText("onTertiaryContainer", s.onTertiaryContainer, s.tertiaryContainer)
        assertText("onError", s.onError, s.error)
        assertText("onErrorContainer", s.onErrorContainer, s.errorContainer)
        assertText("outline", s.outline, s.surface, 3f)
        assertText("success text", DarkSuccess, DarkCard)
        assertText("onSuccessContainer", DarkOnSuccessContainer, DarkSuccessContainer)
        assertText("onWarningContainer", DarkOnWarningContainer, DarkWarningContainer)
    }

    @Test fun brandGradientCarriesWhiteText() {
        assertText("white on BrandStart", Color.White, BrandStart)
        assertText("white on BrandEnd", Color.White, BrandEnd)
    }

    @Test fun layoutClassesFollowBreakpoints() {
        assertEquals(LayoutClass.Compact, layoutClassFor(360))
        assertEquals(LayoutClass.Compact, layoutClassFor(599))
        assertEquals(LayoutClass.Medium, layoutClassFor(600))
        assertEquals(LayoutClass.Medium, layoutClassFor(839))
        assertEquals(LayoutClass.Expanded, layoutClassFor(840))
        assertEquals(2, gridColumnsFor(LayoutClass.Compact))
        assertEquals(2, statColumnsFor(LayoutClass.Compact))
        assertEquals(4, statColumnsFor(LayoutClass.Expanded))
    }

    @Test fun reducedMotionRemovesAnimationTime() {
        assertEquals(0, Motion.duration(Motion.Standard, reducedMotion = true))
        assertEquals(Motion.Standard, Motion.duration(Motion.Standard, reducedMotion = false))
        assertTrue(Motion.Slow <= 400)
    }

    @Test fun spacingScaleIsOrdered() {
        val all = listOf(Spacing.xxs, Spacing.xs, Spacing.sm, Spacing.md, Spacing.lg, Spacing.xl, Spacing.xxl)
        assertEquals(all.sortedBy { it.value }, all)
        assertTrue(Dimens.MinTouchTarget.value >= 48f)
    }

    private fun build(id: String, status: BuildStatus, sample: Boolean) = BuildRecord(
        id = id, projectName = "App", status = status, startedAtMillis = 0L, durationSeconds = null, summary = "", isSample = sample,
    )

    @Test fun dashboardStatsIgnoreSampleBuilds() {
        val stats = DashboardStats.from(
            projectCount = 3,
            builds = listOf(
                build("a", BuildStatus.SUCCESS, sample = false),
                build("b", BuildStatus.SUCCESS, sample = false),
                build("c", BuildStatus.FAILED, sample = false),
                build("d", BuildStatus.RUNNING, sample = false),
                build("e", BuildStatus.FAILED, sample = true),
            ),
        )
        assertEquals(3, stats.projects)
        assertEquals(4, stats.realBuilds)
        assertEquals(2, stats.succeeded)
        assertEquals(1, stats.failed)
        assertEquals(1, stats.running)
        assertEquals(67, stats.successPercent)
    }

    @Test fun successRateIsUnknownUntilARealBuildFinishes() {
        assertNull(DashboardStats.from(0, emptyList()).successPercent)
        assertNull(DashboardStats.from(1, listOf(build("a", BuildStatus.SUCCESS, sample = true))).successPercent)
        assertNull(DashboardStats.from(1, listOf(build("a", BuildStatus.RUNNING, sample = false))).successPercent)
        assertEquals(100, DashboardStats.from(1, listOf(build("a", BuildStatus.SUCCESS, sample = false))).successPercent)
    }

    @Test fun greetingMatchesTimeOfDay() {
        assertEquals("Good morning", greetingForHour(5))
        assertEquals("Good morning", greetingForHour(11))
        assertEquals("Good afternoon", greetingForHour(12))
        assertEquals("Good evening", greetingForHour(17))
        assertEquals("Welcome back", greetingForHour(23))
        assertEquals("Welcome back", greetingForHour(2))
    }

    @Test fun bottomBarKeepsAllThreeMainDestinations() {
        assertEquals(listOf(Routes.HOME, Routes.PROJECTS, Routes.SETTINGS), bottomBarRoutes)
    }
}
