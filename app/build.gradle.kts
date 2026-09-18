plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Overridable from CI (-PappVersionCode=... -PappVersionName=...) so the
// APK's own manifest version actually matches the GitHub release tag it
// ships under — this was hardcoded at 1/"1.0.0" for every release before,
// which is why Obtainium showed "installed: 1.0.0" and offered an update
// to the tag name it just installed. Falls back to these defaults for a
// plain local `./gradlew assembleRelease` with no CI properties passed.
val appVersionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1
val appVersionName = project.findProperty("appVersionName") as String? ?: "1.0.0"

android {
    namespace = "io.github.p1neapplexpress.openflux"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.p1neapplexpress.openflux"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = false
        aidl = true
    }

    signingConfigs {
        // A fixed, checked-in keystore — NOT a real secret, same status as
        // AGP's own auto-generated debug.keystore (identical well-known
        // store/key password "android", alias "androiddebugkey"; only the
        // actual keypair differs). This exists as an explicit file instead
        // of relying on signingConfigs.getByName("debug") because THAT one
        // is generated on demand into ~/.android/debug.keystore the first
        // time it's needed — on a fresh ubuntu-latest GitHub Actions runner
        // (no ~/.android persisted between runs) that means every CI build
        // signed with a brand-new random key. Android refuses to install
        // an APK over an existing install when the signing certificate
        // doesn't match, so every release silently required a manual
        // uninstall first — exactly the "обновление не работает" report.
        // A fixed, repo-committed keystore makes every build share the
        // same signature, so Obtainium's in-place update actually applies.
        create("release") {
            // Named debug.keystore (not release.keystore) so it's picked
            // up by this project's own pre-existing .gitignore exception
            // (`*.keystore` is ignored, `!debug.keystore` is explicitly
            // un-ignored) without having to touch that file.
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // Sideloaded via Obtainium, not the Play Store — a fixed,
            // repo-committed key (see signingConfigs.release above), not a
            // real release identity. Still a validly signed APK (Android
            // refuses to install a truly unsigned one).
            signingConfig = signingConfigs.getByName("release")
            // R8 minification was configured here (isMinifyEnabled = true)
            // but proguard-rules.pro was completely empty — no keep rules
            // for kotlinx.serialization's reflective serializer lookup, the
            // AIDL-generated IUnifiedService interface, or anything else.
            // Nothing had ever actually exercised this: this repo had no CI
            // before, so no one had ever run a real `assembleRelease` build
            // against it. Every user-visible crash on tunnel start, across
            // every transport (not just the new mailru one), matches R8
            // having broken something in that shared start-up path.
            // Disabled rather than papering over it with keep rules for a
            // sideloaded, personal-use build where obfuscation buys
            // nothing but harder-to-read crash reports — see CrashHandler.
            isMinifyEnabled = false
            isShrinkResources = false
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("io.github.g00fy2.quickie:quickie-bundled:1.10.0")
}
