# Phase 25 - Project Explorer

## Read this first (what was and was not verified)
- **Nothing was compiled or run.** This environment has no Kotlin compiler, Gradle, Android SDK or network. All new Kotlin
  (main and tests) is **uncompiled and the tests have never run**. Expect a few compile errors or failing tests on the first CI run
  (`./gradlew testDebugUnitTest assembleDebug`); send me the log and I fix them.
- What *was* run: `python3 scripts/verify_offline.py .` (bracket balance, package/folder match, unresolved imports). It reports **89 problems,
  the same 89 as before this phase** (missing `domain/build` and `service/build` folders, plus 2 known false alarms). None is in a new or changed file.
  It is not a compiler.
- Tests were checked by reading them against the code, not by running them. The Compose screens were never rendered.
- Phase 26 was not started.

## The 14 requirements
| # | Requirement | How |
|---|---|---|
| 1 | Hierarchical explorer | `ExplorerTree` builds folders-first rows with depth; `ui/editor/ProjectExplorer.kt` shows them in the drawer. |
| 2 | Folder navigation | Expand/collapse per folder; searching opens every folder that holds a match. |
| 3 | File search | `FileSearch`: every word must match; `.kt` / `*.xml` match extensions; name matches rank above path matches; fuzzy letters as last resort. |
| 4 | File creation | New file and **new folder** (a folder is kept alive by an empty `.gitkeep`, because the store only keeps folders that hold a file). |
| 5 | Delete with confirmation | `requestDelete` -> dialog showing what goes (count, size, unsaved tabs) -> `confirmDelete`. Works for files and folders. |
| 6 | Rename | Files and folders (rename or move). Open tabs, undo history and the recent list follow. |
| 7 | Duplicate | "Name copy.ext", "Name copy 2.ext"...; never overwrites; the copy opens. Files only (not folders). |
| 8 | Edit | Open in the existing editor (all Phase 11 editing unchanged). |
| 9 | Preview | Read-only sheet with line numbers and colors, cut at 1,500 lines / 60,000 characters; shows an open file's unsaved text; binary/too-large/non-UTF-8 files get an explanation. |
| 10 | Recent files | Newest first, 20 per project, saved in private preferences, dropped when the file disappears. |
| 11 | File type recognition | `FileTypes` / `FileKind`: Kotlin, Java, XML, JSON, YAML, Gradle, Python, JS, TS, HTML, CSS, Markdown (+ SQL, Dart, properties/TOML, text, images, binary). Badge on each row. |
| 12 | Structure visualization | "Structure" tab: totals, bars by type and by top-level folder, largest files, ASCII folder tree with Copy. |
| 13 | File size information | Per file, per folder (recursive) and per project. |
| 14 | Safe operations | See below. |

Syntax highlighting reuses the existing `SyntaxHighlighter` for every listed language; Markdown (which had none) gets a small new `MarkdownHighlighter` used in the preview only.

## Safety rules
- **Workspace containment, two layers.** `FileProjectStore` (unchanged) already rejects `..`, absolute paths and anything whose canonical path leaves the project.
  `ExplorerOperations` additionally validates every path with `FilePathValidator` first. A leading `/` is treated as relative to the project, not as an absolute path.
- **Important files** (`ProtectedFiles`): build.gradle(.kts), settings.gradle(.kts), gradle.properties, AndroidManifest.xml, proguard/consumer rules,
  libs.versions.toml, gradle-wrapper.properties, `.github/workflows/*.yml`, and in the project root package.json, tsconfig.json, pubspec.yaml, pom.xml, requirements.txt, pyproject.toml.
  Deleting one (or a folder containing one) needs its **name typed in**; renaming/moving needs an extra confirmation. They show a lock icon.
- `EditorViewModel.deleteFile(path)` (old API) now **refuses** important files; only the confirmation flow can delete them.
- Nothing is overwritten (create, duplicate, rename all refuse an existing target). Multi-file changes are checked completely **before** the first change.
- Folder rename that fails halfway moves the already-moved files back. Folder delete first backs up every file in memory; if a delete fails, the deleted files are restored;
  a folder holding a file that cannot be read as UTF-8 text is **not deleted** (delete its files one by one).
- Rollbacks also run when the coroutine is cancelled.

## Files
New (`app/src/main/java/com/almus/appcreatorpro/explorer/`): `FileKinds`, `ProtectedFiles`, `PathOps`, `FileSearch`, `ExplorerTree`, `RecentFiles`, `ProjectStructure`, `MarkdownHighlighter`, `FilePreview`, `ExplorerOperations`.
New UI: `ui/editor/ProjectExplorer.kt`, `ui/editor/FilePreviewSheet.kt`.
Changed: `ui/editor/EditorViewModel.kt` (new optional constructor parameter, new state fields and functions; `createFile`/`renameFile` now go through `ExplorerOperations`, same messages),
`ui/editor/EditorScreen.kt` (drawer, dialogs, preview sheet), `di/AppContainer.kt`, `ui/AppViewModelProvider.kt`, `README.md`.
Not removed: `FileExplorer.kt` and `editor/FileTree.kt` stay (now unused by the screen; `FileTreeTest` still covers `FileTree`).
Tests (`app/src/test/.../explorer/`): `ExplorerCoreTest` (pure logic), `ExplorerOperationsTest` (real `FileProjectStore`, rollback with a failing repository, containment), `Phase25EditorViewModelTest`.

## Known limits
- Uncompiled / unrendered (see top).
- Folders cannot be duplicated; there is no drag-and-drop move; no content search (the editor's project search from Phase 11 does that).
- Empty folders exist only through `.gitkeep`.
- Renaming/deleting through the explorer does not touch other references (imports, manifest entries, build scripts).
- Preview is plain text; images and other binaries show file facts only. No JSON pretty-printing or rendered Markdown.
- Editor and AI code that write files directly (Phase 23 repair, Phase 24 apply) are not routed through the protection rules; they have their own review step.
- The "important files" list is name-based and may miss project-specific files.
