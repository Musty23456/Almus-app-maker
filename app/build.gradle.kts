import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// ---- Phase 5 request: stable app signing -------------------------------------------------------------------
// The signing key is NEVER stored in the repository. GitHub Actions decodes it from repository secrets into a temporary
// file and passes its path and password through environment variables (see .github/workflows/android-build.yml).
// Without them (for example a local build) the normal Android debug key is used, exactly as before.
val almusKeystorePath: String? = System.getenv("ALMUS_KEYSTORE_PATH")
val almusKeystorePassword: String? = System.getenv("ALMUS_KEYSTORE_PASSWORD")
val hasAlmusSigning = !almusKeystorePath.isNullOrBlank() && !almusKeystorePassword.isNullOrBlank() && file(almusKeystorePath).exists()

android {
    namespace = "com.almus.appcreatorpro"
    compileSdk = 35
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.almus.appcreatorpro"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.4.1-signed"
    }

    signingConfigs {
        if (hasAlmusSigning) {
            create("almus") {
                storeFile = file(almusKeystorePath!!)
                storePassword = almusKeystorePassword
                keyAlias = "almus"
                keyPassword = almusKeystorePassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // Same APK name and path as before; only the signature changes when the Almus key is available.
            if (hasAlmusSigning) signingConfig = signingConfigs.getByName("almus")
        }
        release {
            // Phase 1 only builds debug APKs. Release signing is intentionally not configured:
            // never commit keystores or passwords to the repository.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        // The cloud-build workflow lives in .github/workflows (single source of truth); the app bundles a copy of
        // those files as assets so it can show/copy almus-cloud-build.yml. They contain no secrets.
        getByName("main") {
            assets.srcDir(rootProject.file(".github/workflows"))
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
