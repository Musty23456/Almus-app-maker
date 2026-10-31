package com.almus.appcreatorpro.data.repository

import android.content.SharedPreferences
import com.almus.appcreatorpro.domain.model.ThemeMode
import com.almus.appcreatorpro.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Stores non-sensitive UI preferences. Never put tokens or passwords in here. */
class SharedPreferencesSettingsRepository(
    private val prefs: SharedPreferences,
) : SettingsRepository {

    private val themeMode = MutableStateFlow(readTheme())

    override fun observeThemeMode(): Flow<ThemeMode> = themeMode

    override suspend fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
        themeMode.value = mode
    }

    override fun isWelcomeCompleted(): Boolean = prefs.getBoolean(KEY_WELCOME_DONE, false)

    override fun setWelcomeCompleted(completed: Boolean) {
        prefs.edit().putBoolean(KEY_WELCOME_DONE, completed).apply()
    }

    private fun readTheme(): ThemeMode =
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name) }
            .getOrDefault(ThemeMode.SYSTEM)

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_WELCOME_DONE = "welcome_completed"
    }
}

/** Pure in-memory settings, used by unit tests. */
class InMemorySettingsRepository(
    initialTheme: ThemeMode = ThemeMode.SYSTEM,
    welcomeCompleted: Boolean = false,
) : SettingsRepository {

    private val themeMode = MutableStateFlow(initialTheme)
    private var welcomeDone = welcomeCompleted

    override fun observeThemeMode(): Flow<ThemeMode> = themeMode

    override suspend fun setThemeMode(mode: ThemeMode) {
        themeMode.value = mode
    }

    override fun isWelcomeCompleted(): Boolean = welcomeDone

    override fun setWelcomeCompleted(completed: Boolean) {
        welcomeDone = completed
    }
}
