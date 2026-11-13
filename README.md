# Almus App Creator Pro (Phase 20)

An Android app for creating projects, editing code and (later) building APKs on GitHub Actions, all from a phone.

Phase 2: project manager + code editor with on-device storage (unchanged).
Phase 3: real GitHub sign-in, repository selection and cloud builds on GitHub Actions (unchanged, see "Phase 3").
**Phase 5 adds advanced project import / export, backup & restore and recent projects** (see "Phase 5" at the end).
**Phase 4 completes the build result system: live build monitor, logs and compiler errors, APK download / share / save, retry and a smarter history** (see "Phase 4" below).

## Status: what is and is not verified

> **Phase 25 (project explorer, see PHASE25_REPORT.md):** the editor drawer is now a Project Explorer: hierarchical tree with folder totals, file search, new file/folder, rename/move (files and folders), duplicate, delete with confirmation, preview with syntax colors, recent files, file-type badges, a project structure view, and protection for important configuration files. All operations stay inside the project folder. **Nothing in this phase was compiled or run** (no Kotlin compiler here); expect a few compile errors on the first CI run.
>
> **Phase 24 (AI coding assistant, see PHASE24_REPORT.md):** new **Smart** chat mode: understands English and Hausa requests (explain, generate, optimize, find bugs, function/class/UI/API generation, refactor, follow-ups), knows the project's language, framework, architecture, dependencies and conventions, suggests next steps, and shows generated code for **review and approval** before it reaches Changes. Backend unchanged; old chat modes unchanged. AI package compiled and 133 tests passed; the Compose screen is **not compiled**.

> **Phase 23 (code repair engine, see PHASE23_REPORT.md):** diagnosed build errors can now become real, reviewable patches: originals are read first, changes are staged (never written),
> validated, previewed line by line, accepted or rejected **per file**, applied only on your approval, recorded, and can be rolled back. It **never claims an error is fixed**: only a rebuild shows that.
> **Not compiled or run** (no Kotlin compiler, Gradle or network here): expect to fix compile errors on the first CI run. New tests: `app/src/test/.../repair/`.

> **Phase 10 (verification, see PHASE10_REPORT.md):** this environment again had **no Kotlin compiler, Gradle, Android SDK or network**, so the Android app was **not compiled, not unit-tested and not linted** in Phase 10. Nothing in Phases 6-9 has been proven to compile. What *was* run: the backend suite (**35/35 tests pass**, 5 of them new, with mutation checks on 3 of the 5), an offline consistency check over all 256 Kotlin files (`python3 scripts/verify_offline.py .`, which is **not** a compiler) and a YAML parse of all workflows. Changes: stray ProGuard file merged into the real one, version 0.10.0 (code 11), and a new independent `checks` CI job. **The first green GitHub Actions run (`gradle testDebugUnitTest assembleDebug`) is still the only real proof that the app builds.**

> **Phase 11 (advanced editor, see PHASE11_REPORT.md):** adds an editor ENGINE layer (13 new Kotlin files, 121 new unit tests) and five new
> languages for syntax highlighting. **None of it was compiled or run** (same missing toolchain as Phase 10), and **only the five new languages are visible
> in the app**; completion, folding, outline, project search, multi-cursor, minimap, themes, shortcuts, split-screen state and the AI helpers are tested-by-design
> logic that is **not connected to the editor screen yet**. No editor library was adopted. Details and the exact list of what works are in the report.

> Phase 9 (Gemini AI): see PHASE9_REPORT.md. The **backend** (`backend/`, Node.js) was run and tested here: **30 tests pass** against a mock Gemini server, plus a mutation check. The **Android/Kotlin code of Phase 9 could NOT be compiled here** (this environment had no Kotlin compiler, Gradle, Android SDK or network), so its 100+ new JUnit tests were written but **not run**. Security-critical regexes and the diff algorithm were checked on the JVM. Build once with GitHub Actions (`./gradlew testDebugUnitTest assembleDebug`) and fix any compile errors before relying on it. **Also note:** the uploaded Phase 8 ZIP did not contain the `domain/build` and `service/build` packages that the code imports; they must be present in your real repository.

