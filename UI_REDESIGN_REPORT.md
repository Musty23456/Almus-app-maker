# UI redesign - design system, dashboard, navigation

## Read this first
- **Nothing here was compiled, run or rendered.** No Kotlin compiler, Gradle, Android SDK or emulator is available here (same as earlier phases). Expect a few compile errors on the first CI run (`./gradlew testDebugUnitTest assembleDebug`); send me the log and I fix them.
- The project is still **missing `service/build/*`** (BuildService, BuildTracker, CloudBuildOrchestrator...). It was already missing in the Phase 28 zip; the app cannot compile until it is restored.
- What I did run: bracket-balance and import-resolution checks over every new/changed file (clean), and a WCAG contrast calculation for every text/background pair of the new palette (all pass; numbers below).
- Phase 35 was not started.

## What changed

**Design system (`ui/theme/`)**
- `Color.kt`: new "developer tool" palette, light + dark, with the full Material surface-container ladder, outline variants, plus success and warning colors. Old color names are kept so nothing else breaks.
- `Type.kt`: complete type scale (headline to label), tighter tracking on large text, all in sp; `CodeTextStyle` (monospace) for technical text.
- `Shape.kt`: shapes (6/8/12/16/24dp), `Spacing` (4dp grid) and `Dimens` (48dp touch target, 840dp max content width).
- `Theme.kt`: new color schemes, shapes, and `MaterialTheme.almus` for colors Material has no slot for (success, warning, card fill, card border). Light / dark / system switching is unchanged.
- `Motion.kt`: 120/200/320 ms durations and `rememberReducedMotion()` (true when the system animation scale is 0).
- `Layout.kt`: Compact / Medium / Expanded classes (600 / 840 dp) and column counts.

**Shared components (`ui/components/`)** - used by most existing screens, so they change everywhere without touching those screens:
- `SectionCard` (86 uses), `ProjectCard`, `BuildCard`, `BuildStatusPill`, `EmptyView`, `ErrorView`, `LoadingView`, `SectionHeader`, `PrototypeBadge`, `ConfirmDialog`, `TextInputDialog`, `UnsavedChangesDialog` were restyled. **Signatures are unchanged** (new parameters only have defaults).
- New: `AlmusButton` (5 styles, icon, loading state, 48dp), `AlmusCard` (flat, hairline border), `StatTile`, `ActionTile`, `IconBadge`, `ErrorBanner`, `SkeletonBlock/Card/List`.
- Status pills now show icon + text, not color alone.

**Navigation**
- Bottom bar restyled (divider, indicator, always-visible labels, tab role, test tags). Same three destinations: Home, Projects, Settings.
- Screen changes use a short cross-fade (200 ms in, 120 ms out; 0 when animations are off). No route was added, removed or renamed.

**Dashboard (`ui/home/HomeScreen.kt`)**
- Greeting hero, 4 stat tiles (projects, builds, success rate, free storage), 6 quick actions (New project, Open project, **GitHub projects**, **Generate with AI**, Cloud build, Build history), recent projects, connections, build history, storage.
- Skeleton loading state with the same shape as the dashboard, empty states, error state with retry.
- Stats count **real builds only**; sample rows are ignored and the success rate shows "-" until a real build has finished.
- Content is centered in an 840 dp column on tablets/landscape; tiles go 2 columns (phone), 3-4 (tablet).
- Two optional parameters were added to `HomeScreen` (`onOpenGitHubProjects`, `onGenerateWithAi`) and wired in `AlmusApp`; all old callbacks and behaviors are kept (the Cloud build button still starts a build when there is no project).

**Also changed:** splash and welcome gradients use the new brand colors; the five places that colored text with `SuccessGreen` now use the theme-aware `MaterialTheme.almus.success` (the old green was 3.4:1 on dark backgrounds).

## Accessibility
- Minimum 48 dp targets on buttons and tiles; headings marked for screen readers; stat tiles read as "Label: value"; decorative icons hidden; loading announced politely, errors assertively; the `Loading` state of a button is announced and blocks double taps.
- Contrast (WCAG AA 4.5:1 text, 3:1 outlines), measured: body text 14-18:1 light, 12-16:1 dark; secondary text 6.3-7.9:1 light, 6.5-8.9:1 dark; primary button 6.5:1 light, 7.3:1 dark; outlines 4.3-4.8:1; brand gradient with white text 6.4-6.5:1. `DesignSystemTest` repeats these checks in code.

## Performance
- No blur, shadows or gradients animate. Skeletons use one shared alpha value applied in a graphics layer (no recomposition). List items fade/move only when they are added or reordered. All motion is skipped when the system animation scale is off.

## Tests added
`app/src/test/.../ui/DesignSystemTest.kt` (not run): light and dark contrast, brand gradient contrast, breakpoints, reduced-motion durations, spacing order, dashboard stats (sample builds ignored, success-rate rounding), greeting, and that the bottom bar still has Home, Projects and Settings.

## Honest limits
- **Only the dashboard, splash, welcome, bottom bar and the shared components were redesigned by hand.** The other ~25 screens keep their own layouts; they pick up the new colors, type, shapes, cards, dialogs and state views, but about 200 direct `Card(`, 17 `AlertDialog(` and 16 spinner uses inside them still use plain Material styling. Moving them to `AlmusCard` / `AlmusButton` is a mechanical follow-up, best done a few screens at a time with CI feedback.
- Material's own `Button` / `TextButton` calls inside those screens keep their default shape; only `AlmusButton` has the new look.
- No navigation rail for tablets: the bottom bar is passed into each screen, so a rail needs a bigger change. Tablets get the centered content column instead.
- "Test important screens" was done only as far as possible without a device: logic tests plus static checks. The Compose screens were never rendered, so spacing and visual polish are unverified. Please look at Home, Projects, Editor and a dialog in light and dark on a phone and on a tablet.
- Typography uses the system font. A custom font would need a font file added to the project.
