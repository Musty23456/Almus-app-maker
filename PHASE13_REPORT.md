# Phase 13 report: AI autonomous development agent

## Read this first

- **The Kotlin is uncompiled and the new unit tests have not been run.** This environment has Java but no Gradle, Kotlin compiler, Android SDK or network (same as Phases 10 and 11). The 13 new engine files, the 3 new UI files, the edits to existing files and the 107 new Kotlin tests have only been checked by reading and by two scripts (imports resolve, brackets balance, `scripts/verify_offline.py` reports 0 problems). Expect some compile errors on the first CI run.
- **What did run:** the backend. `npm test` passes **42 of 42** (35 existing + 7 new in `backend/test/phase13.test.js`).
- **Nothing was tried against real Gemini.** The three new prompts (`agent_plan`, `agent_task`, `agent_fix`) are untested against the model, so plan quality, JSON discipline and how often the agent needs retries are unknown.
- **The agent cannot run unit tests on the phone.** Step 6 "run available tests" is a structural check of test files, not a test run (details below).
- Phase 12 was skipped as you asked. Phase 14 was not started.

## How it works

You describe a goal. The agent plans, **you approve the plan**, it works task by task, checks its own output, and shows you every file change. **Nothing is written to your project until you tap Apply**, and applying goes through the existing change manager, so it is recorded and can be rolled back.

| Step | What happens | Where |
|---|---|---|
| 1-3 Understand, plan, break into tasks | One `agent_plan` request returns the restated requirements, assumptions, architecture, dependency advice, risks and up to 15 tasks. Plan is shown; you can remove tasks, ask for a revised plan, or approve. | `AgentPlanner`, `AgentPlanParser` |
| 4 Generate or modify files | Each task is one `agent_task` request with project-aware context, task memory and your notes. The model may call read-only tools first. Results go to a staging overlay. | `AgentRunner.runTask/converse`, `StagedProjectFiles` |
| 5 Validate | File rules (safe paths, no secrets, no duplicates, no file/folder clashes), then the Phase 8 static checks over the whole merged project; only problems in files the agent touched are reported. | `AgentValidator` |
| 6 Tests | Structural check of test files (see limits). | `StaticTestRunner` |
| 7 Analyze failures | Errors grouped per file; fixable if the file is in the project. | `AgentFailureAnalyzer` |
| 8 Corrections | Up to 2 `agent_fix` rounds, applied to staging only. A round that increases the error count is undone. With auto-correct off, you press "Fix problems". | `AgentRunner.correctionLoop` |
| 9 Build preparation | Text checks of the merged project (settings includes `:app`, namespace, compileSdk, manifest exported flags, AndroidX flag, Compose plugin, Room+KSP, no secrets, web `index.html`). | `BuildPreparation` |
| 10 Report | Markdown report: goal, tasks, files, dependencies, checks, corrections, readiness, refused changes, request count. Copyable. | `AgentReportBuilder` |

## The 16 features, honestly

1. **Natural language project creation**: done. `AgentNewProjectScreen` plans first; the project is created only after you approve the plan, then the agent starts in it (`AgentProjectLauncher`). The existing one-shot generator (Phase 9) is unchanged.
2. **Architecture planning**: done, as part of the plan (understanding, architecture, risks).
3. **Folder generation**: partial by design. The project store is path-based, so a folder exists when its first file does; empty folders cannot be created. The scaffold task creates files in the new folders.
4. **Source code generation**: done (`code` task kind).
5. **Multi-file editing**: done. A task can change several files; `edits` (find/replace) or full `content`.
6. **Dependency recommendations**: done as advice (plan and task results, shown in the plan and report). A `dependencies` task also edits the module build file as a staged change. Nothing is added without your Apply.
7. **API integration assistance**: done as a task kind with guidance (network layer, INTERNET permission, no keys in code). Not tested against a real API.
8. **Database schema generation**: done as a task kind (Room entities/DAOs/database, plugins). The KSP/Room readiness check catches a common miss.
9. **UI generation**: done as a task kind (Compose or XML, plus the resources they reference).
10. **Code explanation**: done (`explain` task; answers only, never changes files). The Phase 9 chat still exists for ad-hoc questions.
11. **Error detection**: done in steps 5-7 with the existing static checks. These are source-level heuristics, not a compiler; a clean result does not prove the app builds.
12. **Automated debugging assistance**: partial. `agent_fix` corrects problems the checks find, and the workspace links to the existing build debugger. Build logs from a failed cloud build are **not** fed to the agent automatically.
13. **Refactoring**: done (`refactor` task kind).
14. **Documentation**: done (`docs` task kind, Markdown).
15. **Build preparation**: done as a readiness checklist. It does not start a build; the report points to the Cloud build screen.
16. **Test generation**: done (`tests` task kind writes JUnit 4 tests and the dependency). They are not executed (see limits).