> Phase 8: see PHASE8_REPORT.md. The new `debug` package (build-log analyzer, static checks, log engine, fix engine, AI-preparation layer, debug session) has **no Android dependency and was compiled with Kotlin 2.0.21 and tested here: 82 JUnit tests pass**. The Compose screens, navigation wiring and Gradle/AGP build of Phase 8 (like Phases 6 and 7) were **not** compiled here: build once with GitHub Actions (`./gradlew testDebugUnitTest assembleDebug`) before relying on them.

> Phase 7: see PHASE7_REPORT.md. Like Phase 6, it could NOT be compiled or run where it was written (no Kotlin compiler, Gradle, Android SDK or network). Build it once with GitHub Actions (`./gradlew testDebugUnitTest assembleDebug`) and fix any compile errors before relying on it.

> Phase 6: see PHASE6_REPORT.md. The Phase 6 code could not be compiled or run in the environment it was written in; it must be built once by GitHub Actions before you rely on it.

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

## Phase 11: advanced editor engine

New plain-Kotlin classes in `editor/` (no Android dependency, so they can be unit-tested on a JVM) and one in `ai/`:

| Class | What it does | Limits |
|---|---|---|
| `Language` (extended) | Python, TypeScript, Dart, YAML, SQL join Kotlin, Java, XML, JSON, JS, HTML, CSS, Markdown. **Live** through the existing highlighter | Highlighting is token based, not semantic |
| `OutlineEngine`, `DefinitionFinder` | File outline and name-based "go to definition" / symbol list | Line-by-line patterns; no types or scopes; Java methods need a modifier |
| `FoldingEngine` | Fold regions from brackets, tags, indentation, Markdown headings | Not a parser; unbalanced code gives no region |
| `CompletionEngine` | Keywords, snippets, words in the file, project symbols; camel-hump matching | Lexical only: no types, nothing after `.` |
| `Navigation`, `LineIndex` | Go to line (`12`, `12:5`), word at caret, offset/line conversion | |
| `LineCommands` | Duplicate, delete, move lines; toggle comment per language | |
| `MultiCursor` | Insert, delete, add caret above/below, select next/all occurrences | Needs a surface that can draw several carets |
| `ProjectSearch` | Search and replace over a path-to-text map (case, whole word, regex) | Per line; skips binary files |
| `MinimapModel` | Density rows and viewport mapping for a minimap | Drawing not written |
| `EditorThemes` | Four built-in themes, custom themes from hex colours, contrast check | No settings screen or persistence yet |
| `ShortcutMap`, `KeyChord` | Default external-keyboard shortcuts, conflict-safe rebinding | The editor screen still handles only Tab / Shift+Tab |
| `SplitLayout` | State for one or two editor panes (side by side or stacked) | No split UI |
| `EditorAiPrompts` | Explain / suggest requests for the Phase 9 backend with a code window, secret scrubbing, blocked sensitive files | No button yet; never run against real Gemini |

Run the logic tests with Gradle on a machine with the Android SDK: `gradle testDebugUnitTest --tests "*Phase11*" --tests "*EditorAiTest"`.

## Build and run

- Backend tests (no install needed, Node 18.17+): `cd backend && npm test`. Offline consistency check: `python3 scripts/verify_offline.py .`
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

## Phase 6: build management and release signing

Phase 6 turns "Build" into a build manager. Everything from Phases 3 and 4 still works: a repository that still has the
version 1 workflow keeps building plain debug APKs.

**Build types** (Cloud build > Build type > Build configuration): Debug APK, Release APK, Release AAB, and Custom
(APK or AAB, debug or release, signed or not).

**Build configuration**: application ID, version name, version code, minimum SDK, target SDK, code shrinking (minify) and
resource shrinking. Leave a field empty to keep the project's own value. The values are applied in the cloud by a small
Gradle init script, so your project files are never edited. Every value is checked in the app and again in the workflow.
This applies to every Android application module of the project.

**Release signing** (Settings > Release signing): create a key (RSA 2048, valid 30 years, PKCS12) or import one
(PKCS12 is verified on the phone; JKS is kept and verified by the cloud build). The keystore and passwords are stored
encrypted on the phone (AES-256-GCM, key in the Android Keystore), the screen blocks screenshots, and nothing is written
to your project or repository. Use "Back up" to save the keystore file yourself: if you lose the key or its password you
can never update an app that was signed with it.

