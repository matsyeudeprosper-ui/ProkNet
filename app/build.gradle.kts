plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "net.prok.proknet"
    compileSdk = 34

    defaultConfig {
        applicationId = "net.prok.proknet.lab"
        minSdk = 26
        targetSdk = 34
        versionCode = 71
        versionName = "0.17.3"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ""
        }
        release {
            // Release is unsigned for now; the lab uses the debug build.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        // Pure-JVM tests for core/ (Packet, Routing). No Robolectric, no emulator.
        unitTests.isReturnDefaultValues = true
    }

    // Predictable APK file name: app/build/outputs/apk/debug/ProkNetLab-debug.apk
    applicationVariants.all {
        val variant = this
        variant.outputs
            .map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
            .forEach { output ->
                output.outputFileName = "ProkNetLab-${variant.buildType.name}.apk"
            }
    }
}

dependencies {
    // Deliberately no AndroidX / Material dependencies:
    // plain android.app.Activity keeps the dependency tree tiny and builds fast
    // on the shared VPS. Only the Kotlin stdlib is pulled in.
    testImplementation("junit:junit:4.13.2")
    // v0.16.4, TEST ONLY - never reaches the APK.
    //
    // The database migration is the one piece of this project that can only be wrong on
    // a phone that has been upgraded, never on a fresh install, so it cannot be proved by
    // reasoning about the SQL. These tests build real databases with the exact historical
    // schemas and run the real migration statements against them.
    testImplementation("org.xerial:sqlite-jdbc:3.46.0.0")
}
