# Phase 22 - Advanced error diagnosis

The Phase 8 debugger now diagnoses build failures: it classifies errors, separates root causes from the errors that follow
from them, explains why they happen, names affected files and lines, suggests solutions and states how sure it is. It
reuses the existing AI architecture (`AiDebugProvider`, `AiDebugController`, the Gemini backend proxy).

## What it covers
Gradle, Kotlin, Java, Android SDK, dependency conflicts, manifest, XML, resource linking, network-related build failures and
GitHub Actions errors (workflow syntax, missing actions, permissions, disk full, runner lost, timeouts, exit codes, git auth,
setup-version problems).

## How it works
Local, offline, rule based (`debug/DiagnosisEngine.kt` and friends):
1. Classification (`DiagnosisCategory`) and severity (`SeverityLevel`: critical to info).
2. Root cause vs secondary vs informational. A secondary error carries a `CausalLink` with the evidence that links it to
   its root (e.g. network failure -> dependency not found; resource error -> unresolved `R`; syntax error -> cascade in the same file).
3. Stack trace analysis (`StackTraceAnalyzer`): Java/Kotlin/Gradle/Logcat traces, "Caused by", "... N more", project frames.
4. Affected files and lines, each labelled by basis: from the log, from a stack trace, or INFERRED.
5. "Why it happens" text and a suggested solution per error kind; network failures are interpreted (404, 401/403, 429, 5xx, TLS, DNS, timeout, reset).
6. Confidence (high/medium/low, 0-100, with reasons). Unrecognised messages get low confidence and are marked
   "insufficient evidence"; a truncated log lowers confidence and is disclosed.

AI (optional, only after the user confirms what is sent):
- New task `DIAGNOSE` returns the eight sections: Error Summary, Error Category, Root Cause, Affected Files, Technical
  Explanation, Recommended Solution, Suggested Code Changes, Confidence Level.
- The AI's claims are checked: file paths that do not exist are flagged unverified, code suggestions are labelled "not checked",
  and the stated confidence is calibrated against the local evidence (at most one step above it; "insufficient" is always
  honoured; no named root cause caps at medium; no stated confidence shows the local level and says so).
- Follow-up questions (`debug_followup`): reuse only the already confirmed log excerpt and snippets, never new source code.
  One question at a time, cancellable, retryable, never stuck "pending".
- Everything sent is redacted and length bounded. Backend prompts carry evidence-only rules (no invented errors, no false certainty).

## UI
Debug screen has a new **Diagnosis** tab: the eight sections, secondary errors, limitations, an "Open" button for files that
really exist, "Diagnose with AI", "Copy report" (Markdown) and a follow-up question box.

## Tests
- `DiagnosisEngineTest` (39), `AiDiagnosisTest` (19), plus the existing debug tests: 166 Kotlin tests pass.
- `backend/test/phase22.test.js`: backend suite 53 tests pass.

## Honest limits
- The Compose UI (`DebugScreen.kt`, `DebugViewModel.kt`), `GeminiDebugProvider` and OkHttp-dependent `ai/` code could not be
  compiled in the build environment (no Android SDK, no network). They were written against the existing code but have not been
  built or run; please run `./gradlew testDebugUnitTest` and a debug build.
- Diagnosis is based on the log text. When the log is truncated or the message is unfamiliar, the report says so instead of guessing.
- The tab renders only what the analysis found; it never proves that a suggested fix works.