**How cloud signing works**
1. In the app: Release signing > Show secrets. Add four repository secrets in GitHub
   (Settings > Secrets and variables > Actions): `ALMUS_SIGN_KEYSTORE_BASE64`, `ALMUS_SIGN_STORE_PASSWORD`,
   `ALMUS_SIGN_KEY_ALIAS`, `ALMUS_SIGN_KEY_PASSWORD`. Then tap "I added the secrets" (the app cannot read GitHub secrets;
   the build checks them again and says clearly if they are wrong).
2. Copy the new `.github/workflows/almus-cloud-build.yml` (version 2, "Workflow setup" in the app) to the branch you
   build from and to the default branch.
3. Choose a Release build type and the key in Build configuration, then build.

The workflow has two jobs. **build** runs your project's Gradle scripts and has no access to any secret; it produces an
unsigned release file. **sign** reads the secrets, signs that file with `apksigner` (APK) or `jarsigner` (AAB), verifies the
signature, rejects the Android debug key, and runs none of your project's code. The key file is deleted from the runner when
the job ends. Passwords reach the tools only through environment variables and are never printed.

**Managing builds**: Cancel build (running builds; if GitHub has not created the run yet, the request is remembered and
sent as soon as it exists), Retry (repeats the build with the same type and settings), history with build type, status and
duration, and download of APK and AAB files. Authentication errors show "connect again" as before.

**Artifacts** (Settings > Saved APK and AAB files): every downloaded file with size, package name, version, build type,
Share and Save to... (AAB cannot be installed; it is for upload to Google Play). For an APK the package and version are read
from the file; for an AAB they come from your build settings when you set them.

New dependency: BouncyCastle (`bcprov-jdk18on`, `bcpkix-jdk18on` 1.78.1), used only to create the certificate of a new key.

Limitations: one set of signing secrets per repository (projects in the same repository share it); product flavors are not
selectable (Gradle task names are limited to assembleDebug/assembleRelease/bundleDebug/bundleRelease); AAB package/version
display depends on build settings; the override script has not been run against a real Gradle build yet (see
PHASE6_REPORT.md).

## Phase 7: background build monitoring, notifications and dashboard

GitHub Actions still does the whole build. Phase 7 only **watches** it: the app checks the status of unfinished builds in the
background, keeps the local build records in sync, and tells you what happened. Nothing from Phases 1-6 was removed; the
build history screen is still there ("Full history" in the dashboard).

### What it does
- **Background monitoring with WorkManager.** While at least one build is unfinished, two kinds of jobs exist, both
  requiring a network connection:
  - a *watchdog* (periodic, 15 minutes - the minimum Android allows) that also looks again at builds whose live checking had stopped;
  - a *chain* of one-time jobs while a build is running: each job checks, then schedules the next one (1 min while the build is
    younger than 10 min, 2 min up to 30 min, then 5 min). When nothing is left to watch, both are cancelled.
  Android decides when work really runs (Doze, App Standby, battery saver): every delay is a minimum, so a minimised or
  closed app may be told about a build later than the delay says. No foreground service, exact alarm or battery-exemption
  request is used (a unit test guards the manifest).
- **Few requests.** One GitHub request per unfinished build per pass; builds the app is already following live are skipped;
  the same build is not asked again within 30 s; a pass ends at the first connection failure or rate limit; at most 10 builds per
  pass; builds older than 8 h are no longer watched.
- **Retry and backoff.** A connection problem makes the worker return "retry": WorkManager repeats it with exponential
  backoff (30 s base). After 4 attempts in a row the pass is treated as done and the chain continues at its normal pace.
  A rate limit delays the next check until GitHub allows it (at most 15 min). A lost GitHub sign-in stops the checking and says
  "Reconnect GitHub" instead of retrying forever.
- **Recovery.** Records live in the app's private storage as before. At app start the tracker resumes unfinished builds (Phase 3), and
  the monitor additionally checks builds whose checking had stopped (one request each) and resumes live following for those still
  running. WorkManager keeps its schedule across process death and reboots, so a closed app is still told when the build ends.
