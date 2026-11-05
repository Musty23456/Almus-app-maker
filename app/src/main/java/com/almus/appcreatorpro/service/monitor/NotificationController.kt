package com.almus.appcreatorpro.service.monitor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the notification switch and the dashboard banner show. */
data class NotificationUi(val enabled: Boolean, val access: NotificationAccessStatus) {
    /** Notifications will really be shown. */
    val working: Boolean get() = enabled && access == NotificationAccessStatus.ENABLED

    /** The user wants them but Android 13+ needs the permission first: the app may ask. */
    val needsPermission: Boolean get() = enabled && access == NotificationAccessStatus.PERMISSION_NEEDED

    /** The user wants them but Android blocks them: only the system settings can change that. */
    val blockedBySystem: Boolean get() = enabled && access == NotificationAccessStatus.BLOCKED_BY_SYSTEM
}

/** Shared by the settings screen and the dashboard: the switch, the permission request and what Android allows. */
class NotificationController(
    private val settings: MonitorSettingsStore,
    private val access: NotificationAccess,
) {
    private val _ui = MutableStateFlow(read())
    val ui: StateFlow<NotificationUi> = _ui.asStateFlow()

    /** Re-reads the Android state (call when the screen comes back from the system settings). */
    fun refresh() {
        _ui.value = read()
    }

    /** Returns true when the screen must now show the system permission dialog. */
    fun setEnabled(enabled: Boolean): Boolean {
        settings.setNotificationsEnabled(enabled)
        refresh()
        return enabled && _ui.value.access == NotificationAccessStatus.PERMISSION_NEEDED
    }

    /** The answer to the system permission dialog. A refusal turns the switch off again instead of nagging. */
    fun onPermissionResult(granted: Boolean) {
        if (!granted) settings.setNotificationsEnabled(false)
        refresh()
    }

    private fun read() = NotificationUi(settings.notificationsEnabled.value, access.status())
}
