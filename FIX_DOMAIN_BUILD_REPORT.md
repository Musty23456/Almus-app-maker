# Fix: missing `domain/build` package (CI error "Unresolved reference 'build'" / 'SigningException')

## Cause
The upload had no `app/src/main/java/com/almus/appcreatorpro/domain/build/` folder (and no `src/test/.../build/` tests), but ~20
files import it. The service/build layer was also still the Phase 5 version, so it did not match its callers.

## Added / changed (reconstructed from how the existing code and tests use these types)
- `domain/build/`: BuildVariant (+OutputFormat), BuildConfiguration (+BuildOptions), BuildConfigurationValidator,
  SigningModels (SigningProfile/Source/Secrets/SecretNames/Exception/Validator/Readiness), Artifacts (ArtifactKind/ArtifactNaming), WorkflowInputs.
- `service/build/`: BuildFollower + SyncOutcome; CloudBuildOrchestrator (build configuration, signing blockers, workflow v1/v2 check,
  cancel, syncOnce); BuildTracker (BuildFollower, cancel); BuildService (cancelBuild, build configuration, retry keeps configuration);
  BuildDetailsService hides the hand-over artifact.
- `.github/workflows/almus-cloud-build.yml`: version 2 (build job without secrets + sign job), as the guard tests require.
- `.gitignore`: `!app/src/**/build/` so source packages named "build" are never ignored again.
- Test: `src/test/.../build/BuildConfigurationTest.kt`.

## Honest status
No Kotlin compiler / Gradle / network was available here, so NOTHING was compiled or run. Checked: every project import resolves to a
declaration, brackets balance, and the workflow was checked against the RepositoryGuardsTest rules with a script. Push and let
"Android Build" run; expect possible further compile errors (the earlier phases were never compiled either).

## Round 2 (CI run #15)
- SigningStore.parse: block body (returns inside expression body are illegal).
- WorkflowCheck.LEGACY added (workflow version 1: debug APK only); orchestrator + CloudBuildScreen consistent.
- BuildDetailsService.loadRawLog added (used by DebugHub).
- ProjectPathResolver: the file exists in this zip at app/src/main/java/com/almus/appcreatorpro/debug/ProjectPathResolver.kt;
  CI said it is unresolved, so it was probably not uploaded to the repository. Check that it is there.

## Round 3
- Test sources: removed ';' and ':' from backticked test names (illegal on the JVM); CloudBuildViewModel parameter order so a trailing lambda binds to workflowTemplate.

## Round 4
- Compared the re-uploaded zip with this project: it had the old workflow v1 and lacked round-3 fixes; this zip is the complete, current state. KeystoreGenerator.kt is at app/src/main/java/com/almus/appcreatorpro/service/signing/.

## Round 5 (811 tests ran, 23 failed)
- the_app_never_logs: removed the literal 'Log.e(' from a suggestion string in StaticDiagnostics.kt.
- 8 workflow/signing failures: CI ran the OLD workflow v1; this zip has v2 (checked against the guard rules with a script).
- 17 debug-sample failures (StaticDiagnosticsTest, BuildErrorAnalyzerTest, DebugSessionTest): the sample sources under
  app/src/test/resources/phase8-samples/*/app/** are probably missing from the repository (GitHub web upload takes ~100 files at a time).
  They are all in this zip (26 files).

## Round 6 (811 tests, 16 failed)
- Workflow: two failure messages in the sign step ended with the word secrets followed by a dot, which RepositoryGuardsTest treats as a secret used inside a run script. Reworded.
- Still failing (15): StaticDiagnosticsTest, BuildErrorAnalyzerTest, DebugSessionTest. They all read app/src/test/resources/phase8-samples/**; the failing
  assertions are exactly what an EMPTY sample project produces. Check that e.g. phase8-samples/kotlin-errors/app/src/main/java/com/example/broken/Calculator.kt is on GitHub.

## Round 7
- Root cause of the 15 debug-test failures: the Android Gradle plugin silently drops *.kt and *.java files from test resources, so the sample projects were empty.
  The 7 sample sources are now stored as Name.kt.sample / Name.java.sample and Samples.project() restores the real names.