- **Notifications** (one per stage, remembered across restarts): build started, in progress (silent, updates in place, shows elapsed time),
  succeeded, failed, cancelled. Each shows the project name, build type, status and result; tapping opens that build's details.
  Old results (older than 12 h) and builds from before Phase 7 are not announced. Two channels: "Build progress" (quiet) and "Build results".
- **Build dashboard** (Home > Build history "See all", or Settings > Build monitoring): counters (active / done / failed / cancelled),
  filter chips, active builds with live progress, running time, refresh and cancel, and the history with duration and artifact availability
  (APK/AAB ready, saved on this phone, expired, deleted, none).
- **Controls.** Notifications on/off (Settings, default on); refresh button (checks every unfinished build once and resumes live following);
  cancel for runs that are still unfinished (a cancel asked before GitHub created the run is sent when it appears); open build details.

### Notification permission
Android 13+ needs the notification permission. The dashboard shows a banner with "Allow notifications" while it is missing; if you refuse,
the switch goes off and the app does not ask again. If you block notifications in Android settings, the app says so and opens that settings page.
Builds and monitoring work exactly the same without notifications.

### Where things are
`service/monitor/`: `BuildSyncEngine` (one pass), `BuildMonitor` (store watching, recovery, WorkManager decisions), `BuildNotificationPlanner` /
`BuildNotificationCoordinator` (what to show, exactly once), `BackgroundSyncPolicy` (all timings), `BuildSyncScheduler` (+ `WorkManagerBuildSyncScheduler`),
`BuildSyncWorker`, `AndroidNotificationSink`, `NotificationController`. `CloudBuildOrchestrator.syncOnce` is the one-shot status check.
`ui/dashboard/`: model, view model, screen.

### Tests (JVM unit tests, `app/src/test/.../monitor`)
Background pass and scheduling decisions, restart recovery from disk (`FileBuildStore`), offline/online (network loss, retry, one notification),
rate limit, lost sign-in, several simultaneous builds, request economy, notification rules and permission states, dashboard model and view model,
settings switch, manifest guard. WorkManager itself and the real notification drawer are not unit-testable on a plain JVM: see PHASE7_REPORT.md
for the on-device checklist.

## Phase 8: debugging and error analysis

Phase 8 helps you find out **why a build failed** and **what looks wrong in the code**, and lets you fix it with a preview and undo.
Nothing from Phases 1-7 was removed.

### What is real and what is not (please read)
- **Build log analysis is real**: it reads the actual GitHub Actions log of a build and groups the errors it finds.
- **Code checks are static heuristics**: simple rules that read your files **without compiling or running them**. They find common
  mistakes (missing imports, unused code, unbalanced brackets, missing resources, manifest and Gradle slips) and **can miss problems
  or be wrong**; each finding shows a confidence level. They are **not** a compiler and **not** a runtime debugger.
- **There is no live Logcat.** Android only lets an app read its own log; reading another app's log needs ADB or a debug agent.
  The architecture has a `LogcatSource` port and a `threadtime` line parser, so a real source can be added later, but nothing
  pretends to show runtime logs today.
- **There is no AI in Phase 8.** The preparation is in place (see below) but no model is called and no AI answer is written into the app.

### How to use it
- **After a failed build:** Build status > **Analyze errors**. **From the editor:** menu (three dots) > **Check code & errors**.
- **Errors tab:** every problem with message, file, line, category, severity and a suggested solution. **Open in editor** jumps to the
  exact file and line (and column when known). Filter: all / build log / code checks.
- **Log tab:** build log with levels (error / warning / info / debug), search (plain or regex, case option), **Copy**, and **Export**
  as .txt or .json through the system file picker. Tokens, passwords and keys are removed before anything is shown, copied or exported.
- **Fixes tab:** each suggestion shows before/after lines. **Apply** writes the file, **Reject** hides it, **Undo** restores the text from
  before the last applied fix. Safety: a fix is refused if the file changed after it was suggested, and undo is refused if you edited the
  file after applying (nothing of yours is overwritten). Undo history lasts for the current app session.

