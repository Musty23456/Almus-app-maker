# Phase 1 ships debug builds only. These rules are a placeholder for future release builds.
# Keep line numbers for readable crash stack traces.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Phase 6: BouncyCastle (creates the certificate of a new signing key). Only the classes that are really used are kept by R8.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-dontwarn javax.naming.**
