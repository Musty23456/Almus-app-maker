# Phase 9 report: Gemini AI integration

## 1. Summary
ALMUS APP CREATOR can now chat with Gemini, generate projects from a description (with a reviewable plan), edit existing projects, debug build errors and review code. Gemini is reached **only through a backend you run**; the Android app holds no Gemini key. Nothing the AI produces is written to a project without validation, a before/after preview and an explicit **Apply**, and every applied change can be undone. No Phase 10 work was started. Nothing was pushed to GitHub.

## 2. Verification status (read this first)
| Part | Status |
|---|---|
| Backend (`backend/`) | **Run and tested here.** 30 tests pass (`npm test`) against a mock Gemini server: connection, single and streaming generation (incrementally, not buffered), cancellation, retries, every error mapping, auth, rate limits, validation, redaction, log/response leak checks. A mutation check (breaking retry logic, redaction, streaming on purpose) made 1, 1 and 3 tests fail respectively, so the tests do detect those regressions. The real process was also started and probed (health, wrong code, no token). |
| Android Kotlin (`ai`, `service/ai`, `ui/ai`, edits to existing files) | **NOT compiled, NOT run.** This environment has no Kotlin compiler, Gradle, Android SDK or network. What was done instead: carefully reviewed against the real Phase 8 APIs (several wrong assumptions were found and fixed this way), bracket balance and import resolution scripts over all new files, and the security-critical regexes (secret scanner, file-label, dependency extraction) plus the line-diff algorithm were ported to Java and executed on the JVM (all checks pass). |
| Android unit tests (~110, `app/src/test/.../ai`) | **Written, not run.** Expect to fix a few compile or assertion mistakes on the first run; they are written to catch real problems (see section 5). |
| Compose screens, navigation, Gradle/AGP | **Not compiled.** Like Phases 6-8, build once with GitHub Actions: `./gradlew testDebugUnitTest assembleDebug`. |

**Important finding about the uploaded ZIP:** `Almus-app-maker-phase8.zip` does not contain the `domain/build` and `service/build` packages (BuildService, BuildDetailsService, CloudBuildOrchestrator, signing/build-configuration models, ...) although `AppContainer`, `DebugHub` and others import them. I did **not** recreate them (that would risk replacing your real code). Phase 9 only uses them through the existing `DebugHub` constructor. Make sure your repository has them; a ZIP tool or filter that skips folders named `build` is the likely cause.

## 3. What was built
**Backend** (Node.js >= 18.17, no dependencies): `server.js` (routes, limits, SSE), `gemini.js` (upstream client, retries, error mapping), `auth.js` (HMAC tokens, constant-time access-code check), `rateLimit.js`, `redact.js`, `prompts.js` (reviewed instructions per task), `validate.js`, `config.js` (fails fast, never prints secrets), `Dockerfile`, `.env.example`, `docs/API_CONFIGURATION.md`.

