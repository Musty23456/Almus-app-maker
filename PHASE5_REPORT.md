# Phase 5 report: advanced project import and export

## Verification status (read this first)
**Nothing in Phase 5 has been compiled or run.** The authoring sandbox has no Kotlin compiler, no Gradle, no Android SDK
and no network, so `./gradlew testDebugUnitTest` and `assembleDebug` could not be executed. What was done instead:
- Static checks on all Kotlin files: bracket balance and resolution of every project import (no problems in new files).
- Line-by-line desk review of every new test against the production code (expected values traced by hand).
Treat the 95 new tests as **written but unverified**. Expect possible compile errors, most likely in Compose UI files
(`ui/transfer/*`, `ProjectsScreen.kt`) or small type-inference slips. Run `./gradlew testDebugUnitTest assembleDebug` (or push
to your CI, which runs both) and send me the first `e:` line of any failure.

Phase 6 was not started.

## New files
domain/transfer: TransferErrors, TransferModels, ZipEntryGuard, ProjectDetector, ProjectNaming, ProjectListFilter
domain/repository: ProjectTransferRepository, RecentProjectsTracker
data/transfer: ProjectStager, ProjectImportService, ProjectArchiver, BackupStore, CountingInputStream
data/storage: RecentProjectsStore
service/transfer: DocumentAccess, AndroidDocumentAccess (+SafTreeSource), GitHubRepoDownloader, GitHubSource
ui/transfer: ImportViewModel, ImportScreen, ProjectTransferViewModel, BackupsViewModel, BackupsScreen, TransferComponents
tests (app/src/test/.../transfer): TransferTestSupport, ZipEntryGuardTest (8), ProjectImportServiceTest (26),
ProjectDetectorAndNamingTest (11), ExportBackupRestoreTest (17), FileProjectStoreTransferTest (5),
GitHubRepoDownloaderTest (10), Phase5ViewModelsTest (18)
PHASE5_REPORT.md

## Modified files
- data/storage/FileProjectStore.kt: implements ProjectTransferRepository (importStaged, replaceContents with rollback,
  contentRoots, recovery of an interrupted replace); new `extra/` folder constant.
- domain/error/AppErrors.kt: one branch for TransferException.
- ui/projects/ProjectsViewModel.kt: optional `recents` param (default keeps old callers working), query, sort,
  visibleProjects, recentProjects; delete also forgets the recent entry. `state` is unchanged.
- ui/projects/ProjectsScreen.kt: Import button, overflow menu, search/sort bar, recent row, export/backup menu items, progress dialog.
- di/AppContainer.kt, ui/AppViewModelProvider.kt, ui/navigation/Routes.kt, ui/navigation/AlmusApp.kt: wiring, routes
  `import` and `backups/{projectId}`, marks a project as opened when the editor opens.
- app/build.gradle.kts: version 0.5.0 (code 6). README.md.
No manifest change, no new permissions, no new dependencies.

## Test plan covered by the new tests
ZIP import (detection, classification, wrapper folder, limits, zip slip, zip bomb, corrupt/not-zip, cancellation, cleanup),
folder import, GitHub import (ViewModel flow + downloader: redirect without token, errors, size limit, encoding),
export (contents, structure, edits, progress, failures, round trip), backup/restore (multiple, restore as new, replace with
safety copy, deletion survival, limit, damaged files, id traversal), recents/search/sort, and that the existing
`ProjectsViewModel(repo)` constructor and the editor-visible file list still behave as before.

## Not verified / needs a real device
Storage Access Framework pickers and the DocumentsContract folder walk, the system "save as" dialog, the Compose screens,
real GitHub downloads, and behavior with very large archives on a phone. Existing features (create/edit) were not
touched functionally, but they were not re-run either.

## Known limitations
- Non-text files are preserved in `extra/` and exported/backed up, but not editable and not uploaded by cloud builds.
- Detection is heuristic (file names and Gradle text); Flutter and other stacks are refused.
- Import runs in the foreground; if Android kills the app mid-import, nothing is saved and temp data is removed at next start.
- GitHub import takes one commit's files, not history.
