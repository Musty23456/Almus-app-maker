# Fix: "Directory ... does not contain a Gradle build" and the missing `domain/build` + `service/build` packages

## What was wrong (from the uploaded CI logs and the GitHub zip)
1. The GitHub repository had no `settings.gradle.kts`, no `build.gradle.kts`, no `app/build.gradle.kts` and almost no Kotlin
   sources (only 11 files under `app/src`). Gradle therefore failed in "Run unit tests" with
   `Directory '.../Almus-app-maker' does not contain a Gradle build`. The workflow itself was fine.
2. The Phase 9 zip is complete, but two packages that the code imports were missing from it:
   `app/src/main/java/com/almus/appcreatorpro/domain/build/` and `.../service/build/` (a zip/upload tool that skips
   folders named `build` is the likely cause). The earlier rounds that rebuilt them were not part of the zip.

## What this zip contains
- Everything from the Phase 9 zip.
- `domain/build/`: BuildVariant (+OutputFormat), BuildConfiguration (+BuildOptions), BuildConfigurationValidator,
  SigningModels (SigningProfile/Source/Secrets/SecretNames/Exception/Validator/Readiness), Artifacts (ArtifactKind/ArtifactNaming), WorkflowInputs.
- `service/build/`: BuildFollower + SyncOutcome, BuildModels (StartResult, PreflightResult, WorkflowCheck, BuildDetails, BuildLogView, DetailsResult),
  CloudBuildOrchestrator (preflight, start, cancel, follow, syncOnce), BuildTracker, BuildService (+Prototype, GitHubActions),
  BuildDetailsService, WorkflowTemplateProvider (+Asset).
- `.github/workflows/almus-cloud-build.yml`: version 2 with a build job without secrets and a sign job (matches RepositoryGuardsTest).
  Its inputs are exactly the keys `WorkflowInputs` sends.
- `.gitignore`: `!app/src/**/build/`.
- Test names: removed `;` and `:` from backticked test names (illegal on the JVM).
- StaticDiagnostics: removed the literal `Log.e(` from a suggestion (the_app_never_logs).
- Debug test samples stored as `*.kt.sample` / `*.java.sample` (the Android Gradle plugin drops *.kt/*.java from test resources); `Samples` restores the names.
- New test: `src/test/.../build/BuildConfigurationTest.kt`.

## Honest status
These two packages were rebuilt AGAIN from how the existing code and tests use them. No Kotlin compiler, Gradle or network was
available, so NOTHING was compiled or run. Checked: every project import resolves to a declaration, brackets balance, and the
workflow inputs, secret names, artifact names and allowed Gradle tasks match the guard tests by script.
Expect a few compile errors or test failures on the first CI run. Send the new log and they are fixed one by one.

## How to upload (important)
Do not drag whole folders into the GitHub web page: it silently skips files (about 100 per upload) and was the cause of the empty repository.
Use `git` (or GitHub Desktop) and push, then check on GitHub that these exist:
`settings.gradle.kts`, `app/build.gradle.kts`, `app/src/main/java/com/almus/appcreatorpro/domain/build/` and `.../service/build/`.
