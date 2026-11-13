# Phase 27 - Git integration

## Read this first (what was and was not verified)
- **Nothing was compiled or run.** This environment has no Kotlin compiler, Gradle, Android SDK, network, or JGit jar. All new Kotlin (main and tests)
  is **uncompiled and the tests have never run**. Expect a few compile errors or failing tests on the first CI run
  (`./gradlew testDebugUnitTest assembleDebug`); send me the log and I fix them. JGit method names were written from memory of the 5.13 API.
- What *was* run: `python3 scripts/verify_offline.py .` -> **89 problems, the same 89 as before** (none in a new or changed file). It is not a compiler.
- The Compose screens were never rendered. The release/R8 path (new JGit keep rules) was not run.
- Your upload is `phase25`; I treated this request as Phase 27 (you said not to go to 28) and did not implement anything for Phase 26.

## Requirements
| # | Requirement | Where |
|---|---|---|
| 1-3 | Init, clone, pull | `GitService.init / cloneRepository / pull`; clone makes a **new project** (staging -> `importStaged`, history moved to `<project>/git`) |
| 4 | Fetch | `GitService.fetch` (prunes deleted remote-tracking refs) |
| 5-7 | Create / switch / delete branches | switching a remote branch creates a tracking branch; delete re-checks merge state at execution time |
| 8-9 | History, changed files | paged log; per-commit file list; working-tree changes in the Changes tab |
| 10-12 | Stage, unstage, commit | single files or all; deletions staged correctly; works before the first commit |
| 13 | Push | never forced; non-fast-forward is refused with "pull first" |
| 14-15 | Merge, conflict detection | conflicts listed with region counts; Open / Resolved / Keep mine / Take theirs / Abort |
| 16 | Status | branch, upstream ahead/behind, staged/modified/new/conflicted, merge state |

## Credentials and tokens
- Reuses `GitHubAuthManager.tokenProvider`; nothing new stores a token.
- `GitHubTokenCredentialSource` answers only for `https://github.com` (not http, not look-alike hosts, not URLs with user info).
- `HostPinnedCredentialsProvider` answers only for that host, so a redirect to another server gets nothing.
- Addresses with user name/password/token are rejected; stored remote URLs are stripped of user info; `GitRedactor` scrubs GitHub tokens, URL credentials, `Authorization` and `token=` from every `GitException` message, and JGit causes are dropped.
- `GitSecretGuard`: staging keystores, `.env`, `local.properties`, private keys, GitHub/AWS/Google/Slack tokens or literal signing passwords blocks the commit until confirmed; findings show file + reason, never the value. New projects get a `.gitignore` excluding these.

## Confirmations (enforced in `GitService`)
Delete branch (typed name when unmerged), abort merge, keep-one-side resolution, push, commit-with-secrets. Without a matching `GitConfirmation` the service throws `CONFIRMATION_REQUIRED` with the text to show; the ViewModel shows the dialog and repeats the same operation.

## Existing functionality
- `FileProjectStore.listFiles` now ignores a root-level `.git` pointer file (JGit may create one); nothing else in the store changed.
- Export, backup, cloud build and the explorer only read `files/` and `extra/`; they never see `git/`. Deleting a project deletes its history.
- Editor menu gets a "Git" item (saves first); Projects menu gets "Clone repository"; new routes `git/{projectId}` and `gitclone`.

## Files
New: `git/` (`GitModels`, `GitErrors`, `GitRedactor`, `GitValidation`, `GitSecretGuard`, `GitConflicts`, `GitRisk`, `GitCredentials`, `GitEngine`, `JGitEngine`, `JGitCredentialsProvider`, `GitWorkspaces`, `GitService`), `ui/git/` (`GitScreen`, `GitViewModel`, `CloneScreen`, `CloneViewModel`).
Changed: `gradle/libs.versions.toml`, `app/build.gradle.kts` (JGit 5.13.3, packaging excludes), `app/proguard-rules.pro`, `di/AppContainer.kt`, `ui/AppViewModelProvider.kt`, `ui/navigation/{Routes,AlmusApp}.kt`, `ui/editor/EditorScreen.kt`, `ui/projects/ProjectsScreen.kt`, `data/storage/FileProjectStore.kt`, `README.md`.
Tests: `GitCoreTest` (validation, redaction, credentials, secrets, conflicts, risks), `JGitEngineTest` (real repos, local bare remote), `GitServiceTest` (confirmations, clean-tree rules, secrets, locking, push/pull), `GitViewModelTest`, one `FileProjectStoreTest` case.

## Known limits
- Uncompiled / unrendered / untested on a device (see top). Real GitHub network behavior (auth, scopes, protected branches) is untested; a GitHub App token needs Contents read/write on the repository, an OAuth token needs `repo`.
- Clone, fetch, pull and push over https only; no SSH. Credentials only for github.com; other hosts work for public repositories.
- Cloned binary / very large / odd-named files stay in `files/` (Git tracks them) instead of `extra/`; the editor cannot open them and a cloud build upload may fail on them.
- Git history is not included in ZIP export or backups (only `files/` and `extra/`), and `extra/` files are not versioned.
- Detached HEAD: viewing/switching works, committing and pushing are refused. No rebase, stash, tags, cherry-pick, force-push, discard-changes, submodules or LFS. Commit messages are one line in the UI.
- Merge/pull require no uncommitted tracked changes (untracked files are fine). Unsaved editor text is not seen by Git: the editor saves before opening the Git screen.
- After pull/merge/switch the editor re-reads changed files when it resumes (existing behavior); the project's "last updated" time is not bumped by Git operations.
- Service-level clone is not tested (the validator is https-only, so there is no local test remote); the engine-level clone is tested against a local bare repository.
- Phase 28 was not started.
