# Phase 28 - AI Repository Workspace

## Read this first
- **Nothing here was compiled or run.** No Kotlin compiler, Gradle, Android SDK or network is available here (same as earlier phases).
  Expect a few compile errors or test failures on the first CI run (`./gradlew testDebugUnitTest assembleDebug`); send me the log and I fix them.
- What I did run: a bracket-balance check and an import-resolution check over every new/changed file. The only unresolved imports are
  `service/build/*` (the folder missing from the zip since Phase 23) and three Kotlin extension helpers my checker cannot see.
- The Compose screens were never rendered. The AI was not tested against the real backend.
- Phase 36 was not started.

## What it does
Projects screen -> **GitHub** button -> **My GitHub Projects**
1. Repositories tab: your repositories (private + public when signed in), local filter, **Search GitHub** (public text search), or paste `owner/repo` / a github.com link.
2. Tap a repository: information (visibility, language, stars, size, last push, default branch, branch count), **branch picker**, **last commit**.
3. **Import and open workspace**: downloads that branch's newest commit (by commit id), unpacks it with the existing import checks, creates a normal local project, and opens the AI conversation.
4. Workspaces tab + "Recent" list = recent repository history. **Switch** menu in the workspace screen = project switching.
5. In the workspace: **Check for updates / Update local copy** (confirm first, safety backup first), Files, Agent, AI settings, Clear chat.

## The approval rule (how it is enforced)
- The AI chat (`WorkspaceChatEngine`) is built with only a backend, a chat-history store and a read-only context function. It has **no file writer, change manager or agent**, so it cannot modify a project.
- Flow: "I want to improve the UI" -> AI says what it will inspect and asks 2-4 questions -> after your answers it writes a plan between `<<<PLAN>>>` markers -> you press **Approve plan** -> **Start with Agent** hands the approved plan to the existing Agent.
- Approval only adds a line to the conversation. A plan counts only if both markers are present (a cut-off plan cannot be approved). A new message after approval cancels it. Typing the approval sentence by hand does nothing.
- From reading the Agent code: it plans, waits for its own approval, stages changes and shows them for review before writing. I did not run it.

## Private repositories and tokens
- Uses the existing `GitHubAuthManager` (encrypted token) and `GitHubRepoDownloader`. The token is read only inside those classes and goes only to api.github.com in an Authorization header; the redirect to the storage host carries no token.
- Workspace records (`WorkspaceStore`) hold owner, repo, branch, commit, project id, times. **No token.** A test checks the stored text has no token field.
- Public repositories work **without signing in** (second API client, no Authorization header, GitHub's 60 requests/hour limit applies). If a saved token is rejected, public reads fall back to anonymous.
- The AI only receives files through `ProjectContextBuilder` (credential files skipped, secrets redacted).

## Files added
`workspace/`: WorkspaceModels, WorkspaceStore, WorkspaceGitHub, RepoWorkspaceManager, WorkspaceAnalyzer, WorkspacePlans, WorkspacePrompts, WorkspaceChatEngine, WorkspaceHub
`ui/workspace/`: MyGitHubProjectsScreen, MyGitHubProjectsViewModel, RepoWorkspaceScreen, RepoWorkspaceViewModel
Tests (`app/src/test/.../workspace/`): WorkspaceCoreTest, WorkspaceChatEngineTest, PublicAccessTest

## Files changed (small)
- `domain/github/GitHubModels.kt`: `GitHubRepo` gets optional language, stars, updatedAtIso, sizeKb (defaults, old code unaffected)
- `domain/github/GitHubApi.kt`: `searchRepositories` with a default body (test fakes still compile)
- `service/github/GitHubApiImpl.kt`: `searchRepositories`, `allowAnonymousReads` (default false), reads the new repo fields
- `service/transfer/GitHubRepoDownloader.kt`: `allowAnonymous` parameter (default false)
- `di/AppContainer.kt`, `ui/AppViewModelProvider.kt`, `ui/navigation/Routes.kt`, `ui/navigation/AlmusApp.kt`, `ui/projects/ProjectsScreen.kt` (GitHub button)

## Honest limits
- Only repositories the existing detector supports can be imported: native Android (Kotlin/Java) and HTML/CSS/JS. Others (Flutter, Python...) are refused with the detector's message.
- **Sync replaces local files**, including edits made on the phone. It asks first and saves a safety backup first; if the backup fails nothing is replaced.
- Branch list shows the first 100 branches. Search shows GitHub's first 1000 results.
- Whether GitHub search includes private repositories depends on the token's rights; the app does not promise it. Your own private repositories are always in the "My repositories" list.
- The "improve" conversation relies on the AI following the prompt rules; the hard guarantee is only that the chat cannot write files.
