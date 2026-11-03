# Phase 6 build verification report

## Read this first

**Nothing in Phase 6 has been compiled or executed.** The environment this phase was written in has a JDK but no Gradle,
no Kotlin compiler, no Android SDK and no network. So:

- the new Kotlin code and tests have **not** been compiled;
- the tests have **not** been run, so there is no pass/fail result to report;
- the workflow has **not** run on GitHub, and the Gradle init script has **not** been run against a real Android build.

What was verified, and how:

| Check | Result |
|---|---|
| All 171 Kotlin files: brackets, parentheses and string/template syntax (a real lexer) | no errors |
| Every API called by new code and tests compared against the actual declarations (BuildService, BuildStore, ApkStore, GitHubApi fakes, UI components, formatters, SecretStore, KeyValueStore, downloader) | matched; two mismatches found and fixed |
| Workflow YAML parses; 2 jobs; 12 inputs | yes |
| The new workflow guard tests, re-implemented in Python and run against the real YAML | all conditions hold (secrets only in the sign job, none in the build job, no `${{` in `run:`, key removal, retention 1 day, task allow-list, input list identical to what the app sends) |

## Next step (required)
Push to your own repository and let `.github/workflows/android-build.yml` run (`./gradlew testDebugUnitTest assembleDebug`).
Expect to fix some compile errors; I could not catch them here. Then do the device checks below.

## What was built
Files changed or added are listed in "Files" below.

- Build types: Debug APK, Release APK, Release AAB, Custom. Task allow-list shared by app and workflow.
- Build configuration: application ID, version name/code, min/target SDK, minify, shrink resources. Validated in the app
  (`BuildConfigurationValidator`) and again in the workflow; applied through a fixed Gradle init script, never by editing files.
- Signing manager: generate (BouncyCastle certificate, PKCS12), import (PKCS12 verified, JKS kept unverified), alias,
  passwords, GitHub secrets view, "I added the secrets" confirmation per repository, backup, delete.
- Build management: cancel (new `cancelRun` API, remembered if the run does not exist yet), retry with the same
  configuration, history, duration, status, AAB/APK download.
- Artifact manager screen and AAB support in the downloader and store.
- Workflow version 2 with a build job (no secrets) and a sign job (secrets, no project code). Version 1 repositories keep
  building plain debug APKs: the app sends only the four version 1 inputs, and refuses release/AAB/overrides with a clear message.

## Security properties (by design; the unit/guard tests assert them)
- Passwords and keys: stored only in the AES-GCM encrypted store (Android Keystore key, separate from the GitHub token's);
  `SigningSecrets.toString()` is masked; error messages never include passwords; the signing screen sets FLAG_SECURE.
- Never committed: nothing signing-related is written into the project folder or sent as a workflow input; only the secret
  *names* and the key id (local only) exist in app data. `allowBackup` is already false.
- Never in logs: the sign step uses `-storepass:env`/`--ks-pass env:`; no `echo` of any secret; the app's log redactor now also
  masks `*pass*` variable names, `keystore` values and long base64 blobs.
- GitHub Actions Secrets are the only place the credentials exist in the cloud; only the `sign` job can read them.
- No Gemini/API key exists in this app or in the workflows.

## Known risks (honest list)
1. **Never compiled** (above). Highest risk.
2. **Init script not tested on real AGP.** It relies on `afterEvaluate` ordering and the setters `setMinifyEnabled`,
   `setShrinkResources`, `defaultConfig.minSdk/targetSdk/versionCode/versionName/applicationId`. If a setting cannot be applied the
   build fails with "Almus could not apply these settings" rather than silently building the wrong thing.
3. **BouncyCastle on Android**: used without registering a provider. Proguard keep rule added; if R8 or a class clash causes
   trouble the fallback is the manual keystore flow in "Signing the app".
4. **PKCS12 written on Android** uses the platform's encoder; JDK 17 (keytool/apksigner/jarsigner) should read it, but this was
   not tried with a real phone-made keystore.
5. **Secrets confirmation is manual** (the app has no permission to read GitHub secrets, by design).
6. **Multiple application modules** all receive the same overrides.
7. Retry of a build was not given a dedicated new test; it reuses `record.configuration` through the existing retry path.

## Device/cloud test plan (run these after the first green CI build)
1. Debug build of a sample project, default configuration: APK appears, installs.
2. Debug build with a changed version code/name/application ID: installed package shows the new values.
3. Create a key; add the 4 secrets; Release APK: `apksigner verify --print-certs` shows your certificate, not the debug one.
4. Release AAB: `jarsigner -verify` succeeds; file appears as AAB; Share / Save to... work.
5. Wrong password in the secrets: build fails at step "Sign" with the hint; no password in the log or in the app.
6. Remove a secret: sign job stops with the "not set up" message.
7. Cancel during build and during the first seconds after Start: final status "Cancelled".
8. Sign out/expire the token, press Cancel: "connect again" message.
9. A repository that still has the version 1 workflow: debug build works; release is refused with the update message.

## Files
Added: `domain/build/{BuildVariant,BuildConfiguration,SigningModels}.kt`, `service/signing/{KeystoreGenerator,SigningStore,SigningManager}.kt`,
`service/artifacts/ArtifactInspector.kt`, `data/repository/BuildConfigurationJson.kt`, `ui/buildconfig/*`, `ui/signing/*`, `ui/artifacts/*`,
tests `build/BuildConfigurationTest`, `build/Phase6BuildFlowTest`, `build/Phase6ViewModelsTest`, `signing/SigningTest`, `artifacts/Phase6ArtifactsTest`.
Changed: `.github/workflows/almus-cloud-build.yml` (v2), `GitHubApi/Impl` (cancelRun), `GitHubModels`, `Models`, `BuildLogs`,
`BuildModels`, `BuildService`, `BuildTracker`, `BuildDetailsService`, `CloudBuildOrchestrator`, `ApkStore`, `ArtifactDownloader`,
`BuildRecordJson`, `AppContainer`, `AppViewModelProvider`, navigation/settings/cloud build/status/history screens and view models,
`ApkActions`, `app/build.gradle.kts` (BouncyCastle, version 0.6.0), `libs.versions.toml`, `proguard-rules.pro`,
`RepositoryGuardsTest`, `GitHubTestSupport`, `README.md`.
Unchanged: `android-build.yml`, `create-signing-key.yml`.
