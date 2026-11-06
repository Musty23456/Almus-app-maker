# Fix: app crashes (hardening of start-up and background work)

No crash log was available, so the exact line could not be identified. The code was reviewed for everything that runs
at start-up or in the background without protection. Found: every app-wide coroutine scope (build tracker, build
monitor, APK downloads, AI hub) had NO CoroutineExceptionHandler, and `resumeUnfinished()` / the monitor's collectors
had no try/catch. Any exception there (damaged saved build record, WorkManager or notification problem) closes the app,
and because the same data is read at every start, the app then crashes on every launch.

Changed:
- di/AppContainer.kt: shared CoroutineExceptionHandler on all four app scopes.
- AlmusApplication.kt: each start-up step isolated with runCatching.
- service/monitor/BuildMonitor.kt: collectors and applyPlan protected.
- service/build/BuildTracker.kt: resumeUnfinished protected.

NOT compiled or run here (no Gradle/network). If it still crashes, send the logcat/stack trace.
