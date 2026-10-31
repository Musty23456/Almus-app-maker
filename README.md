# Almus App Creator Pro (Phase 4)

An Android app for creating projects, editing code and (later) building APKs on GitHub Actions, all from a phone.

Phase 2: project manager + code editor with on-device storage (unchanged).
Phase 3: real GitHub sign-in, repository selection and cloud builds on GitHub Actions (unchanged, see "Phase 3").
**Phase 4 completes the build result system: live build monitor, logs and compiler errors, APK download / share / save, retry and a smarter history** (see "Phase 4" below).

## Status: what is and is not verified

The sandbox this was written in could not reach Google Maven / Maven Central and had no Android SDK, so the
Android build has **never been run by the author**.

- Verified: all non-UI Kotlin (storage, editor engine, ViewModels, validators, templates) compiles with the Kotlin
  2.0.21 compiler and **464 unit tests pass** (197 from Phase 2, the rest Phase 3 and 4) on a plain JVM (using small stand-ins for the Android/AndroidX classes).
- **Not verified:** the Jetpack Compose screens, the Gradle/AGP setup and the GitHub Actions run. The first workflow
  run is the real test. See "If the build fails".

## Tech stack

Kotlin 2.0.21, Jetpack Compose (BOM 2024.12.01) + Material 3, AGP 8.7.3, Gradle 8.9 (installed by the workflow),
JDK 17, compileSdk/targetSdk 35, minSdk 26. Application ID `com.almus.appcreatorpro`. Phase 2 added no dependencies.

## Features

**Project manager** (Projects tab, or Dashboard > New project)
- Create a project: name, project type (Native Android Kotlin, Native Android Java, HTML/CSS/JavaScript), package name
  (Android types only), optional description. Starter files are generated for each type.
- List saved projects with type, creation date and last-updated time.
- Open, rename (menu on each card, or inside the editor) and delete (with confirmation).
- Damaged or incomplete project folders are skipped and reported in a banner instead of crashing the app.

**Code editor** (tap a project)
- Line numbers, syntax colors (Kotlin, Java, JavaScript, HTML, XML, CSS, JSON), matching-bracket highlight.
- Auto-indent on Enter (extra level after `{ ( [`), Tab / Untab (also for multi-line selections).
- Undo / redo, copy / cut / paste / select all, find and replace (match case, next/previous, replace one/all).
- Symbol row above the keyboard (`{ } ( ) [ ] ; : " ' < > / = ...`).
- Multiple open files as tabs; file explorer drawer (Menu icon) with folders; create, rename/move and delete files.
- Save indicator in the title bar ("All changes saved" / "Unsaved changes" / "Saving..."), a dot on modified tabs.
- Unsaved-change protection: confirmation when closing a modified tab or leaving the editor, and an automatic save
  whenever the app goes to the background.

**Not included:** compiling/building, GitHub login, folders as separate objects (folders exist while they contain files),
image/binary files, soft-wrapped lines, editor font-size setting.

## Storage

Projects are saved in the app's private storage (`Context.filesDir/projects/`), so **no storage permission is requested**.
They survive app restarts and are deleted by Android only if you uninstall the app or clear its data.

```
projects/<id>/project.properties    metadata (name, type, package, dates)
projects/<id>/files/...             source files
```

Saves are atomic (temporary file, then move) so a crash cannot leave a half-written file. Files must be UTF-8 text up to
512 KB. File names may use letters, digits, space and `- _ .`, with `/` for folders.

## Project structure

```
.github/workflows/android-build.yml   unchanged from Phase 1
app/src/main/java/com/almus/appcreatorpro/
  domain/        models, repository interfaces, validators, templates, use case   (pure Kotlin)
  data/storage/  FileProjectStore: the on-device ProjectRepository + SourceFileRepository
  data/...       in-memory repository (tests), sample build history, settings
  editor/        pure editor logic: undo, highlighter, brackets, indent, find, file tree
  service/       BuildService interface (still a prototype), storage info
  ui/editor/     EditorViewModel + editor screens;  ui/projects/, ui/home/, ...
app/src/test/...  197 unit tests
```

