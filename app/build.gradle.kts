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
        versionCode = 28
        versionName = "0.9.15"
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
}
