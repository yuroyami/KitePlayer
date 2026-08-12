import io.github.yuroyami.kiteplayer.buildtools.PrepareAndroidSampleMediaTask

/*
 * The Android assembly proof (S1.c.6, re-consumed by S1.d.4): one plain application whose whole
 * job is to prove Gradle variant resolution, transitive JNI packaging, R8, the view lifecycle,
 * audio, seek and teardown in one ordinary app. Since S1.d it consumes exactly ONE coordinate,
 * :kiteplayer-phone, and presents through the reusable KitePlayerView.
 *
 * Release is deliberately debug-signed and debuggable: the smoke oracle reads the app's private
 * files through run-as, which needs both, and R8 still runs, which is the half that matters.
 * No distributed release consumes either local-only choice.
 */
plugins {
    /* AGP 9 carries built-in Kotlin support; a separate Kotlin Android plugin is refused. */
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.yuroyami.kiteplayer.sample.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.yuroyami.kiteplayer.sample.android"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    packaging {
        jniLibs.useLegacyPackaging = false
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    // The whole point of the aggregate: one line, and the playable stack arrives api-transitively.
    implementation(project(":kiteplayer-phone"))
    // Dispatchers.Main's factory. The view's members are main-thread only, and the controller
    // hops threads the way any ordinary app does. Found by the S1.d.4 smoke: without this the
    // hop throws before open() and the oracle reports Idle.
    implementation(libs.kotlinx.coroutines.android)
}

/* The bundled clip: copied from the conformance media, never committed (about 20 MB), never
 * fetched and never transcoded here; scripts/testmedia.sh is the producer and runs first. */
val prepareSampleMedia = tasks.register<PrepareAndroidSampleMediaTask>("prepareAndroidSampleMedia") {
    sourceMedia.set(rootProject.layout.projectDirectory.file("testmedia/sync1080p30.mp4"))
    outputDirectory.set(layout.buildDirectory.dir("generated/s1cAssets"))
}

androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(
        prepareSampleMedia,
        PrepareAndroidSampleMediaTask::outputDirectory,
    )
}
