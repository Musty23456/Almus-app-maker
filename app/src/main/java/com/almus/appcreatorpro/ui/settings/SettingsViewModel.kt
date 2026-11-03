package com.almus.appcreatorpro.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.almus.appcreatorpro.domain.model.ThemeMode
import com.almus.appcreatorpro.domain.repository.SettingsRepository
import com.almus.appcreatorpro.service.monitor.NotificationController
import com.almus.appcreatorpro.service.monitor.NotificationUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    /** Phase 7: the notification switch. Null keeps the older constructor (and its tests) working. */
    private val notificationController: NotificationController? = null,
) : ViewModel() {

    /** Whether build notifications are on and whether Android lets them through (null = not available). */
    val notifications: StateFlow<NotificationUi?> =
        notificationController?.ui ?: MutableStateFlow<NotificationUi?>(null)

    fun refreshNotificationState() {
        notificationController?.refresh()
    }

    /** Returns true when the screen must now show the system permission dialog. */
    fun setNotificationsEnabled(enabled: Boolean): Boolean = notificationController?.setEnabled(enabled) ?: false

    fun onNotificationPermissionResult(granted: Boolean) {
        notificationController?.onPermissionResult(granted)
        if (!granted) _message.value = "Notifications stay off. Allow them in the Android settings to get build alerts."
    }

    val themeMode: StateFlow<ThemeMode> = settingsRepository.observeThemeMode()
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    fun showWelcomeAgainNextLaunch() {
        settingsRepository.setWelcomeCompleted(false)
        _message.value = "The welcome screen will show again the next time the app starts."
    }

    fun consumeMessage() {
        _message.value = null
    }
}
