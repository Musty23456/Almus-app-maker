# Phase 11 report: advanced code editor

## Read this first
Phase 11 is **only partly delivered**, and nothing in it has been compiled or run.
- **No toolchain again** (no Gradle, Kotlin compiler, Android SDK or network, same as Phase 10). The 13 new Kotlin files and 121 new tests are **uncompiled**. A compile error in any of them breaks the whole Gradle build, so expect to fix some on the first CI run.
- **Only five new languages are visible in the app** (Python, TypeScript, Dart, YAML, SQL highlighting, through the existing highlighter). Everything else new is an **engine layer that the editor screen does not use yet**.
- **No editor library was adopted** and the editor screen is untouched. You asked for a library "where practical", and not to label an incomplete editor an advanced IDE: **this is not an advanced IDE yet**. It is the existing Compose text-field editor plus tested-by-design logic that a real surface can use.
- Nothing was tested with a real Gemini key. Phase 12 was not started.

## Why no editor library
The current surface is a Compose `BasicTextField` with a gutter. It cannot draw several carets, fold lines, or render large files efficiently, and no amount of engine code fixes that. A real fix is replacing the surface with an editor component. I did not do it blind: I could not download or compile any library, and swapping the surface touches the screen all existing editor features run through. A candidate to evaluate is the open-source Sora Editor for Android (View-based, would be hosted with `AndroidView`); **I have not verified its current feature list, license terms or API**, so treat it as a lead, not a decision. The engine classes are written against plain strings and `EditorValue`, so they work with either surface.

## The 23 features, honestly
Legend: **Existing** = was already in the app (seen in the code, not re-run). **Live** = new and reachable in the app (uncompiled). **Engine** = new logic and unit tests (uncompiled), not connected to the UI. **No** = not done.

| # | Feature | Status | Notes |
|---|---|---|---|
| 1 | Advanced syntax highlighting | Existing + Live | 8 old languages plus 5 new. Token based, not semantic, not TextMate |
| 2 | Auto-completion | Engine | `CompletionEngine`: keywords, snippets, words in file, project symbols |
| 3 | Code suggestions | Engine | Same engine; AI suggestions via `EditorAiPrompts` |
| 4 | Auto indentation | Existing | `CodeEditing` (not changed) |
| 5 | Bracket matching | Existing | `BracketMatcher` (not changed) |
| 6 | Code folding | Engine | `FoldingEngine`; no fold markers or hiding in the UI |
| 7 | Line numbers | Existing | Gutter in `CodeEditorSurface` |
| 8 | Find and replace | Existing + Engine | Single file exists; project-wide replace is `ProjectSearch.replaceAll` |
| 9 | Multiple file tabs | Existing | Tabs with an unsaved dot |
| 10 | Split-screen editing | Engine | `SplitLayout` state only; **no split UI** |
| 11 | Multiple cursors | Engine | `MultiCursor` operations; **the current text field cannot show several carets** |
| 12 | Go to line | Engine | `Navigation.parseLineTarget`; no dialog |
| 13 | Go to definition | Engine | `DefinitionFinder`, name based, may return several candidates |
| 14 | Code navigation | Engine | Definitions plus whole-word `ProjectSearch` as "find usages" |
| 15 | File outline | Engine | `OutlineEngine`; no outline sheet |
| 16 | Minimap | Engine | `MinimapModel` data only; recommended on wide screens only |
| 17 | Custom themes | Engine | `EditorThemes.custom` with hex colours and a contrast check; no screen, no storage |
| 18 | Dark and light modes | Existing + Engine | App uses Material light/dark; four new built-in editor themes are not applied |
| 19 | Undo and redo | Existing | `UndoHistory` |
| 20 | Automatic file saving | Existing | README (Phase 2) says edits are auto-saved when leaving the editor; I did not re-check it |
| 21 | Unsaved changes indicator | Existing | Dot before the tab name |
| 22 | Keyboard shortcuts | Engine | `ShortcutMap` defaults and rebinding; the screen handles only Tab / Shift+Tab |
| 23 | Search across project files | Engine | `ProjectSearch`; no search screen |

