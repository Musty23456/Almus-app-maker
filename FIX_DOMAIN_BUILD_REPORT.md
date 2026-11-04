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
