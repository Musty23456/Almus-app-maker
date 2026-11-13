# Phase 24 - AI coding assistant upgrade ("Smart" chat mode)

## Takaitawa a Hausa
Sabon yanayin **Smart** a cikin AI Assistant (Chat) yana fahimtar abin da kake so (Hausa ko English), yana duba aikinka (language, framework, architecture, dependencies, salon rubutu) kafin ya rubuta code, kuma kafin a rubuta komai a files ɗinka, dole ne ka **duba ka amince** (Review & apply → Changes → Apply). Tsofaffin yanayin (Ask/Explain/Generate/Edit) ba su canza ba. **Ba a taɓa backend ba** (babu abin da za ka sake turawa a Render). Ba a fara Phase 25 ba.

## Read this first (what was and was not verified)
- **Compiled and run (new this phase):** the pure-Kotlin part of the AI package (everything in `ai/`, plus `editor/`, `debug/`, `domain/`, `repair/PatchValidator`) was compiled with the Kotlin 2.0.21 compiler (same version as the project) and **133 unit tests ran and passed**: the 86 AI tests that existed before + 47 new ones (`Phase24IntentTest`, `Phase24AssistantTest`). I also broke one rule on purpose to confirm the new tests fail when they should.
- **Not compiled:** the Compose screen (`ui/ai/AiAssistantScreen.kt`) and the ViewModel (`ui/ai/AiViewModels.kt`). Android/Compose libraries are not available here. They only use APIs already used in the same files, but expect possibly one small compile error on the first CI run; send me the log.
- The test harness used a small stand-in for `kotlinx-coroutines-test` (not available offline); your real CI uses the real library.
- `scripts/verify_offline.py` reports 89 problems: the same 87 as before (missing `domain/build` and `service/build` folders, which this upload does not contain) plus 2 "UNBALANCED" notes for `ProjectProfile.kt`. The 2 are false alarms: that script counts `{` and `(` inside string literals such as `endsWith("{")`; the real compiler accepted the file.
- Not tested with a real Gemini key. The Hausa sentences (suggestions, hints) were written by me; please read them and tell me what to change. Phase 25 was not started.

## The 12 requirements
| # | Requirement | How |
|---|---|---|
| 1 | Natural-language requests | New chat mode **Smart** (default). `IntentDetector` reads English, Hausa or a mix; hooked letters (ƙ ɗ ɓ ƴ) and plain ones (k d b y) are treated the same. A line under the box shows "Understood as: ..." before you send. |
| 2 | Code explanation | Intent EXPLAIN -> existing `explain` task + beginner-friendly rules. |
| 3 | Code generation | Intent GENERATE -> `generate_code` task; reuses the project's theme/helpers; ends with how to use it. |
| 4 | Code optimization | Intent OPTIMIZE: real inefficiencies only, behaviour unchanged, complete files back. |
| 5 | Bug identification | Intent FIND_BUGS: lists only real bugs (where / what / why / fix), never invents one, says what it cannot see. |
| 6 | Function generation | Intent FUNCTION (clear name, edge cases, KDoc, where it goes, usage example). |
| 7 | Class generation | Intent CLASS (right architecture layer, project's DI style, package folder). |
| 8 | UI component generation | Intent UI_COMPONENT: Compose (state hoisting, `Modifier`, Material 3, `@Preview`), XML views (layout + strings.xml + wiring) or web, chosen from the project profile. |
| 9 | API integration | Intent API_INTEGRATION: uses the HTTP/JSON library already in the project, models + service + repository, error handling, off-main-thread, INTERNET permission, no hard-coded keys. |
| 10 | Refactoring | Intent REFACTOR: structure only, behaviour unchanged, one line per change. |
| 11 | Follow-up conversations | `FollowUpResolver`: "make it blue", "now add a test", "kuma ƙara shi" re-attach the **current** text of the previous exchange's files (the screen says which, and the sent message is marked "carried over"). A short message that fits nothing continues the previous intent. |
| 12 | Context-aware suggestions | `SuggestionEngine`: starter chips from the project profile (Compose / XML / web, already has Retrofit or not), next-step chips after an answer, file-specific chips when files are attached; EN/HA switch; "Attach?" chips for files whose names match what you typed (also from Hausa words). Tapping only fills the box. A test checks that every suggestion is read back as the intent and language it was made for. |

## What the AI knows about the project (`ProjectProfile`)
Detected locally from build files, manifest and a small sample of source files (never secrets or hidden files): **language, UI toolkit (Compose / XML / web), base package, SDK versions, architecture (MVVM + StateFlow/LiveData, Repository, UseCases, Hilt/Koin, Navigation, Room), package folders, known libraries, Gradle dependencies, conventions (indent, trailing commas, line width, `_state` pattern, `modifier` parameter, string resources, view-id style, quote style)**. Only these derived facts are sent, never file text beyond what you attach. Cached for 2 minutes and rebuilt when the file list changes. If it fails, the request still goes out without it.

## Preview and approve
"Apply to file" is now **"Review & apply"**: a dialog lists every file of the answer (new / changed, +/- lines, before/after view, check results). Blocked files (unbalanced brackets, "..." placeholders, changed package line, secrets) cannot be ticked; a fragment that would replace much of a big file is warned about; big rewrites are warned, not blocked. Ticked files go to the **Changes** tab as one change; **Apply** there is a second explicit step, and Undo still works. The dialog writes nothing. A block now becomes a *new file* when the path does not exist yet, and `// File: path` as the first line of a block also names the file.

## Hausa and English
The answer follows the language of your message. For Hausa the prompt says: explain in simple Hausa, keep programming terms in English (function, class, ViewModel, build, error, ... list in `TechnicalTerms`), never translate code, identifiers, paths, library names or error messages; code comments stay English.

## Existing features are untouched
- Ask / Explain / Generate / Edit send exactly what they sent before (a test checks the request text and task). Editor AI, Review, Debugger, Repair, Changes, history format (old saved chats load fine) are unchanged.
- **The backend is not changed.** Smart mode uses the existing tasks (`chat`, `explain`, `generate_code`, `edit_code`); the intent rules travel in the request text. One file -> `edit_code`, several -> `generate_code`, bug finding -> `chat` (prose, not JSON).
- `ChatEngine` got one optional last parameter (`assistant`), so older callers compile unchanged.

## Files
New (`app/src/main/java/com/almus/appcreatorpro/ai/`): `AssistantLanguage.kt`, `CodingIntent.kt`, `ProjectProfile.kt`, `CodingPrompts.kt`, `FollowUp.kt`, `CodingSuggestions.kt`, `GeneratedCodeReview.kt`, `CodingAssistant.kt`.
Changed: `ChatModels.kt` (Smart mode, turn fields, builder prefix, `ChatApply`), `ChatEngine.kt`, `AiProjectSession.kt`, `ui/ai/AiAssistantScreen.kt`, `ui/ai/AiViewModels.kt`.
Tests (`app/src/test/.../ai/`): `Phase24IntentTest.kt` (12), `Phase24AssistantTest.kt` (35).

## Known limits
- Intent detection is rule-based: a very unusual phrasing can be misread. The "Understood as" line shows it before sending; pick Ask/Edit/etc. to override.
- Checks on generated code are text checks (brackets, XML/JSON, placeholders, package line), not a compiler. Only a build shows that it compiles.
