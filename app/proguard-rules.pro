# Release-build rules (the CI build only produces debug APKs; the release/R8 path has never been run - see PHASE10_REPORT.md).
# Keep line numbers for readable crash stack traces.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Phase 6: BouncyCastle (creates the certificate of a new signing key). Only the classes that are really used are kept by R8.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-dontwarn javax.naming.**

# Phase 10: merged from a stray, never-used copy at app/src/proguard-rules.pro (Gradle only reads app/proguard-rules.pro).
# OkHttp / Okio reference optional platform classes that are not on Android.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
