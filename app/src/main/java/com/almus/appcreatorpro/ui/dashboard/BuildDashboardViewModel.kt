package com.almus.appcreatorpro.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.almus.appcreatorpro.domain.error.toUserMessage
import com.almus.appcreatorpro.domain.repository.BuildStore
import com.almus.appcreatorpro.service.artifacts.ApkStore
import com.almus.appcreatorpro.service.build.BuildFollower
import com.almus.appcreatorpro.service.build.BuildService
import com.almus.appcreatorpro.service.monitor.BuildRefresher
import com.almus.appcreatorpro.service.monitor.NotificationController
import com.almus.appcreatorpro.service.monitor.NotificationUi
import com.almus.appcreatorpro.ui.UiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The build dashboard: active, finished and failed builds with duration and artifact availability, plus the user
 * controls (refresh, cancel, notification switch). Builds are read from the same store the tracker and the
 * background sync write to, so the screen is always up to date, also after the app was restarted.
 */
class BuildDashboardViewModel(
    private val builds: BuildStore,
    private val follower: BuildFollower,
    private val buildService: BuildService,
    private val refresher: BuildRefresher? = null,
    private val notificationController: NotificationController? = null,
    private val apkStore: ApkStore? = null,
    private val now: () -> Long = System::currentTimeMillis,
    /** How often the "running for" times are recomputed. 0 = never (tests). */
    tickMillis: Long = 15_000L,
) : ViewModel() {

    private val _filter = MutableStateFlow(DashboardFilter.ALL)
    val filter: StateFlow<DashboardFilter> = _filter.asStateFlow()

    private val tick = MutableStateFlow(0L)

    val state: StateFlow<UiState<DashboardState>> =
        combine(builds.observeBuilds(), follower.activeBuildIds, _filter, tick) { records, followed, filter, _ ->
            val result: UiState<DashboardState> =
                UiState.Success(DashboardBuilder.build(records, now(), followed, savedOnPhone(records), filter))
            result
        }
            .catch { emit(UiState.Error(it.toUserMessage())) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, UiState.Loading)

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _cancelling = MutableStateFlow<Set<String>>(emptySet())

    /** Ids of builds whose cancel request is being sent right now. */
    val cancelling: StateFlow<Set<String>> = _cancelling.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** The notification switch state (null when notifications are not available, for example in tests). */
    val notifications: StateFlow<NotificationUi?> =
        notificationController?.ui ?: MutableStateFlow<NotificationUi?>(null)

    init {
        if (tickMillis > 0L) {
            viewModelScope.launch {
                while (true) {
                    delay(tickMillis)
                    tick.value = tick.value + 1
                }
            }
        }
    }

    fun setFilter(filter: DashboardFilter) {
        _filter.value = filter
    }

    /** Checks GitHub for every unfinished build once (and resumes live following). Ignored while already running. */
    fun refresh() {
        if (_refreshing.value) return
        val service = refresher
        if (service == null) {
            _message.value = "Refreshing is not available."
            return
        }
        _refreshing.value = true
        viewModelScope.launch {
            try {
                _message.value = service.refreshNow().userMessage()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "The status could not be refreshed. Try again."
            } finally {
                _refreshing.value = false
            }
        }
    }

    /** Asks GitHub to cancel [buildId]. Only builds that can still be cancelled are sent. */
    fun cancel(buildId: String) {
        if (buildId in _cancelling.value) return
        _cancelling.value = _cancelling.value + buildId
        viewModelScope.launch {
            try {
                val record = builds.getBuild(buildId)
                if (record == null || record.status.isTerminal) {
                    _message.value = "This build has already finished."
                } else {
                    _message.value = buildService.cancelBuild(record).fold(
                        onSuccess = { it },
                        onFailure = { it.message ?: "The build could not be cancelled." },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "The build could not be cancelled."
            } finally {
                _cancelling.value = _cancelling.value - buildId
            }
        }
    }

    // ------------------------------------------------------------------ notifications

    fun refreshNotificationState() {
        notificationController?.refresh()
    }

    /** Returns true when the screen must show the system permission dialog. */
    fun enableNotifications(): Boolean = notificationController?.setEnabled(true) ?: false

    fun onNotificationPermissionResult(granted: Boolean) {
        notificationController?.onPermissionResult(granted)
        if (!granted) _message.value = "Notifications stay off. You can turn them on in Settings."
    }

    fun clearMessage() {
        _message.value = null
    }

    private fun savedOnPhone(records: List<com.almus.appcreatorpro.domain.model.BuildRecord>): Set<String> {
        val store = apkStore ?: return emptySet()
        return records
            .filter { !it.isSample && it.status.isTerminal && it.id.matches(SAFE_ID) }
            .filter { store.list(it.id).isNotEmpty() }
            .map { it.id }
            .toSet()
    }

    private companion object {
        val SAFE_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}
