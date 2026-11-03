# Phase 8 report: debugging and error analysis

## Read this first
| Part | Status |
|---|---|
| `debug` package (analyzer, log engine, static checks, fix engine, AI-preparation layer, `DebugSession`, `RouteArgs`) | Pure Kotlin, **compiled with Kotlin 2.0.21 and tested: 82 JUnit tests, all pass** (JUnit 4.13.2, coroutines 1.6.4 on a plain JVM) |
| Compose screens (`ui/debug`), navigation, `DebugHub`, `AppContainer`/`AppViewModelProvider` wiring, `EditorViewModel.openAt/resyncFromDisk`, `EditorScreen`, `BuildStatusScreen` button, `BuildDetailsService.loadRawLog` | **Not compiled, not run.** Only checked for syntax errors; unresolved-reference errors are expected without the Android SDK. |
| Gradle / AGP build, Android runtime | **Not run** (no Android SDK or network where this was written). Same situation as Phases 6 and 7. |

Build once with GitHub Actions (`./gradlew testDebugUnitTest assembleDebug`) and fix any compile error before relying on the UI parts.
Phase 9 has not been started. Nothing was pushed to GitHub.

## What it does
1. **Build error analyzer**: Kotlin (K2, K1, kotlinc CLI), Java (javac with `symbol:` and column), Gradle, missing dependency, manifest (also merger),
   resource linking (AAPT), Android SDK configuration. Rule-based suggestions (not AI). Dedupe, caps, CI-path to project-path mapping.
2. **Error dashboard**: message, file, line, category, severity, suggestion; "Open in editor" goes to file, line and column.
3. **Log viewer**: levels, plain/regex search, copy, export .txt/.json via the system file picker; secrets redacted first.
4. **Static code diagnostics** (heuristics with confidence levels): see README. Not a compiler, not a runtime debugger.
5. **AI preparation** (`debug/AiDebug.kt`): request builder, prompt renderer, response parser, patch converters (search/replace, unified diff),
   `AiDebugProvider` interface, `NoAiProvider`. **No model is called, no AI answers exist in the app.** Nothing would be sent without the user pressing Send.
6. **Fix management**: preview (before/after), apply, reject, restore, undo; stale-file and edited-since-apply protection.
7. **Logcat**: only a port (`LogcatSource`, `UnavailableLogcatSource`, `LogcatLineParser`). Live Logcat is not available to a normal app for other apps.

## Tests and sample projects with intentional errors
`app/src/test/resources/phase8-samples/`: `kotlin-errors`, `java-errors` (the `build.log` / `syntax-build.log` / kotlinc logs are **real compiler output**),
`gradle-errors`, `resource-errors` (logs **written by hand** from known formats), `android-static` (a small Android project with intentional errors,
including a malformed XML file).
Verified: detection and categories, navigation targets (path exists, line correct, caret on that line), every offered fix applies and removes its issue,
undo restores, generated templates produce 0 errors, a stress run over a large real codebase was reduced to 0 false errors, redaction, filters, export.

## Not verified / honest limits
- Compose UI behaviour (tab layout, dialogs, file-picker export), `EditorViewModel.openAt` and `resyncFromDisk` (no test; they use the verified `SourceText.selectionFor`).
- Gradle/AAPT/manifest log formats may differ from real GitHub runs in details.
- Static checks can miss problems and can be wrong.
- Undo history is in memory only.

## Required next step
Run the GitHub Actions build, fix compile errors if any, then try on a phone: failed build > Analyze errors > Open in editor > Apply fix > Undo.