`SourceFileRepository` is the seam future build engines will use to read a project's files, whether they build in the
cloud (GitHub Actions) or offline. `BuildService` is unchanged.

## Build and run

- Locally: open in Android Studio (Ladybug or newer), or run `gradle testDebugUnitTest assembleDebug` with Gradle 8.9.
- GitHub: push to `main`, or Actions tab > **Android Build** > **Run workflow**. Download the artifact
  **almus-app-creator-pro-debug-apk** from the bottom of the run page (a zip containing `app-debug.apk`).
  Test and lint reports are uploaded as separate artifacts.

## Uploading from an Android phone (no Git, no Termux)

Same as Phase 1. If you already have the repository, the simplest way to apply Phase 2 is to replace the files:
the GitHub website cannot upload folders from a phone, so for each changed file open it in the repository, tap the
pencil (edit), paste the new contents and commit. New files: **Add file > Create new file**, type the full path
(typing `/` creates folders), paste, commit. Alternatively delete the repository's files and upload everything fresh
from a desktop browser. Make sure `settings.gradle.kts` is at the repository root and the branch is `main`.

## How to test each feature on the phone

1. **Create:** Projects tab > **+** > enter a name, pick a type, **Create**. The editor opens with starter files.
   Try an invalid name (`ab`, `-x`, a duplicate) and an invalid package (`Foo`, `com.class`): errors show under the field.
2. **Persistence:** close the app from the recent-apps screen, reopen: the project is still listed. Edit and save a file,
   force-stop, reopen the project: the edit is there.
3. **Edit:** type code; check colors, line numbers, bracket highlight (put the caret next to `{`), Enter after `{`
   (indents), Tab / Untab on a selection, Undo / Redo.
4. **Clipboard / find:** select text > Copy, move the caret > Paste; **All** selects everything. Search icon:
   enter text, Next/Prev, Replace, All.
5. **Files:** Menu icon > file explorer. **New file** (`utils/Helper.kt` creates a folder), open several files and switch
   tabs, three-dot menu > Rename/move, Delete (confirmation). Tap a folder to collapse it.
6. **Unsaved changes:** edit without saving, then (a) tap a tab's x, (b) press Back: a dialog offers Save / Discard /
   Keep editing. Press Home with unsaved edits and come back: the edits were auto-saved.
7. **Rename / delete project:** card menu > Rename, Delete (confirmation); also Menu > pencil icon inside the editor.
8. **Corrupted data:** cannot be exercised without file access on a normal phone; it is covered by unit tests.
9. **Themes:** Settings > Appearance > Dark / Light; the editor colors follow.

## If the build fails

The first `error:`/`e:` line in the failed step's log is the cause. Most likely, because the UI could not be compiled in
the authoring sandbox: a Compose API mismatch in `ui/editor/*` (for example `KeyboardOptions(autoCorrectEnabled = ...)`,
`LocalClipboardManager`, or an icon missing from `material-icons-core`). Paste the error and it is a one-line fix.
Unit-test failures block the APK step and their report is uploaded as an artifact.

## Known limitations

(Phase 2 list, still true) no binary/image files, no empty folders, long lines scroll sideways, bracket
matching ignores strings/comments, highlighting is skipped above 200,000 characters, files are limited to 512 KB,
the starter templates are plain starting points that have not been build-tested.

## Phase 3: GitHub integration and cloud builds

### How it works
The phone is only the client. Your GitHub repository stores the sources, a GitHub Actions runner builds the APK, and
the APK is kept as an Actions artifact. The app: signs in, lets you pick a repository/branch, validates the project,
commits its files in one atomic commit (Git Data API, never force-push), starts the workflow, polls its status and
shows Queued / Running / Success / Failed / Cancelled. "Success" is shown **only** when GitHub reports the run as
completed + success. If polling stops (timeout, rate limit, repeated errors, sign-in expired) the app says polling
stopped and does not guess a result; open the run on GitHub to see the truth.

