package com.almus.appcreatorpro.monitor

import com.almus.appcreatorpro.data.repository.InMemoryBuildStore
import com.almus.appcreatorpro.data.storage.InMemoryKeyValueStore
import com.almus.appcreatorpro.domain.build.BuildConfiguration
import com.almus.appcreatorpro.domain.model.BuildRecord
import com.almus.appcreatorpro.domain.model.BuildStatus
import com.almus.appcreatorpro.domain.repository.BuildStore
import com.almus.appcreatorpro.service.build.BuildFollower
import com.almus.appcreatorpro.service.build.CloudBuildOrchestrator
import com.almus.appcreatorpro.service.monitor.BackgroundSyncPolicy
import com.almus.appcreatorpro.service.monitor.BuildMonitor
import com.almus.appcreatorpro.service.monitor.BuildNotification
import com.almus.appcreatorpro.service.monitor.BuildNotificationCoordinator
import com.almus.appcreatorpro.service.monitor.BuildSyncEngine
import com.almus.appcreatorpro.service.monitor.BuildSyncScheduler
import com.almus.appcreatorpro.service.monitor.BuildSyncStateStore
import com.almus.appcreatorpro.service.monitor.MonitorSettingsStore
import com.almus.appcreatorpro.service.monitor.NotificationAccessStatus
import com.almus.appcreatorpro.service.monitor.NotificationSink
import com.almus.appcreatorpro.testutil.FakeClock
import com.almus.appcreatorpro.testutil.FakeGitHubApi
import com.almus.appcreatorpro.testutil.NoFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeSink : NotificationSink {
    var access = NotificationAccessStatus.ENABLED
    val posted = mutableListOf<BuildNotification>()
    val cancelled = mutableListOf<String>()
    var cancelAllCalls = 0
    override fun status() = access
    override fun post(notification: BuildNotification) { posted += notification }
    override fun cancel(buildId: String) { cancelled += buildId }
    override fun cancelAll() { cancelAllCalls++ }
    fun kinds(buildId: String) = posted.filter { it.buildId == buildId }.map { it.kind.stage }
}

class FakeScheduler : BuildSyncScheduler {
    val events = mutableListOf<String>()
    var lastContinueDelay: Long? = null
    override fun ensureWatchdog() { events += "watchdog" }
    override fun ensureChain(delayMs: Long) { events += "chain:$delayMs" }
    override fun continueChain(afterSlot: Int, delayMs: Long) { events += "continue:$afterSlot:$delayMs"; lastContinueDelay = delayMs }
    override fun cancelWatchdog() { events += "cancelWatchdog" }
    override fun cancelAll() { events += "cancelAll" }
}

class FakeFollower(initial: Set<String> = emptySet()) : BuildFollower {
    private val state = MutableStateFlow(initial)
    val tracked = mutableListOf<String>()
    override val activeBuildIds: StateFlow<Set<String>> = state
    override fun track(buildId: String) { tracked += buildId }
    fun setFollowed(ids: Set<String>) { state.value = ids }
}

fun record(
    id: String,
    status: BuildStatus = BuildStatus.QUEUED,
    startedAt: Long = 1_000_000L,
    runId: Long? = 100L,
    stopped: Boolean = false,
    project: String = "Shop",
    configuration: BuildConfiguration? = null,
    finishedAt: Long? = null,
    detail: String? = null,
) = BuildRecord(
    id = id, projectName = project, status = status, startedAtMillis = startedAt, durationSeconds = null,
    summary = "musty/builds (main)", isSample = false, projectId = "p1", repoFullName = "musty/builds",
    branch = "main", projectDir = "almus-projects/shop", runId = runId, pollingStopped = stopped,
    configuration = configuration, finishedAtMillis = finishedAt, detail = detail,
)

/** Everything of the Phase 7 stack wired with fakes, on a virtual clock. */
class MonitorRig(
    val store: BuildStore = InMemoryBuildStore(),
    val api: FakeGitHubApi = FakeGitHubApi(),
    val clock: FakeClock = FakeClock(10_000_000L),
    val sink: FakeSink = FakeSink(),
    val scheduler: FakeScheduler = FakeScheduler(),
    val follower: FakeFollower = FakeFollower(),
    val policy: BackgroundSyncPolicy = BackgroundSyncPolicy(),
    stateKv: InMemoryKeyValueStore = InMemoryKeyValueStore(),
    settingsKv: InMemoryKeyValueStore = InMemoryKeyValueStore(),
) {
    val settings = MonitorSettingsStore(settingsKv)
    val state = BuildSyncStateStore(stateKv)
    val orchestrator = CloudBuildOrchestrator(
        api = api, files = NoFiles, builds = store, now = clock::now, sleep = clock::sleep,
    )
    val notifications = BuildNotificationCoordinator(settings, sink, state, clock::now)
    val engine = BuildSyncEngine(store, orchestrator, notifications, state, { follower.activeBuildIds.value }, policy, clock::now)
    val monitor = BuildMonitor(
        store = store, engine = engine, notifications = notifications, settings = settings, sink = sink,
        scheduler = scheduler, follower = follower, scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        policy = policy, now = clock::now,
    )
}

/** A scripted queue for FakeGitHubApi: values are WorkflowRun or GitHubException; the last one repeats. */
fun answers(vararg values: Any): ArrayDeque<Any> = ArrayDeque(values.toList())
