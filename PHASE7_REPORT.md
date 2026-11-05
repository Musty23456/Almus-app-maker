# Phase 7 report: background build monitoring

## Read this first

**Nothing in Phase 7 has been compiled or executed.** The environment this was written in has a JDK 21 but no Kotlin compiler,
no Gradle, no Android SDK and no network (same situation as Phase 6, and Phase 6 itself was never compiled either). So:

- the new and changed Kotlin code has **not** been compiled;
- the 96 new unit tests have **not** been run: there is **no pass/fail result**, and "verified" must not be read into this report;
- WorkManager, notifications and the permission dialog have never run on a device.

Phase 8 should not start until the checks under "Required next step" are green.

## What was checked, and how
| Check | Result |
|---|---|
| All 196 Kotlin files: brackets, parentheses, strings and templates (own lexer) | no errors |
| Every `com.almus.appcreatorpro.*` import resolves to a declaration in the project (`R` and `BuildConfig` are generated) | all resolve |
| Manual line-by-line review of the new code against the real declarations (`BuildStore`, `GitHubApi` fake, `BuildService`, `FakeClock`, `MainDispatcherRule`...) and walk-through of each test's expected values | done; several test mistakes found and fixed this way |
| Compile, unit tests, Gradle, lint, R8 | **not possible here** |

## Required next step
Push to your own repository and let `.github/workflows/android-build.yml` run (`./gradlew testDebugUnitTest assembleDebug`).
Expect compile errors (Phase 6 + 7 are both uncompiled). Likeliest places: Compose imports in `BuildDashboardScreen`/`SettingsScreen`,
`fun interface` with a `suspend` member (`BuildRefresher`), `by`-delegation in one test, type inference in `AppContainer`/`MonitorRig`.

## What was built
- `CloudBuildOrchestrator.syncOnce(buildId, maxAgeMs)`: ONE status check (locate run, get run, finish on a final state), returned as a `SyncOutcome`.
  The existing follow/poll code is untouched.
- `BuildSyncEngine`: a pass over unfinished builds (modes BACKGROUND, RECOVER_STOPPED, MANUAL) with request economy rules.
- `BuildMonitor`: watches the store (notifications + WorkManager schedule), recovery at start, refresh, and the decision after each WorkManager pass.
- WorkManager: `BuildSyncWorker`, `WorkManagerBuildSyncScheduler` (15-min watchdog + self-continuing chain on two alternating unique names,
  network-connected constraint, exponential backoff).
- Notifications: five stages, deduplicated across restarts, two channels, tap opens the build (extra validated, activity is exported).
- Dashboard + view model + pure model; notification switch and permission flow in Settings and the dashboard; `BuildTracker` now implements `BuildFollower`.
- Version 0.7.0 (code 8); dependency `androidx.work:work-runtime-ktx:2.9.1`; permission `POST_NOTIFICATIONS`.

## Design decisions worth knowing
1. **One notification path.** Every change (in-app tracker, background sync, app start) goes through `BuildNotificationCoordinator`, which stores the last stage per build.
2. **No double polling.** Builds the in-app tracker follows live are skipped by the background engine (same process). A short gap rule covers the rest.
3. **Stopped builds** (timeout/connection) are only rechecked by the slow watchdog (every >= 10 min per build), up to 8 h after the build began.
4. **Notifications default to on**, but Android 13+ still needs the permission; refusing turns the switch off (no nagging).
5. Home's "Build history > See all" now opens the dashboard; the old history screen is reachable through "Full history" (nothing removed).

## Known risks / limitations (honest list)
1. **Never compiled or run** (above). Highest risk.
2. **Timing is not guaranteed.** WorkManager follows Doze/App Standby/battery rules; with the app closed, a result notification can arrive minutes late.
   Some manufacturers' aggressive battery managers can delay or kill background work; the app does not ask for exemptions.
3. Two chain slots may briefly coexist (an app start while a chain job is pending); they merge on the next pass and the engine's lock and gap rule prevent duplicate requests.
4. The engine and the in-app tracker can in theory write the same record at the same moment (read-modify-write, as before Phase 7); the engine re-reads before saving and skips records that became final.
5. Builds with `pollingStopped` because of an expired sign-in are rechecked by the watchdog; the call fails locally (no token) so it costs no network, but no "sign-in expired" notification is sent.
6. Notification text uses the record's detail; for failed builds this is the failing step name, not the log.
7. No instrumented/Robolectric tests: WorkManager scheduling, the notification drawer and the permission dialog are untested by code.
8. The merged manifest will contain permissions added by WorkManager's library (e.g. for rescheduling after reboot); the source manifest adds only `POST_NOTIFICATIONS`.

## Device test plan (after the first green CI build)
1. Start a build, press Home immediately: within a few minutes a "Build started"/"in progress" notification appears; the progress one shows elapsed time.
2. Swipe the app away while the build runs; wait for the end: a result notification arrives (maybe late); tapping it opens that build.
3. Open the app again after the build ended while it was closed: dashboard shows the final state, artifact "APK ready", no duplicate notification.
4. Airplane mode during a build, then back on: status catches up; one result notification.
5. Two builds at once (two projects): two separate notifications and dashboard cards.
6. Settings: switch notifications off: shown ones disappear, none appear. On Android 13+: fresh install, allow/deny the dialog; block in system settings and check the message + button.
7. Cancel from the dashboard: "Cancelling", then "Cancelled" notification. Refresh button on a build whose checking had stopped.
8. Reboot the phone during a build: monitoring resumes (WorkManager reschedules).
9. `adb shell dumpsys jobscheduler | grep almus` to see the watchdog/chain while a build is unfinished, and none when all builds are final.

## Files
Added: `service/monitor/*` (12 files), `service/build/{BuildFollower,SyncOutcome}.kt`, `ui/dashboard/*`, `res/drawable/ic_stat_build.xml`,
tests `monitor/*` (6 files) and `ui/SettingsNotificationsTest.kt`.
Changed: `CloudBuildOrchestrator` (syncOnce), `BuildTracker` (implements BuildFollower), `AppContainer`, `AlmusApplication`, `MainActivity`,
navigation (`AlmusApp`, `Routes`), `SettingsViewModel/Screen`, `AppViewModelProvider`, `Dialogs.kt` (settings helper), `AndroidManifest.xml`,
`app/build.gradle.kts`, `libs.versions.toml`, test fake `GitHubTestSupport` (per-run answers), `README.md`.
Unchanged: all workflows, signing, artifacts, import/export.
