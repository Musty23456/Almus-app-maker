# Phase 23 - AI code repair engine

## Read this first
- **Nothing here was compiled or run.** This environment has no Kotlin compiler, Gradle, Android SDK or network (same as Phases 10-22).
  Every new Kotlin file and all new tests are **uncompiled**; expect a few compile errors or test failures on the first CI run
  (`./gradlew testDebugUnitTest assembleDebug`). Send me the log and I fix them.
- What *was* run: `python3 scripts/verify_offline.py .` (not a compiler). It reports no problem in any new file. It reports **87 problems that
  were already in the upload**: all are unresolved imports of `domain/build` and `service/build`. **The zip you uploaded does not contain those two
  folders** (an upload tool skips folders named `build`, as in the earlier fix report). They must be in your real repository; this zip does not
  contain them either, and nothing in Phase 23 touches them. I added a `.gitignore` (`!app/src/**/build/`) only because the upload had none.
- Not tested with a real Gemini key. Phase 24 was not started.

## Workflow
Build failure -> Diagnosis (Phase 22) -> Suggested repair -> Patch generation -> Patch validation -> **your approval** -> Apply -> Rebuild.
The new **Repair** tab in the debug screen walks through it. The last step is yours: *Rebuild* opens the cloud build screen; the app never starts a build by itself.

## The 12 requirements
| # | Requirement | How |
|---|---|---|
| 1 | Patches from diagnosed errors | `RepairEngine.plan(issue, suggestions)`. A patch exists only when a rule-based check or the AI produced a real line edit (`FixProposal`). With none, the plan says "no patch" and nothing is invented. The AI is asked through the existing confirm-first request (`AiTask.GENERATE_PATCH`), whose prompt now carries "change as little as possible / never guess" rules. |
| 2 | Identify affected files | `AffectedFileResolver`: files from the diagnosis (log / stack trace / inferred), checked against the real file list; files named only by a patch are marked *inferred*; non-project files are listed and never changed. |
| 3 | Read the original first | The engine reads each file from the real project, checks it still matches the suggestion (hash), and only then edits. |
| 4 | Minimal changes | Only the suggested line edits, merged when they do not overlap (rule-based wins an overlap, the other is reported). `PatchValidator` warns above 40 changed lines and **blocks** above 150 lines or ~80% of a file, and flags whitespace-only changes. |
| 5 | Validate syntax | `PatchValidator`: bracket balance (strings/comments ignored), XML well-formedness, JSON parse, no new placeholders (`...`), package line unchanged. **Compared with the original:** a problem the patch *introduces* blocks it; one that was already there only warns. These are text checks, not a compiler (the UI says so). |
| 6 | Preview original and modified | `RepairPreviews`: original text, modified text. |
| 7 | Line-by-line differences | Unified diff with old/new line numbers and a two-column view; unchanged stretches are collapsed. |
| 8 | Accept or reject | Per file, nothing is written by accepting. A blocked patch cannot be accepted. |
| 9 | Individual file changes | Only accepted files are written; the rest stay pending and can be applied later. |
| 10 | Rollback | `RepairSession.rollback` through the shared `AiChangeManager.undo`; a file edited after the repair blocks it (nothing written) unless you confirm, and then the current text is saved first. If the change history no longer has the repair, the saved originals are used. |
| 11 | Preserve originals | The original text of every file is saved (`FileRepairBackupStore`, one JSON per repair, never overwritten) **before** anything is written; if saving fails, nothing is written. |
| 12 | Record every change | `RepairLogEntry` per file for every apply, reject and rollback (hashes, origin, +/- lines, issue), kept on disk and shown in the tab. Applied changes also appear in the shared AI change history. |

## Safety rules (each has a test)
- Preparing, previewing and accepting write nothing. `apply()` with nothing accepted writes nothing.
- A file edited after the plan was made makes apply refuse (stale check by hash); a failed write puts earlier files back.
- The applied state is "**Applied, not verified**"; no message says the error is fixed.
- Reuses the existing staging overlay (`StagedProjectFiles`), `ChangeResolver`, `LineDiff` and `AiChangeManager`; no second write path.

## Files
New: `repair/` (RepairModels, PatchValidator, RepairPreview, RepairEngine, RepairSession, RepairLog), `service/repair/` (RepairHub, FileRepairStores), `ui/debug/RepairTab.kt`.
Changed (small): `FixManager` (markApplied/markPending), `DebugSession` (filesChangedExternally), `AiDebug` (patch prompt rules), `DebugScreen` (Repair tab, `onRebuild`), `DebugViewModel`, `AppViewModelProvider`, `AppContainer`, `AlmusApp`, `README`.
Tests (`app/src/test/.../repair/`, 61): `PatchValidatorTest`, `RepairEngineTest`, `RepairSessionTest` (application, rejection, rollback, stale, backup failure, write failure, restart), `RepairStoresAndPreviewTest`.

## Honest limits
- Uncompiled (see top). The Compose tab was never rendered.
- Validation is simple text checking: it cannot catch type or logic errors, so a validated patch can still fail to build.
- Rule-based fixes exist only where `StaticDiagnostics` has one; most build errors need the AI for a patch, and AI patches must match the file text exactly or they are dropped.
- Saved originals are not pruned automatically.
- No automatic "did it work" comparison after a rebuild; you read the new build's result.
