package com.almus.appcreatorpro

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.almus.appcreatorpro.domain.model.ThemeMode
import com.almus.appcreatorpro.service.monitor.DeepLinks
import com.almus.appcreatorpro.ui.navigation.AlmusApp
import com.almus.appcreatorpro.ui.theme.AlmusTheme

class MainActivity : ComponentActivity() {

    /** Phase 7: the build a notification asked to open. Consumed by the navigation once it is ready. */
    private val pendingBuildId = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Only a fresh start reads the intent: a rotation must not open the build a second time.
        if (savedInstanceState == null) pendingBuildId.value = buildIdFrom(intent)

        val settings = (application as AlmusApplication).container.settingsRepository
        val themeFlow = settings.observeThemeMode()

        setContent {
            val themeMode by themeFlow.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            AlmusTheme(themeMode = themeMode) {
                AlmusApp(
                    openBuildId = pendingBuildId.value,
                    onOpenBuildHandled = { pendingBuildId.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingBuildId.value = buildIdFrom(intent)
    }

    /** The activity is exported, so the extra is only used when it looks like a build id the app itself created. */
    private fun buildIdFrom(intent: Intent?): String? =
        DeepLinks.sanitizeBuildId(intent?.getStringExtra(DeepLinks.EXTRA_BUILD_ID))
}