### What the build analyzer recognises
Kotlin compilation (K1, K2 and command-line formats), Java compilation (with `symbol:` and column), Gradle failures ("What went wrong"),
missing dependencies and repositories, AndroidManifest errors (including manifest-merger), resource linking (AAPT) errors, and
Android SDK configuration (missing SDK, licences, compileSdk/minSdk/targetSdk). Messages are matched by rules, so the suggestions are
rule-based text, not AI. CI paths (for example `/home/runner/work/...`) are mapped to your project files.

### Code checks (static)
Missing and unused imports, unused locals, undefined names (only when safe), reassigning a `val`, unbalanced brackets, empty `catch`,
`!!`, `GlobalScope`, blocking calls in UI files, Java `==` on strings, missing semicolons, unreachable code, division by zero, XML tag
balance, missing `@type/name` and `R.type.name` resources, manifest (exported, missing class, INTERNET), Gradle (namespace, SDK versions,
dependency without version). Checked on the real code of this project and on freshly generated templates (0 false errors).

### Preparing for AI debugging (Gemini later)
`debug/AiDebug.kt` defines the contract; no network code exists yet:
`AiRequestBuilder` (redacted, size-limited request: issues, log excerpt, code around the error; secret-looking files are never included)
-> `AiPromptRenderer` (provider-independent prompt with a JSON answer schema) -> a future `AiDebugProvider` -> `AiResponseParser` ->
`AiPatchConverter`, which turns search/replace or unified-diff patches into the same `FixProposal` as rule-based fixes (so they get the
same preview / apply / reject / undo) **only if they match your real file exactly**; otherwise they are rejected, never guessed.
The UI shows exactly what would be sent and asks first; the default `NoAiProvider` says the AI is not connected.

### Where things are
`debug/` (pure Kotlin, no Android): `BuildErrorAnalyzer`, `ErrorKnowledge`, `LogCleaner`, `LogEngine` (parse / filter / export), `StaticDiagnostics`,
`KnownSymbols`, `FixModels`, `FixManager`, `AiDebug`, `LogcatSource`, `DebugSession`, `RouteArgs`. `service/debug/DebugHub` (one session per
project/build), `ui/debug/` (view model, screen). The editor got `openAt(path, line, column)` and re-reads changed files when it resumes.
`BuildDetailsService.loadRawLog` reads up to 400,000 characters from the end of the job log.

### Tests (`app/src/test/.../debug`, sample projects with intentional errors in `app/src/test/resources/phase8-samples`)
Build-log analysis (real kotlinc/javac output and Gradle/AAPT/manifest logs), static checks on a sample Android project, every offered fix
applies and removes its issue, templates have no false errors, navigation targets point at the right line, log filter/search/export, fix
apply / stale / reject / undo / conflict, AI request redaction, response parsing and patch conversion, route argument encoding.

### Phase 8 limitations
Static checks are heuristic. The sample Gradle/AAPT/manifest logs were written by hand from the known formats (the kotlinc and javac logs are
real compiler output). Undo history is not kept after the app closes. The Compose screens were not compiled or run (see PHASE8_REPORT.md).

## Phase 9: Gemini AI assistant

Architecture: `Android app -> AI chat/editor screens -> ALMUS backend (backend/) -> Gemini API`. **The Gemini API key exists only on the backend.**
The app stores the backend address and an access code (Keystore-encrypted) and uses a 1-hour session token. Setup: **backend/docs/API_CONFIGURATION.md**.

**Where to find it**
- Settings > *AI assistant (Gemini)* > **AI backend settings** (address, access code, test connection, model) and **Generate an app with AI**; Projects top bar > **AI**.
- Editor menu > **AI assistant** for the open project: tabs **Chat**, **Edit**, **Review**, **Changes**.
- Debug screen (Phase 8) now has Gemini as its AI provider (Explain / Suggest corrections / Generate patch), still behind the Phase 8 "what will be sent" confirmation.