### Sign-in and security (no backend needed)
- Uses the GitHub **OAuth device flow**: only a public *client id* is needed. **No client secret exists anywhere** in the
  source, assets or APK.
- Token is stored encrypted with an Android Keystore key (AES-256-GCM); never logged (the app has no logging; a test
  enforces this); backups disabled. "Disconnect" deletes it from the phone.
- The app says "Connected" only after `GET /user` succeeds. Saved-but-unverifiable token = "Unverified"; 401 = "Expired", token deleted.
- Three methods: **GitHub App** (recommended: only repos you install it on; Contents/Actions/Metadata only; cannot create
  repos), **OAuth App public_repo**, **OAuth App repo** (broadest). The `workflow` scope is never requested.
- The build workflow has `permissions: contents: read`, uses no secrets, does not persist the checkout token, validates
  inputs and passes them through env vars, only builds `assembleDebug`, and runs only when started manually.
- Limits: revoking the token fully must be done at github.com > Settings > Applications. GitHub App user tokens can
  expire after 8 hours; the app then asks you to reconnect (refreshing would need a client secret, so it is not done).

### One-time setup (all from an Android phone, in the browser)
1. **Create a sign-in app** on github.com (desktop-site mode helps):
   - Recommended: Settings > Developer settings > GitHub Apps > New. Name it, set Homepage URL to anything, untick
     Webhook, tick **Enable Device Flow**, Repository permissions: Contents = Read & write, Actions = Read & write
     (needed to start runs), Metadata = Read. Create it, copy the **Client ID**, then *Install App* on the repositories you want.
   - Simpler: Developer settings > OAuth Apps > New, tick **Enable Device Flow**, copy the **Client ID**.
2. Paste the **Client ID** into the app's GitHub screen (it is a public identifier, kept on the phone only). Never paste a client secret anywhere.
3. **Add the workflow to the repo you build in**: in the app's Cloud build screen tap "Copy workflow", then "Open on GitHub",
   paste, Commit. The file is `.github/workflows/almus-cloud-build.yml`. It must exist on the default branch **and** the branch you build.
4. Required repository secrets: **none**.
5. Open the app > GitHub > pick the method > Connect. A code appears; tap to open github.com/login/device, enter it, approve.
6. Repositories > select repo and branch. Projects > project menu > Cloud build > Start. Watch it in Builds.

### Where things are
`domain/github` (models, errors, API interface, validators), `service/github` (HTTP, API client, device flow, Keystore store, auth manager),
`service/build` (orchestrator, tracker, workflow template), `data/repository` (build history/config stores),
`ui/github`, `ui/cloudbuild`, `ui/builds`. Workflow: `.github/workflows/almus-cloud-build.yml`.

### Tests
Run `./gradlew testDebugUnitTest` (or let `android-build.yml` run it). Covers: not-connected states, token rejection, invalid
repos/branches, missing workflow, dispatch errors, run correlation, polling intervals/timeouts/rate limits/error backoff,
queued/running/success/failed/cancelled mapping, atomic commit and conflicts, validators, stores, ViewModels, workflow
security guards and secret scans.

### Known limitations
- Compose screens, Gradle/AGP/OkHttp resolution, Keystore store and DI wiring were never compiled by the author (no
  Android SDK/Maven access); the first Actions run may need small fixes.
- Polling only runs while the app process is alive; it resumes next time the app opens.
- The APK is not downloaded inside the app: open the run on GitHub and download the `almus-apk-<id>` artifact.
- Build and untrusted project code run in a single job (read-only token, no secrets). Actions are pinned to tags, not SHAs.
- Gradle cache is disabled for safety, so builds are slower.
- Only native Gradle Android projects; web/other types are rejected. Max 400 files / 5 MB per upload.
- Creating repositories works only with OAuth methods, not with a GitHub App.
- Phase 4 is described below. Phase 5 has not been started.

## Phase 4: build results (monitor, logs, APK download, history)