**Android**
- `ai/` (pure Kotlin): models and `AiBackend` contract; `SseEventReader`; `SecretScanner`; `AiSettingsStore` + `BackendUrlValidator`; `ProjectContext` (controlled context, dependencies, suggester); `AiParsers` (plan, project, selection, proposal, review); `GeneratedFileValidator`; `LineDiff`; `Changes` (resolver); `ChangeManager` (apply/reject/undo/history); `ChatModels` + `ChatEngine`; `AiOperations`; `AiProjectSession`; `AiProjectGenerator`; `GeminiDebugProvider` (Phase 8 connection).
- `service/ai/`: `GeminiBackendClient` (login, token cache, one re-login, streaming), `PostStreamTransport` (OkHttp), `AiHub`, file-backed stores for chat and change history.
- `ui/ai/`: `AiAssistantScreen` (Chat/Edit/Review/Changes), `AiGeneratorScreen`, `AiSettingsScreen`, `CodeBlockView` (syntax highlighting with the editor's highlighter), view models.

## 4. Modified existing files (all small, additive)
`.gitignore` (backend `.env`), `app/build.gradle.kts` (version 10 / 0.9.0), `AndroidManifest.xml` (+ `res/xml/network_security_config.xml`: cleartext only for 10.0.2.2/localhost/127.0.0.1), `di/AppContainer.kt` (AI settings, backend client, hub; Gemini as Phase 8's AI provider), `ui/AppViewModelProvider.kt`, `ui/navigation/Routes.kt` + `AlmusApp.kt` (3 routes), `EditorScreen.kt` ("AI assistant" menu item), `SettingsScreen.kt` (AI card), `ProjectsScreen.kt` ("AI" button), `README.md`. No Phase 1-8 feature was removed.

## 5. Requirement checklist
| # | Requirement | Where / how |
|---|---|---|
| 1 | Secure backend, no key in app, auth, errors/rate limits, configurable models, streaming | `backend/`; app scans for keys in tests (`SettingsAndSecurityTest`) |
| 2 | AI chat: history, streaming, highlighting, copy, regenerate, clear, explain/generate/edit | `ChatEngine`, `AiAssistantScreen`, `CodeBlockView` |
| 3 | Project generator: plan, tech, structure, files, UI, dependencies, manifest, build-ready | `AiProjectGenerator`, backend `generate_project` rules, `GeneratedFileValidator` + Phase 8 `StaticDiagnostics` |
| 4 | Multi-file structured output, validated | `GeneratedProjectParser`, `GeneratedFileValidator` |
| 5 | Edit existing project: inspect, modify, preview, apply/reject | `AiProjectSession` edit flow, `ChangeResolver`, Changes tab |
| 6 | AI debugging via Phase 8 | `GeminiDebugProvider` plugged into `DebugHub` |
| 7 | Code review | `ReviewParser`, Review tab |
| 8 | Context awareness with controlled selection, no secrets | `ProjectContextBuilder`, `SecretScanner`, confirm-before-send list |
| 9 | Change management: preview, apply, reject, undo/restore, never silent | `AiChangeManager` (stale refusal, rollback, persisted history) |
| 10 | Planner reviewed before generating | `GeneratorFlow.PlanReady` (editable name/package, approve or skip) |
| 11 | Integrated professional UI | menu, settings, projects entry points; Material 3 screens |

## 6. Test report
- Backend: 30/30 pass (`cd backend && npm test`), verified including mutation check.
- Android: ~110 tests in `BackendClientTest`, `SettingsAndSecurityTest`, `ParsersAndValidatorTest`, `ChangesTest`, `ChangeManagerTest`, `ChatTest`, `ContextTest`, `FlowsTest` covering: connection and token handling, streaming (events, early end, error event, mid-401), API failure mapping, single-file and multi-file generation, full project generation (plan -> review -> create -> undo), existing-project editing end to end (nothing written before Apply, only listed files sent, credential files never sent), debugging provider, security (no key/Google URL in sources, cleartext limits, access code storage, redaction). **Not executed here.**

## 7. Known limitations and honest caveats
1. Kotlin code is uncompiled (see section 2). Fix compile errors from the first CI run before trusting it.
2. Model names in `.env.example` are examples; check Google's current list. I could not call the real Gemini API here (no key, no network), so real-model behaviour (JSON compliance, quality of generated projects) is untested; the backend and app handle malformed or cut-off answers explicitly.
3. Generated projects are checked statically; they are not compiled. Expect the first cloud build to need fixes sometimes.
4. Rate limits and login lockouts are in memory: run one backend instance.
5. Everyone sharing an access code shares a rate limit; give each person their own `name=code`.
6. The AI cannot delete or rename files by design. The undo history is stored unencrypted in the app's private storage (backups are off).
7. Prompts you send (including the confirmed files) are processed by Google under your Gemini plan's terms; the backend does not store them.

## 8. Next step
Do not start Phase 10 until Phase 9 is verified: (1) run `backend` tests and try the backend with your real key; (2) build the app with GitHub Actions and fix compile/test errors; (3) try: Test connection, chat streaming, generate an app, an edit with Apply and Undo.