**Features**
- *Chat*: history per project (kept on the phone), streaming answers, syntax-highlighted code blocks with Copy, regenerate/retry, stop, clear, modes Ask / Explain code / Generate code / Edit code, attach files (shown before sending), "Apply to file" (turns a code block into a reviewable change).
- *Project generator*: describe an app -> **plan to review/edit** -> generate -> review each generated file (view/untick) -> create project. Generated files are validated (safe relative paths, no binaries/wrapper/credential/`.github` files, no secrets, size/count limits, duplicate and file-vs-folder conflicts, package/path match, manifest and Gradle presence) and run through the Phase 8 code checks.
- *Editing an existing project*: the AI first sees only the **file list** and says which files it needs; you confirm the files that will be sent; it proposes changes (full files or exact find/replace edits that must match once); you see per-file before/after diffs.
- *Change management*: nothing is written until **Apply changes**. Files changed since the suggestion make Apply refuse (nothing written); a failed write rolls back; **Reject** / bring back; **Undo** per applied change (kept on disk, survives restarts); "Restore anyway" asks before discarding later edits. The AI can never delete files.
- *Review*: quality, security, performance, architecture, best practices, findings sorted by severity with file/line shortcuts.
- *Context control*: only the files you pick (max 12, 40 K chars each, 150 K total); credential files and build output are never sent; secret-looking text is replaced before sending and again on the backend; dependency names and file paths (no content) give the model project awareness.

**Backend**: sign-in with access codes, signed expiring tokens, per-user per-minute/per-day limits, login lockout, size limits, secret redaction, reviewed per-task instructions, configurable models, Gemini retries/timeouts, streaming (SSE), clear key-free errors, no prompt logging. `cd backend && npm test`.

**Tests**: `backend/test` (30, Node built-in runner) and `app/src/test/java/com/almus/appcreatorpro/ai` (Kotlin, JUnit 4 + coroutines-test).

**Known limitations**: see PHASE9_REPORT.md (Kotlin not compiled here; one backend instance because limits are in memory; the AI cannot delete or rename files; generated projects are checked statically, not compiled, so the first cloud build is the real test).

## Phase 5: advanced project import and export

### What it does
- **Import from ZIP** (Projects > Import > Choose ZIP file): unpacks safely, detects the project type, shows a review screen
  (name, type, package, file counts, warnings) and saves **nothing until you tap Import**.
- **Import a folder / Android Studio project** (Projects > Import > Choose folder): uses Android's folder picker (Storage
  Access Framework). Pick the project's root folder.
- **Import from GitHub** (Projects > Import > Browse repositories): uses the existing GitHub sign-in (Settings > GitHub).
  Pick a repository, pick a branch, the app downloads that branch's current commit as a ZIP and shows the same review screen.
- **Export** (project card menu > Export as ZIP): choose where to save in the system "save as" dialog. The ZIP has the same
  folder structure as the project and contains every file, including images and other non-text files kept by an import.
- **Backups** (card menu > Back up now / Backups; Projects top bar menu > All backups): any number of backups per project
  (30 manual ones; safety copies do not count). Restore **as a new project** (never overwrites anything) or **replace the
  current project** (asks first and always saves a safety backup of the current state first). Backups are kept when a
  project is deleted and can restore it as a new project.
- **Recent projects**: projects you open are remembered ("Recently opened" row), and the list has search (name, package,
  description, type; every word must match) and sorting (updated, opened, name A-Z / Z-A, created, type).

### Supported projects
Native Android (Kotlin, Java; Gradle/AndroidManifest based) and HTML/CSS/JavaScript. Kotlin wins when a project has both
Kotlin and Java. Flutter and anything unrecognized is reported on the review screen and cannot be imported.

### Safety
- **Path traversal ("zip slip")**: any entry that is absolute, names a drive, contains `..` or a control character makes
  the whole import fail; every destination is also checked against the staging folder's canonical path. Only plain files
  are ever created (a ZIP "symlink" becomes an ordinary file).
- **Large / hostile archives**: everything runs on a background thread with progress and Cancel. Limits are enforced on the
  bytes really read: 20,000 files, 500 MB total, 100 MB per file (bigger files are skipped and reported), and a
  compression-ratio check against ZIP bombs.
- Unpacking happens in `filesDir/transfer-staging`, outside the project list. Any failure or Cancel removes it, so a failed
  import never leaves a half-made project. Leftovers of a killed app are removed at the next start.