### What you can do now
Open a build (Builds, or right after starting one). The screen shows:
- **Status and timing**: status pill, build ID, repository, branch, folder, commit, run id, start time, finish time and a duration that counts up live. While the build runs the app checks GitHub every 8 to 30 seconds and stops by itself when the build is Success, Failed or Cancelled. **Check GitHub now / Refresh** checks immediately. Returning to the screen also refreshes.
- **Network and sign-in problems**: short outages are retried with waiting; a long outage or an expired sign-in stops checking and says so ("Not updating ... not a final result") with a **Refresh** or **Reconnect GitHub** button. Nothing is ever shown as finished unless GitHub said so.
- **Steps**: every step of the run with ok / FAILED / skipped marks, and "Failed at: ...".
- **Errors and log**: for failed builds the app reads the end of the failed job's log, picks out compiler errors (Kotlin, Java, Gradle "What went wrong", missing dependencies/SDK), and shows them with **Copy errors** / **Copy log**. **Show build log** does the same for any finished build. Secrets and tokens are removed from the text before it is shown or copied. If GitHub no longer has the log (logs expire), or this sign-in may not read it, the app says that honestly and offers **Open run on GitHub**.
- **APK and artifacts**: name, size, expiry date ("expires in 12 d") and status (available / expired / deleted) read from GitHub. **Download APK** shows progress and can be cancelled; failures show a clear reason and **Try again** only when trying again can help. A second tap while downloading does nothing (no duplicates). The app never says an APK is available unless GitHub lists a non-expired artifact.
- **Saved APKs**: downloaded APKs are kept in the app's private storage. **Share** (Android share sheet), **Save to...** (you choose a folder/file, no storage permission needed) and **Delete**.
- **Retry build / Build again**: uploads the project's current files again to the same repository, branch and folder and starts a new build (a new history entry). The old one stays.

**History** lists every build with its real status and one line about its APK: saved on this phone, available on GitHub (with a **Download APK** button and progress), expired, deleted, or failed. Tap a build to reopen its results.

### How the APK download is secured
- GitHub's artifact endpoint answers with a redirect to a short-lived storage link. The app sends your token **only to api.github.com**; the redirect is read by hand and followed **without** the token, and only if it is `https`.
- The signed link and the token are never logged, shown or put in error messages (tests check this).
- Checks before anything is saved: HTTP status (expired = 410, deleted = 404, forbidden, rate limit, server errors), size announced vs received, a 250 MB limit (counted from real bytes, so a lying server or zip bomb is stopped), valid zip with checksums, every extracted file must be a real APK (contains AndroidManifest.xml). File names inside the zip are sanitised and can never choose where a file is written.
- Partial downloads go to a temporary folder and are deleted on failure or cancel; an APK saved earlier is never damaged by a failed download.
- Sharing uses Android's FileProvider limited to the `apks/` folder only. The app does not request install or storage permissions.

### Using it from the phone
1. Start a build as in Phase 3 (Projects > menu > Cloud build > Start).
2. Builds > tap the build. Wait for Success (you can leave the app; open it again and it catches up).
3. Tap **Download APK**. When it says "Downloaded and verified", tap **Share** (for example to Files or WhatsApp) or **Save to...**.
4. To install, open the saved file from a file manager. Android may ask you to allow installing from that app.
5. If the build failed, read "Errors and log", fix the code in the editor, then tap **Retry build**.

### Setup
Nothing new. The GitHub App / OAuth App needs permission to **read Actions** (artifacts and logs). Phase 3's setup already asks for Actions read and write. If an older sign-in cannot read artifacts you will see "GitHub refused the download": reconnect with a method that includes Actions access.

### Where things are
`service/artifacts` (stream transport seam, downloader, APK store, download manager), `service/github/OkHttpStreamTransport.kt`, `service/build/BuildDetailsService.kt` (steps, logs, artifact refresh), `domain/github/BuildLogs.kt` (redaction and Gradle error extraction), `ui/builds` (screens, ViewModels, share helper), `res/xml/file_paths.xml`.

