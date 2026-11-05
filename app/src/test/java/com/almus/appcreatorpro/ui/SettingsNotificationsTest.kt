package com.almus.appcreatorpro.ui

import com.almus.appcreatorpro.data.repository.InMemorySettingsRepository
import com.almus.appcreatorpro.data.storage.InMemoryKeyValueStore
import com.almus.appcreatorpro.service.monitor.MonitorSettingsStore
import com.almus.appcreatorpro.service.monitor.NotificationAccessStatus
import com.almus.appcreatorpro.service.monitor.NotificationController
import com.almus.appcreatorpro.testutil.MainDispatcherRule
import com.almus.appcreatorpro.ui.settings.SettingsViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsNotificationsTest {
    @get:Rule
    val main = MainDispatcherRule()

    private var access = NotificationAccessStatus.ENABLED
    private val controller = NotificationController(MonitorSettingsStore(InMemoryKeyValueStore())) { access }

    @Test
    fun the_older_constructor_still_works_and_has_no_notification_state() {
        val vm = SettingsViewModel(InMemorySettingsRepository())
        assertNull(vm.notifications.value)
        assertFalse(vm.setNotificationsEnabled(true))
    }

    @Test
    fun the_switch_turns_notifications_off_and_on() {
        val vm = SettingsViewModel(InMemorySettingsRepository(), controller)
        assertTrue(vm.notifications.value!!.working)
        vm.setNotificationsEnabled(false)
        assertFalse(vm.notifications.value!!.enabled)
        vm.setNotificationsEnabled(true)
        assertTrue(vm.notifications.value!!.working)
    }

    @Test
    fun a_refused_permission_is_explained_and_leaves_the_switch_off() {
        access = NotificationAccessStatus.PERMISSION_NEEDED
        val vm = SettingsViewModel(InMemorySettingsRepository(), controller)
        assertTrue(vm.setNotificationsEnabled(true))
        vm.onNotificationPermissionResult(false)
        assertFalse(vm.notifications.value!!.enabled)
        assertEquals(true, vm.message.value?.contains("Notifications stay off"))
    }
}
