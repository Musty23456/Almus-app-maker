# Phase 33 - APK and AAB management

## What it does
The Artifacts screen now shows every APK/AAB saved on the phone and **checks each one in the background**:

| Requirement | Where |
|---|---|
| Detect APK / AAB | `ApkStore` (saved files) + "On GitHub, not saved here" list built from finished builds |
| Name, size | card header, `Size:` |
| Version, package | read **from the file** (APK: Android's parser; AAB: its protobuf manifest). Build settings are used only when the file cannot tell, and the card says so |
| Build variant | `Build:` line + coloured badge `DEBUG APK`, `RELEASE AAB`... |
| Download | "On GitHub" cards with Download / Cancel / Try again |
| Export | **Save to...** copies the file and checks the checksum of the copy |
| Share | **Share** (title carries the DEBUG/RELEASE badge) |
| Organize | grouped by project; filter APK/AAB, debug/release, text search; sort newest/oldest/largest/name; **Clean up older files** (keeps newest 1-3 per project+type+kind, shows exactly what goes) |
| Delete with confirmation | always confirmed; RELEASE files get a stronger warning |
| Integrity | SHA-256 recorded at download; later checks say Verified / Changed since saved / Damaged; changed or damaged files cannot be shared or exported |
| Signing status | Not signed / debug key / your own key (matched by public fingerprint) / another key, with schemes (v1, v2, v3, v3.1), and whether the signature was **verified** and by whom |

## Honesty rules (tested)
* An APK is called installable **only** when: the archive is whole, its checksum is not changed, Android itself parsed it **and verified its signature**, and the phone's Android version is high enough. Otherwise the card says "Not checked yet", "not signed", "Android rejected this file"...
* An AAB is **never** called installable (it is for Google Play).
* What the **file** says (debuggable flag, debug certificate) beats what the build settings asked for; a disagreement, or a release build signed with the debug key, is a red warning.
* A signature that was merely found is shown as "not verified".

## Signing secrets
The manager only ever sees the **public** certificate fingerprint and the label of a saved key (`KnownSigningKeys`). It never touches `SigningManager.secrets`, keystores, passwords or private keys. `Phase33GuardsTest` fails the build if the artifact code ever mentions them or logs. Checksum records live in `filesDir/apk-meta/`, outside the folder the FileProvider can share, and hold only checksum, size, time, source.

## Changed / new files
New: `service/artifacts/` `ArtifactModels, Checksums, ApkSigningBlock, BundleManifestReader, ArtifactAnalyzer, ApkPackageReader, AndroidApkPackageReader, ArtifactRegistry, ArtifactAssessor, ArtifactCatalog, ArtifactOrganizer, BuildIntents`; `ui/artifacts/` `ArtifactTexts, ArtifactCard`.
Rewritten: `ui/artifacts/ArtifactsViewModel, ArtifactsScreen`.
Edited: `ApkStore` (checksummed copy, `validBuildId`), `ApkDownloadManager` (optional `registry`), `ApkActions` (optional share label), `AppContainer`, `AppViewModelProvider`, `Phase6ArtifactsTest`.
Removed: `ArtifactInspector.kt` (replaced by `ApkPackageReader`).
Tests: `Phase33*Test` (66 tests, real signed fixtures made with keytool/jarsigner).

## Verification
The logic layer (everything except Compose, `AndroidApkPackageReader`, `AppContainer`) was compiled with Kotlin 2.0.21 and all 66 new tests passed with JUnit 4. The Compose/Android files could not be compiled here (no Android SDK) and were reviewed against the repo's own usage by a separate reviewer: no errors found. The Phase 6 view-model tests were rewritten but could not be run here.

## Not done on purpose
No install button; no cryptographic v2/v3 verification of my own (Android does it); Phase 34 not started.

## IMPORTANT: missing folders in the zip
`domain/build/` and `service/build/` are **not in the zip you sent** (the project cannot compile without them: 21 files import them). I did not touch or recreate them. Make sure they exist in your GitHub repo (see FIX_DOMAIN_BUILD_REPORT.md).