## AI capabilities

- **Project-aware, multi-file context**: reuses `ProjectContextBuilder` over the staged view, so earlier tasks' files are visible to later ones. Same secret redaction and file caps as chat (12 files, 40k chars each, 150k total).
- **Conversation history and task memory**: the goal and your notes are sent with every request; finished tasks' summaries and file lists are sent as memory. Both are persisted.
- **Tool calling**: the model can answer with `tool_calls` (`list_files`, `read_file`, `search`), the agent runs them and replies in the next message. Max 3 rounds per task, 12 file reads per task. All read-only; secret files, build output and binaries are refused.
- **Progress reporting / streaming**: step label, task checklist with progress bar, request count, and the live tail (last 300 characters, secrets removed) of the streamed answer.
- **Cancellation**: cancels the in-flight request; work done so far is kept and the run can be resumed.
- **Retry handling**: transient errors retried twice with 1 s / 2 s waits (server `retry-after` honoured, capped at 30 s); unusable or cut-off answers asked again once with a hint to send fewer files; a failed task can be retried or skipped (dependent tasks are then skipped); a hard cap of 60 requests per run.
- **Persistence**: plan, task states, notes, log, staged files and counters are saved per project. If the app is closed mid-run, the run comes back as "cancelled" and can be resumed. Staged files whose real counterpart changed meanwhile are dropped on restore.

## Safety

| Requirement | How it is enforced |
|---|---|
| Never overwrite user files without approval | The agent only reads the project. It writes to an in-memory overlay (`StagedProjectFiles`); a test asserts the fake project received zero writes before Apply. Apply is one tap in the Files tab, with a destructive confirmation when existing files would be replaced. |
| Show proposed changes, provide diffs | Files tab: every file with before/after line diff (reuses the Phase 9 components). Per-file selection. |
| Support rollback | Apply goes through `AiChangeManager`; "Roll back this run" uses its undo (refuses if you edited the files since). |
| Approval before destructive operations | The agent cannot delete at all (`StagedProjectFiles.delete` throws, a `delete` action from the model is refused and reported). Overwrites need your confirmation. The plan itself needs approval before step 4. |
| No blind overwrites | An existing file can only be changed if the model was shown it in this exchange (context or a `read_file`). A file shown only in part can only be changed with targeted edits. |
| Stale protection | If a real file changed after the agent staged its version, Apply writes nothing and says which files. |
| Never expose API keys | Secrets are redacted before anything is sent; a new secret in model output is refused; log and live output are redacted; the app never sees the Gemini key (it stays in the backend). Secret files cannot be read by tools. |
| Limit agent permissions | `AgentPermissions`: max tasks, files, bytes, tool rounds, reads, retries, correction rounds, requests; read-only tool allow-list; unsafe paths refused. Delete is not a setting, it is not possible. |

## Provider architecture

The agent depends only on the `AiBackend` interface. `AiProviders.kt` adds a small registry (`AiProvider`, `AiProviderRegistry`); `AiHub` takes the agent's backend from `providers.default()`. Today only Gemini (through the ALMUS backend) is registered. Adding a provider means implementing `AiBackend` and registering it; there is no UI to choose a provider because there is only one. A test runs the agent on a second fake provider.

## Changes to existing files (small on purpose)

