import io.github.yuroyami.kiteplayer.buildtools.PrepareAndroidSampleMediaTask

/*
 * One application comparing the three Android presentation products without hiding their
 * lifecycle differences: an XML-inflated native view, that same native view hosted by Compose,
 * and true Compose video. The original XML Activity remains the measured smoke component.
 *
 * Release has two shapes and the keys decide which. With no release keystore configured it is
 * debug-signed and debuggable ON PURPOSE: the smoke oracle reads the app's private files through
 * run-as, which needs both. Give it a keystore through the four properties below and the same
 * build type becomes what a distributed release has to be, not debuggable and signed with the real
 * key. Nothing is conditional except the two lines that have to be.
 *
 * A correction to what this comment used to claim: the keyless build does NOT exercise R8. AGP
 * says so on every run, "All code optimizations and obfuscation are disabled for debuggable
 * builds", so `isMinifyEnabled = true` buys nothing there and the shrunk build is the keyed one.
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

    /*
     * The real release keystore, when this machine has one. All four properties are required
     * together: a half-configured keystore is a build that fails at signing time with a message
     * about the wrong thing.
     */
    val keystorePath: String? = providers.gradleProperty("kiteplayer.release.storeFile").orNull
    val keystorePassword: String? = providers.gradleProperty("kiteplayer.release.storePassword").orNull
    val keyAlias: String? = providers.gradleProperty("kiteplayer.release.keyAlias").orNull
    val keyPassword: String? = providers.gradleProperty("kiteplayer.release.keyPassword").orNull
    val releaseKeystore: File? = keystorePath
        ?.let(::file)
        ?.takeIf { it.isFile && !keystorePassword.isNullOrBlank() && !keyAlias.isNullOrBlank() && !keyPassword.isNullOrBlank() }

    if (releaseKeystore != null) {
        signingConfigs.create("release") {
            storeFile = releaseKeystore
            storePassword = keystorePassword
            this.keyAlias = keyAlias
            this.keyPassword = keyPassword
        }
    } else {
        logger.lifecycle(
            "[KitePlayer sample] no release keystore: the release build stays debug-signed and " +
                "debuggable, which is what the smoke oracle needs. Set " +
                "kiteplayer.release.storeFile, storePassword, keyAlias and keyPassword for a " +
                "distributable one.",
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // Both follow the keystore together. A release that is signed for distribution and
            // still debuggable is the worst of the two, because anything can read its data.
            isDebuggable = releaseKeystore == null
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
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
    // The complete entry point supplies the runtime, native views and both Compose paths.
    implementation(project(":kiteplayer-compose"))
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
