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

    buildTypes {
        release {
            // Sideloaded via Obtainium, not the Play Store — reuse the
            // auto-generated debug key instead of managing a real release
            // keystore. Still a validly signed APK (Android refuses to
            // install a truly unsigned one), just not attributable to a
            // dedicated release identity.
            signingConfig = signingConfigs.getByName("debug")
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
