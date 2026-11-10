# Phase 20 report: live app preview system

## Read this first

- **The Kotlin is uncompiled and the 132 new Kotlin tests have not been run.** This environment has Java but no Gradle, Kotlin compiler, Android SDK or network (same as Phases 10, 11 and 13). The 19 new engine files, 4 new UI/service files, the edits to existing files and the tests were checked by reading, by `scripts/verify_offline.py` (0 problems) and by a script that checks that imports resolve, brackets balance and no `break`/`continue` sits inside an inline lambda (Kotlin 2.0 does not allow that). Expect some compile errors on the first CI run.
- **What did run:** the backend. `npm test` passes **46 of 46** (42 before, 4 new in `backend/test/phase20.test.js`).
- **Nothing was tried on a device or with real Gemini.** The sandboxed WebView in particular (`SandboxedWebView`) is Android-only code that cannot be unit-tested, and I have not seen it run. Check it first on a real phone (see "What to verify on a device").
- **A WebView preview is not an Android runtime, and the app never pretends it is.** HTML/CSS/JS runs in a real web engine. Android XML layouts and Jetpack Compose are **not run**: ALMUS reads the source and draws an **approximation**, and every preview carries a label saying which of the two it is.

## The investigation: how to preview Android UI

| Approach | Fidelity | Works inside this app? | Status here |
|---|---|---|---|
| WebView | Web only | Yes | Used for HTML/CSS/JS only. Cannot render Android views or Compose. |
| Inflate the project's XML with `LayoutInflater` | Real views | No. The project's resources are not compiled into this app, and there is no aapt2 on the phone. | Not possible. |
| Run Compose code | Real | No. It needs the Kotlin compiler plus the Compose runtime and compile-time plugin. | Not possible. |
| **Source-level approximation** (parse the XML or Compose source, lay it out, draw boxes) | Low to medium: structure, sizes, text, simple widgets | Yes, instant, offline | **Implemented**, labelled "Approximation". |
| **Layoutlib in a cloud job** (the engine behind Android Studio's preview) via Paparazzi, Google's Compose Preview Screenshot Testing, or Roborazzi (Robolectric instead of Layoutlib) | High for Views and Compose, still not a device | Not on the phone (needs a JVM and SDK data). The app already runs cloud builds, so this is the practical route. | **Not implemented.** A readiness checker tells the user what the project still needs, and `NativeRenderProvider` is the extension point. The default says "not implemented yet". |
| Run the built app on a cloud emulator and screenshot it | Real runtime | Via CI only; slow (minutes) | Not implemented; described in the Native tab. |
| Install the built APK on the phone (existing Cloud build) | Real device | Yes | Already exists; the Native tab points to it. |

How I checked this: I read the Android developer documentation on Compose screenshot testing and several write-ups on Paparazzi and Roborazzi. What I take from them is that Paparazzi and Google's tool render with Layoutlib on a JVM without an emulator, that Google's tool is experimental and Compose-only, and that all of them need test source sets and Gradle plugin configuration in the project. I did not run any of these tools, so the cloud route is a design, not a tested feature. Versions and exact setup differ between releases and must be verified when it is built.

## The 10 features, honestly

1. **Live UI preview**: done for web (real engine) and as an approximation for XML and Compose. Not the Android runtime.
2. **HTML/CSS/JavaScript preview**: done. The project's text web files (html, css, js, json, svg...) are served from memory to a sandboxed WebView. Relative links between project pages work. **Binary files (png, jpg, fonts) are not available** to the preview; the build explains when a page refers to one.
3. **Responsive preview**: done. Free width slider (200 to 1400 dp), and the width breakpoints found in the project's CSS (`min-width`/`max-width` in px, em, rem) appear as one-tap chips. Pages are laid out at the real dp width and scaled to fit.
4. **Multiple screen sizes**: 8 generic device profiles (small phone to desktop) plus custom size, portrait and landscape, zoom (fit, 50, 75, 100 %), text scale (100 to 150 %). The sizes are typical logical sizes, not specific products.
5. **Android device frame previews**: a plain drawn frame (bezel, status bar with punch-hole or notch, navigation pill). No manufacturer artwork. The frame is cosmetic; it is not a device emulator.
6. **Light and dark mode**: done, as an **emulation**. The WebView has no per-page switch for `prefers-color-scheme`, so the preview rewrites a copy of the project's CSS (`(prefers-color-scheme: dark)` becomes always-true or always-false, `color-scheme` is resolved) and overrides `matchMedia` for that query. The project files are never changed. Limits: CSS built at run time is not rewritten, and complex `matchMedia` queries go to the real browser. For XML, `values-night` resources are applied in dark mode and a stand-in palette is used. Compose themes are not evaluated (a stand-in Material-like palette is used).
7. **Preview refresh**: manual refresh, plus auto-refresh. Auto-refresh **polls** a fingerprint of the previewable files every 1.5 s while the screen is open, so it also sees changes made elsewhere (the AI assistant, the agent). A new WebView is created for every refresh.
8. **Preview error reporting**: script errors (with file and line mapped back to the project), failed or blocked requests, 404s inside the project, a crashed or unresponsive web process, XML parse errors with line numbers, and a note for every widget or attribute the approximation cannot draw. Entries with a file and line open the editor at that position.
9. **Preview console**: shows page `console.*` output and the errors above, filter by level, copy, clear, capped at 500 entries, identical consecutive lines collapse, secret-looking values are redacted. There is **no REPL** (you cannot type JavaScript into the page).
10. **Design synchronization where supported**:
    - code to preview: auto-refresh (above);
    - preview to code: **tap to find the source**. For web pages (switch "tap an element to find its source") every body element in the preview copy carries its source file and line; for XML layouts and the Compose outline, a tap on a drawn view reports the source line. "Open in editor" jumps there.
    - **Not supported:** visual editing that writes code (drag and drop, a property panel), two-way live editing, and a split editor/preview view. The preview opens from the editor menu ("Live preview", which saves first), so unsaved edits appear after saving.

## How the approximations work

- `SimpleXml`: a small hand-written XML reader. It refuses `<!DOCTYPE` and never resolves entities, so a malicious layout cannot read files or reach the network.
- `ResourceTable`: strings, colors, dimens from `res/values*`; `values-night` in dark mode. Styles and themes are not evaluated.
- `XmlLayoutBuilder`: LinearLayout (weights, gravity, default horizontal orientation), FrameLayout, ScrollView, CardView, TextView, Button, EditText, ImageView, Switch, CheckBox, ProgressBar, Toolbar, `<include>`, data-binding roots, `tools:text`, `visibility`. ConstraintLayout supports parent and sibling start/end/top/bottom anchors and bias only; chains, guidelines, barriers and ratios are reported as unsupported. RelativeLayout, TableLayout and GridLayout are **stacked at the top-left** with a warning. RecyclerView and ListView are striped placeholders. Custom and unknown views are labelled placeholders.
- `ComposeOutlineBuilder` (with `KtLexer`): reads the nesting of well-known composables and a subset of modifiers. Project composables are expanded up to 4 levels with their parameters unknown (shown as ‹name›). `if`, `when`, `for`, `items` are **not evaluated**: everything inside is drawn once, as if every branch ran, and the preview says so. State, themes, shapes beyond rounded corners, animations and custom layouts are not handled.
- `LayoutEngine`: follows the shape of Android's measure and layout rules, but **text is measured with an estimate** (average glyph width), so wrapping and heights are approximate.

These are heuristics. A drawing that looks right does not mean the app looks right, and a missing widget does not mean the code is wrong.

## Sandbox: preview execution isolated from user files

The web preview is the only place where code from the project (or from the AI) actually runs. Layers:

1. **No JavaScript bridge.** `addJavascriptInterface` is never called, so a page cannot call into the app. Console and errors arrive through the WebView's console callback.
2. **One gate for every request** (`PreviewRequestPolicy`, unit-tested). The page lives on a virtual origin `https://preview.almus.invalid` (`.invalid` can never resolve). Only in-memory project files are served there. With the internet **off (default)**, every other URL is blocked: http, https, `file:`, `content:`, `intent:`, `javascript:` and all other schemes. Path traversal (`..`, encoded forms, backslashes, NUL) is refused. Only GET and HEAD work. `data:`/`blob:` subresources are allowed, never as the top-level page. A lookalike host such as `preview.almus.invalid.evil.com` is not served.
3. **Which files can enter at all** (`WebPreviewBuilder.isServable`): text web files only, never secret files (`.env`, keystores, `local.properties` and the like via the existing `SecretScanner`), never build output or tool folders. The preview reads the project through `PreviewFiles`, whose `write` and `delete` throw.
4. **Content-Security-Policy**, injected into each page and sent as a header: `default-src 'none'` with only the page's own origin (and `data:`/`blob:` for media), no frames, no objects, no forms, no `<base>`. The page's own `<base>` tags are removed.
5. **WebView settings:** file, content and file-URL access off, no cookies, no geolocation, no DOM database, mixed content blocked, cache off, permission requests denied, file choosers, new windows and downloads denied, `alert/confirm/prompt` never block, navigation outside the project blocked, system force-dark disabled so only the emulation applies.
6. **WebRTC is removed** by the injected script (the request gate and the CSP cannot stop it).
7. **Fresh WebView per refresh**, destroyed afterwards with storage and cookies cleared; an unresponsive page is terminated (API 29+); a crashed renderer is reported instead of crashing the app.
8. **Internet is an explicit, per-session choice**, off by default. Even when on: https only, standard port only, no IP addresses (any form), no `localhost`, `.local`, `.lan`, `.internal`, single-label names, no user-info URLs, GET only.

**What is not covered, plainly:**
- The WebView runs in the app's process; its renderer is a separate sandboxed Chromium process on modern Android, but there is no separate Android user or process for the preview.
- A public name that resolves to a private address (**DNS rebinding**) is not stopped when the internet is on, because the app does no DNS of its own. This is why internet is off by default.
- `shouldInterceptRequest` does not see WebSocket traffic; the CSP (`connect-src`) is what blocks it. I did not use `blockNetworkLoads` because I cannot test how it interacts with intercepted responses; if you want that extra layer, enable it and test on a device.
- Endless loops: the page can burn CPU until the WebView is terminated or refreshed.
- All of the above is design and code review; none of it has been tested against a real hostile page.

## Flutter compatibility

Flutter is prepared for, not supported. Projects with a `pubspec.yaml` show up as a preview target; the preview says "not available yet" instead of failing. The seams: `PreviewKind.FLUTTER`, `PreviewEngine` (register a new engine in `PreviewEngines` and nothing else changes) and `FlutterPreviewEngine`. The planned route, once a Flutter toolchain runs in a cloud job: a Flutter **web build served through the same sandbox** as web pages, or golden screenshots from `flutter test` shown as images. Neither exists. There is no Flutter project type in the app yet (`ProjectType` has Android Kotlin, Android Java and Web).

## AI integration

The **AI** tab: describe a screen, choose web, Android XML or Compose, and the AI (through the same `AiBackend` as everything else, new backend task `preview_ui`) returns up to 8 files. They appear in the preview as an in-memory **draft**; nothing is written to the project. "Refine draft" sends the current draft back with a change request. "Save to project" asks for confirmation, then goes through the **same change manager** as every other AI change (stale check, history, undo). Safety: allowed paths per target (web files; `res/layout|values|drawable|color|menu` XML; Kotlin under `app/src/main/java|kotlin`), size and count limits, secrets refused, duplicates refused, refused files are listed; external loads and unusual APIs (`Runtime.getRuntime`, `ProcessBuilder`...) are flagged, not silently accepted. Generated JavaScript only ever runs in the sandbox. Flutter generation is refused. **Not tested against real Gemini**; the prompt in `backend/src/prompts.js` (`preview_ui`) is checked for shape only.

## Changes to existing files (small on purpose)

- `ai/AiModels.kt`: `PREVIEW_UI` task kind. `backend/src/prompts.js`, `backend/package.json`: the task, version, test script; new `backend/test/phase20.test.js`.
- `di/AppContainer.kt`: `previewHub`. `ui/AppViewModelProvider.kt`: `previewFactory`. `ui/navigation/Routes.kt`, `AlmusApp.kt`: route `preview/{projectId}`.
- `ui/editor/EditorScreen.kt`: a "Live preview" entry in the overflow menu (new parameter with a default); it saves first.
- `README.md`: a Phase 20 line.

## Commands executed and results

| Command | Result |
|---|---|
| `cd backend && npm test` | 46 pass, 0 fail (42 before, 4 new) |
| `python3 scripts/verify_offline.py` | 329 Kotlin files (253 main, 76 test), 1173 `@Test` methods (+132), **0 problems**, workflow YAML valid |
| Import, bracket and lambda-jump check on the 36 new or changed Kotlin files | all `com.almus` imports of the new code resolve; brackets balance (two files were flagged by my first, naive string stripper and are balanced with a better one); no `continue`/`break` inside inline lambdas. It lists two imports in the existing `AppContainer.kt` (`ArtifactInspector`, `WorkflowTemplateProvider`) that my loose script cannot match; I did not touch them. |
| Gradle build, Kotlin compile, Kotlin unit tests, instrumented tests, any run on a device | **Not run** (no toolchain) |

New Kotlin tests (132): XML parser and resources 10, layout engine 15, XML layout builder 15, Compose outline and lexer 16, web sandbox (theme emulation, HTML instrumenter, request policy, console, breakpoints, bundle) 31, preview session 23, detector, native readiness, devices, AI parser and generator 22. They cover the request policy against traversal, other schemes, IP forms and lookalike hosts; that previews never write; the draft overlay; auto-refresh with virtual time; source-line mapping; secret redaction; and the honesty labels.

## What to verify on a device (first)

1. A web page shows up at all (the interceptor path), in light and dark.
2. A page that tries `fetch("https://example.com")` shows a "blocked" console line, and with the internet switch off nothing leaves the phone.
3. `<a href="file:///...">`, `intent:` and `window.open` do nothing.
4. Tap-to-source in inspect mode, and the script error to editor jump.
5. An endless loop (`while(true){}`) is stopped and reported.
6. The wireframe of a real layout and a real Compose screen, to see how far the approximation is off.

## Limitations (honest list)

- Uncompiled Kotlin, unrun tests, no on-device check (above). The UI composables, `PreviewViewModel`, `PreviewHub` and the WebView setup have no tests.
- Binary assets are not available to web previews.
- The Android previews are approximations; Compose is read as an outline. Real fonts, themes, Material components, animations and unsupported attributes differ. Text size is estimated.
- No native-fidelity render exists. The cloud route is a design plus a readiness checker, never executed.
- Auto-refresh polls (up to 400 files, 3 MB), it does not use file events. Unsaved editor text is not previewed.
- No console REPL, no visual editing, no split view.
- Dark mode for web is an emulation on a copy of the CSS.
- Flutter: detection only.
- Preview settings (device, theme) are not remembered between sessions.

## Suggested next step (not started)

Compile on CI, fix the first round of errors, and run the six device checks. Then decide whether the cloud Layoutlib render is worth building, since it is the only route to native-fidelity previews.
