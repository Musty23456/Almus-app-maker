# Fix: AI Debugger crash (no Phase 22 work)

## Root cause (two independent defects, both reproduced on the JVM before the fix)

1. **Quadratic secret-redaction regex (freeze, then Android kills the app).**
   `LogRedactor` (`domain/github/BuildLogs.kt`) and `LogCleaner.location` (`debug/LogCleaner.kt`) started a scan at
   every word boundary inside a long dotted/dashed word, so the cost grew with the square of the line length.
   Measured on the original code: 5,000 chars = 1.2 s, 20,000 chars = 19.5 s, 40,000 chars = 79.5 s.
   `AiRequestBuilder.build` redacted every issue message, log line and code line without any length limit, and
   `DebugViewModel.prepareAi` ran it in `viewModelScope` (main thread). A real build log with one very long line
   (class path dump, minified file) froze the UI until Android closed the app.
2. **Unhandled exceptions escaped into `viewModelScope`.**
   `GeminiDebugProvider` caught only `CancellationException` and `AiException`. Anything else (a network/keystore/JSON
   runtime exception, a failure while checking the answer's patches in `DebugSession.askAi`) propagated into
   `viewModelScope.launch { ... }` in `DebugViewModel.sendAi`, which had no handler. An uncaught exception there
   reaches Android's default handler and ends the process. Reproduced: a backend that throws
   `GeneralSecurityException` made `askAi` throw out to the caller.

## What changed

| File | Change |
|---|---|
| `domain/github/BuildLogs.kt` | Secret-name regex anchored with a look-behind: linear time, same output. |
| `debug/LogCleaner.kt` | Same fix for the `file.kt:line:col` pattern. |
| `debug/AiDebug.kt` | `AiResult.Failed.cause`; per-line/per-message caps and redaction input cap; empty / oversized answer handling; max 20 patches; one bad patch cannot sink the others. |
| `debug/AiSafety.kt` (new) | `AiFailures`: exception to friendly message, `isRecoverable` (never hides cancellation or broken-app Errors). |
| `debug/LogLimits.kt` (new) | Very large logs keep the tail (8 MB max); lines over 20,000 chars are shortened, with a note. |
| `debug/DebugSession.kt` | `askAi` never throws (only cancellation); log analysis runs on `Dispatchers.Default`; spinners always reset, also on cancel; `errorSink` for every caught exception; `postMessage`. |
| `debug/AiDebugController.kt` (new) | One request at a time, cancel, retry, 180 s deadline, state always recovers. `AiUi` moved here and gained `Preparing`, `Working(request)`, `Done(outcome, request)`. |
| `ai/GeminiDebugProvider.kt` | Catches every recoverable failure; empty / cut-off / malformed answers have their own messages; "wait N seconds" hint. |
| `service/ai/GeminiBackendClient.kt` | HTTP 408 and 504 map to a retryable timeout. |
| `ui/debug/DebugViewModel.kt` | Uses the controller; `CoroutineExceptionHandler` on every launch (logs the stack trace and shows a message); cancels on clear. |
| `ui/debug/DebugScreen.kt` | AI card: Cancel while working, Retry and Close after a failure, task buttons disabled while busy. |

Approval workflow is unchanged: an AI answer only adds PENDING suggestions; files change only when the user presses Apply.
Nothing is swallowed: the user sees a message, the stack trace goes to Logcat (`AiDebug` tag / `System.err`).

## Tests
New: `debug/AiDebuggerCrashFixTest.kt` (26 tests), `ai/GeminiDebugProviderHardeningTest.kt` (13 tests incl. the real
`GeminiBackendClient` with scripted HTTP / SSE errors). Extended: `BackendClientTest` (408/504).

## Verification performed
See the chat summary: standalone Kotlin 2.0.21 compile of all platform-independent sources, 177 JVM tests green.
Not run here (no Android SDK, no network): the Gradle Android build, `DebugScreen.kt` (Compose) compile, tests that need `kotlinx-coroutines-test`.