### GitHub Actions still produces the APK
`.github/workflows/android-build.yml` is unchanged in what it does (unit tests, `gradle assembleDebug`, upload of `app-debug.apk`; only the checkout hardening from Phase 3). Tests check it still runs `assembleDebug` and fails if no APK is produced. Phase 4 adds one FileProvider entry and one XML resource to the app; both are standard AndroidX. **This was not run by the author** (see limitations).

### Tests added in Phase 4
Successful downloads; failed, interrupted, truncated, cancelled and retried downloads; missing, expired and deleted artifacts; sign-in expiry (401); redirect safety; token never sent to the storage host; zip tricks and size limits; build status transitions and stopping at a final state; network outages and resume; log redaction and error extraction; history availability; retry; double-start protection; ViewModels; old Phase 3 history files still load.

### Phase 4 known limitations
- Not compiled or run by the author: the Compose screens, the OkHttp stream transport, FileProvider/SAF wiring and the Gradle build (no Android SDK or Maven access here). The first GitHub Actions run is the real test; send the first `e:`/`error:` line if it fails.
- Checking happens only while the app process is alive (no background service). Open the app to catch up.
- The app does not install APKs itself; it shares or saves them. Debug APKs are signed with a debug key.
- Only the last part of a log (about 40 KB) is read. The full log is on GitHub via the button.
- The build-log artifact is not downloaded; logs come from the job log.
- Artifacts are kept by GitHub for 14 days (APK) and 7 days (log) by the workflow; after that downloads are impossible and the app says so.
- No automatic retry of a download after the app is closed; no connectivity listener (use Refresh).

## Signing the app (stable key, set up from a phone)

### Why
Without a fixed key, every GitHub build is signed with a different temporary debug key. Android then refuses to install a new build over the old one ("App not installed"). With the Almus key, every build has the same signature and updates the installed app.

### How it is kept safe
The key is **never in the repository**. It lives in two GitHub repository secrets. The build workflow decodes it into a temporary file, passes it to Gradle only through environment variables, deletes it afterwards, and then checks with `apksigner` that the APK really carries your key (it fails the build if a key was set up but the APK is still debug-signed). The workflow runs only on pushes to `main` and manual runs, never on pull requests, so outsiders cannot reach the secrets. The in-app cloud builds for *your* projects stay secret-free on purpose (they run untrusted code); they are not signed with this key.

### One-time setup on an Android phone (github.com in the browser, "Desktop site" helps)
1. In your repository: **Settings > Secrets and variables > Actions > New repository secret**. Name: `ALMUS_KEYSTORE_PASSWORD`. Value: a long password you choose (at least 16 characters, 24 if the repository is public). Write it down somewhere safe.
2. **Actions** tab > **Create Signing Key** > **Run workflow**. It makes the key (about 1 minute).
3. Open the finished run. Under **Artifacts** download **almus-signing-key** (a zip, kept 1 day). Unzip it in your Files app.
4. Open `ALMUS_KEYSTORE_BASE64.txt`, select all the text, copy.
5. **New repository secret**: name `ALMUS_KEYSTORE_BASE64`, paste, save.
6. **Actions > Android Build > Run workflow**. When it finishes, the run summary says "Signed with the Almus key" and shows a certificate fingerprint. Download the APK artifact as before. Every later build shows the same fingerprint.
7. Back up your password and the `.txt` file (for example in a password manager). If both are lost, a new key means the installed app must be uninstalled before a new one can be installed.

Notes: the "Create Signing Key" workflow refuses to run again once `ALMUS_KEYSTORE_BASE64` exists, so you cannot replace the key by accident. Phone installs of an APK signed with a different key than the one already installed must be uninstalled first (this loses the app's data once).

### Limitations
- This signs the **debug** variant (same `app-debug.apk` name and path). A minified release build is not configured; it has never been compiled here and would need its own test.
- Not run by the author: the Gradle signing block and the new workflow steps have only been checked statically (YAML parses, `keytool` command tested locally, 467 JVM tests). The first run is the real test.
- Google Play signing/upload keys are not covered.