Languages (13): Kotlin, Java, XML, JSON, JavaScript, HTML, CSS, Markdown (existing); Python, TypeScript, Dart, YAML, SQL (new). Outline, folding and completion exist for each where it makes sense (for example JSON has keys but no completion). All are heuristics; see Limitations.

## AI integration
`ai/EditorAi.kt` (`EditorAiPrompts`) builds explain and suggest requests for the existing Phase 9 backend: file path, language, a window of 30 lines around the caret, at most 6000 characters of selection, the list of declarations in the file. Files that look like they hold secrets are never sent (`SecretScanner.isSensitivePath`) and secret-looking values are replaced locally (`SecretScanner.redact`). A suggestion is returned as text and `extractCode` reads the first complete fenced block; nothing is applied automatically. AI code-fence tags `python`, `ts`, `dart`, `yaml`, `sql` now map to the new languages in `ChatModels.languageFor`. **No button or screen uses it yet, and it was only tested against the fake backend in unit tests (not run).**

## Changes to existing files (small on purpose)
- `editor/Language.kt`: five enum entries and two flags (`caseInsensitiveKeywords`, `tripleSingleQuotes`) with defaults, plus file extensions. No existing `when` over `Language` exists (searched), so new entries should not break exhaustive checks, but this is **uncompiled**.
- `editor/SyntaxHighlighter.kt`: `'''` strings and case-insensitive keywords, both only active for the new languages.
- `ai/ChatModels.kt`: five more code-fence tags.
- `README.md`, `PHASE11_REPORT.md`. No Compose, ViewModel or manifest changes.

## Commands executed and results
```
python3 scripts/verify_offline.py .   -> kotlin files 274 (213 main / 61 test); @Test 931; problems: 0; all 3 workflows parse
cd backend && npm test                -> tests 35, pass 35, fail 0 (unchanged by Phase 11)
grep -c '@Test' on the new test files -> 28 + 26 + 26 + 30 + 11 = 121 new tests
Python re-implementation of the outline regexes (sanity only, not the Kotlin code) on the test inputs -> names matched the test expectations; the one mismatch
   (control words such as "if" matching the JS/Dart method rule) is filtered by the Kotlin `accept` check, which the Python run did not include
Python contrast calculation for the four built-in themes -> every text colour at least 3:1 and comments at least 2:1 on the background
```
Every other expectation in the tests was **checked by hand against the code, not by running it.** Hand checking found and fixed two wrong test expectations (a prefix cursor offset and the `SplitLayout.onClosed` replacement choice). More mistakes of that kind are likely.
**Not executed:** Gradle build, any Kotlin compilation, any JUnit test, Android lint, any UI.

## Limitations (honest list)
- Uncompiled: possible compile errors in 13 main files and 5 test files. Run CI and send me the log.
- Engine is not wired to the screen: users cannot yet open an outline, search the project, fold code, split the screen, use shortcuts beyond Tab, or ask the AI from the editor.
- Multi-cursor, folding, minimap and split view need a rendering surface the current text field cannot provide. That is the real Phase 11 remaining work.
- Completion is lexical: no types, no members after `.`, no imports. "Go to definition" matches by name only and finds nothing in libraries.
- Outline/folding are line and bracket heuristics: Java methods without a modifier, multi-line declarations and Python decorators are missed; Markdown/YAML/JSON "outline" is keys or headings.
- Performance is untested. The engines cap their work (files over 500,000 characters get no outline or folds, results are capped) but they are not incremental, so they should be run off the main thread and debounced.
- Themes are not persisted and not applied; shortcuts are not customisable in a screen; no accessibility review (TalkBack, large text) was done.
- The 5 new test files check the logic of the new classes only; none exercises the Compose UI.

## Suggested next step (still Phase 11, not started)
1. Run the Android Build workflow and fix compile/test errors in the new files (send me the log).
2. Decide the surface: evaluate an editor library against these needs (large files, folding, multi-cursor, soft keyboard, external keyboard), or extend the current one.
3. Wire the engines in this order: go to line and project search, outline sheet, completion bar, shortcuts, AI explain/suggest buttons, folding, split view, themes screen, minimap.
