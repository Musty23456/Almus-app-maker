package com.almus.appcreatorpro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.almus.appcreatorpro.domain.model.ThemeMode
import com.almus.appcreatorpro.ui.navigation.AlmusApp
import com.almus.appcreatorpro.ui.theme.AlmusTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val settings = (application as AlmusApplication).container.settingsRepository
        val themeFlow = settings.observeThemeMode()

        setContent {
            val themeMode by themeFlow.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            AlmusTheme(themeMode = themeMode) {
                AlmusApp()
            }
        }
    }
}
