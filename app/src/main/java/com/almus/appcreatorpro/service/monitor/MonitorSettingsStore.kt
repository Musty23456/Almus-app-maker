package com.almus.appcreatorpro.service.monitor

import com.almus.appcreatorpro.data.storage.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The user's choices for background monitoring. Notifications are on by default; Android may still block them. */
class MonitorSettingsStore(private val store: KeyValueStore) {
    private val _notificationsEnabled = MutableStateFlow(store.getString(KEY_NOTIFICATIONS) != "false")

    val notificationsEnabled: StateFlow<Boolean> = _notificationsEnabled.asStateFlow()

    fun setNotificationsEnabled(enabled: Boolean) {
        store.putString(KEY_NOTIFICATIONS, enabled.toString())
        _notificationsEnabled.value = enabled
    }

    private companion object {
        const val KEY_NOTIFICATIONS = "notifications_enabled"
    }
}
