import io.github.yuroyami.kiteplayer.buildtools.PrepareAndroidSampleMediaTask

/*
 * One application comparing the three Android presentation products without hiding their
 * lifecycle differences: an XML-inflated native view, that same native view hosted by Compose,
 * and true Compose video. The original XML Activity remains the measured smoke component.
 *
 * Release is deliberately debug-signed and debuggable: the smoke oracle reads the app's private
 * files through run-as, which needs both, and R8 still runs, which is the half that matters.
 * No distributed release consumes either local-only choice.
 */
plugins {
    /* AGP 9 carries built-in Kotlin support; a separate Kotlin Android plugin is refused. */
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

android {
    namespace = "io.github.yuroyami.kiteplayer.sample.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.yuroyami.kiteplayer.sample.android"
        minSdk = 26
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

    buildFeatures {
        compose = true
    }
}

dependencies {
    // The playable stack and the two deliberately different Compose presentation products.
    implementation(project(":kiteplayer-mobile"))
    implementation(project(":kiteplayer-compose-interop"))
    implementation(project(":kiteplayer-compose-video"))
    implementation(project(":kiteplayer-compose-ui"))
    implementation(compose.runtime)
    implementation(compose.ui)
    implementation(compose.foundation)
    implementation(libs.androidx.activity.compose)
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