- **Left out on purpose**: `.git`, `.idea`, `.gradle`, `node_modules`, Gradle `build/{intermediates,outputs,generated,...}`
  folders, `.apk`/`.aab`, `local.properties`, `.jks`/`.keystore` (signing keys). The review screen counts what was left out.
- GitHub: the token goes only to api.github.com; the redirect to the download server is followed without it, https only,
  and signed URLs are never shown or logged.
- **Permissions**: none added. The system file/folder pickers grant access to exactly what you pick.

### Storage layout and one important limitation
```
projects/<id>/project.properties   (unchanged)
projects/<id>/files/...            editable UTF-8 text files <= 512 KB with editor-safe names (unchanged; editor, cloud builds)
projects/<id>/extra/...            NEW: everything else an import found (images, jars, large files, other names)
backups/<projectId>/<backupId>.zip + .properties
```
Files in `extra/` are **kept and exported/backed up, but not shown in the editor and not uploaded by cloud builds** (those
only ever read `files/`). So an imported Android project that needs its PNG icons or a Gradle wrapper jar in the repository
will not build from the app's cloud build until that is supported. The review screen tells you how many such files exist.
Import from GitHub does not keep git history (only the files of the chosen commit).

### Files
New: `domain/transfer/*` (models, errors, `ZipEntryGuard`, `ProjectDetector`, `ProjectNaming`, `ProjectListFilter`),
`domain/repository/ProjectTransferRepository.kt`, `RecentProjectsTracker.kt`, `data/transfer/*` (`ProjectStager`,
`ProjectImportService`, `ProjectArchiver`, `BackupStore`, `CountingInputStream`), `data/storage/RecentProjectsStore.kt`,
`service/transfer/*` (SAF access, GitHub download), `ui/transfer/*` (import, backups, progress UI), tests in
`app/src/test/.../transfer/`. Modified: `FileProjectStore`, `AppErrors`, `ProjectsViewModel`, `ProjectsScreen`, `AppContainer`,
`AppViewModelProvider`, `Routes`, `AlmusApp`, `app/build.gradle.kts` (version only). See `PHASE5_REPORT.md` for the full list and
the verification status.


## Phase 13: AI development agent

Describe an app or a change; the agent plans, you approve the plan, it works in a staging area, checks its own files and shows diffs. Nothing reaches your project until you apply it, and applying can be rolled back. See PHASE13_REPORT.md (the new Kotlin is uncompiled and its tests have not been run; the backend tests pass).

## Phase 20: live preview

Editor menu, Live preview. Web pages run in a sandboxed WebView (no network by default, no access to your files). Android XML and Compose are drawn as labelled approximations, not the Android runtime. See PHASE20_REPORT.md (the new Kotlin is uncompiled and its tests have not been run; the backend tests pass).

## Phase 22 - Advanced error diagnosis
The debug screen has a Diagnosis tab (root cause vs secondary errors, affected files, explanation, solution, confidence) with optional AI diagnosis and follow-up questions. See `PHASE22_REPORT.md`.

## Phase 27 - Git integration
Manage Git repositories inside a project: **Editor menu -> Git** (status, stage/unstage, commit, history, branches, merge, conflicts,
fetch/pull/push, remote and author settings) and **Projects -> menu -> Clone repository** (creates a new project with full history).

- Engine: JGit (`git/JGitEngine.kt`). The work tree is the project's `files/` folder; the repository data lives in `<project>/git/`, so Git never adds files to the project, to exports or to cloud-build uploads.
- GitHub sign-in is reused (`GitHubTokenCredentialSource`). The token is read from the encrypted store per network call, handed out **only for https://github.com**, pinned to that host (no redirect can receive it), never written to the repository config, and scrubbed from every error message.
- Safety: delete branch, abort merge, take-one-side conflict resolution and push always show a confirmation (unmerged branches: type the branch name). The service enforces it, not only the UI. Push is never forced. Merge/pull start only with no uncommitted tracked changes. Committing keys/passwords/tokens needs an extra confirmation.
- Tests: `app/src/test/.../git/` (`GitCoreTest`, `JGitEngineTest`, `GitServiceTest`, `GitViewModelTest`) plus one case in `FileProjectStoreTest`. See `PHASE27_REPORT.md` for what was and was not run.