- `ai/AiModels.kt`: three `AiTaskKind` entries.
- `backend/src/prompts.js`, `backend/package.json` (version, test script): three tasks; new `backend/test/phase13.test.js`.
- `service/ai/AiHub.kt`: `agent(projectId)`, `newAgentLauncher()`, `providers`, `agentStore` (both with defaults, so existing callers and tests are unaffected). It calls `session.init()` first so the shared change history is loaded before the agent can apply.
- `service/ai/AiFileAdapters.kt`: `FileAgentRunStore`.
- `di/AppContainer.kt`, `ui/AppViewModelProvider.kt`, `ui/navigation/Routes.kt`, `ui/navigation/AlmusApp.kt`: wiring and two routes (`agent/{projectId}`, `agentnew`).
- `ui/ai/AiAssistantScreen.kt`, `ui/ai/AiGeneratorScreen.kt`: an "Agent" button (new parameter with a default). `ui/ai/AiChangesTab.kt`: `ChangeFileRow` and `DiffView` changed from `private` to `internal` for reuse.

## Commands executed and results

| Command | Result |
|---|---|
| `cd backend && npm test` | 42 pass, 0 fail (35 before, 7 new) |
| `python3 scripts/verify_offline.py` | 299 Kotlin files (230 main, 69 test), 1041 `@Test` methods (+110), **0 problems**, workflow YAML valid |
| Import/bracket check on the 34 new or changed Kotlin files | all agent/ui/ai imports resolve; brackets balanced. It also lists two imports in the existing `AppContainer.kt` (`ArtifactInspector`, `WorkflowTemplateProvider`) that my loose script cannot match; I did not touch them. |
| Gradle build, Kotlin compile, Kotlin unit tests, instrumented tests | **Not run** (no toolchain) |

New Kotlin tests (107): parsers 10, staging/guard/tools 16, model client 7, validation/readiness/report 21, runner scenarios 35, persistence 9, launcher/provider 9. They cover the approval gate, the ten steps, "project untouched until Apply", apply/rollback/stale, retry/skip/cancel/resume, request budget, tool-call rounds, blind-overwrite refusal, secret handling, correction/undo-if-worse, restore after restart.

## Limitations (honest list)

- **Uncompiled and unrun Kotlin**, as above. The UI (`AgentWorkspaceScreen`, `AgentNewProjectScreen`), the ViewModels and the `AiHub` additions have **no tests at all**; they were written against the existing screens' patterns and read carefully, nothing more.
- **Tests are not executed.** Unit tests need Gradle and a JDK, which the phone does not have, and the cloud build in this app builds an APK (assemble tasks), not the unit tests. `StaticTestRunner` only checks that test files contain `@Test` functions and that a JUnit dependency exists, and says "Tests were NOT run" in the log and report. `AgentTestRunner` is an interface, so a real runner (for example a cloud job running `testDebugUnitTest`) can be plugged in later.
- **Validation is heuristic.** The checks are the Phase 8 source analysis, not a compiler. A "ready" report can still fail the cloud build.
- **Quality depends on Gemini and on prompts nobody has tried yet.** The blind-overwrite rule is strict on purpose; if the model forgets to read a file it wants to change, the task fails with a clear reason and can be retried (the retry prompt includes the reason). Expect some of that early on.
- **Context is capped** (12 files / 150k characters per request). On large projects the agent sees only what the plan paths, dependencies, keyword matching or its own `read_file` calls select.
- **Tasks run one after another.** A run can cost up to 60 requests; there is no cost estimate.
- **Steps 1-3 are one request**, not three; the step labels in the UI follow the workflow but the model is asked once.
- **Debugging from build logs is not automatic** (see feature 12).
- **Partial apply**: if you apply only some files, the run counts as applied; unselected files are not kept for a later apply.
- **New projects start from the template files**; the scaffold task is shown them so it may change them, other task kinds only change files they were shown.
- **Restored runs recompute checks**; the report of a restored applied run is the saved text only.
- One `agent_fix` prompt change is untested in the backend beyond shape tests (the backend test checks the prompts' structure and the request plumbing, not model behaviour).

## Suggested next step (not started)

Compile on CI and fix the first round of errors; try the agent on a small real project with a real Gemini key and tune the three prompts from what actually comes back. Phase 14 was not started.
