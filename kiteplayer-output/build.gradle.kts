plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
}

/*
 * :kiteplayer-output holds the audio sinks and the video renderers: the only code in the library
 * that talks to an operating system.
 *
 * Everything here is small on purpose. All playback policy lives in :kiteplayer-core, and a backend
 * only obeys. A sink writes samples the engine gives it and reports honestly when they will be
 * heard; a renderer draws a frame at a time it is told. Neither decides anything.
 *
 * Targets are added as backends land. Apple first, because CoreAudio and Metal are reachable and
 * testable on the development machine, and because iOS then shares the same code. The JVM target
 * publishes the common surface only, no backend: it exists so a consumer whose commonMain depends
 * on this module still resolves when that consumer also compiles a desktop target.
 */
kotlin {
    explicitApi()
    jvmToolchain(21)

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {
        // Declaring the block is what switches tracking on.
    }

    macosArm64()
    iosArm64()
    iosSimulatorArm64()
    jvm()

    /* S1.c.4 step 1. Output depends only on core and its existing portable libraries: no
     * KiteCodec, no FFmpeg, no NDK, no Android media support library (the boundary scans of
     * S1.c.5 step 10 enforce it). Host tests drive the fake AudioTrack/canvas seams; device
     * tests drive the real ones on the named emulator. */
    android {
        namespace = "io.github.yuroyami.kiteplayer.output"
        compileSdk = 36
        minSdk = 26
        withHostTest {}
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":kiteplayer-core"))
            // The library only, never the Gradle plugin: KPKMP.md contract item 6. Used by
            // CoreAudioSink for one lock, which orders its own teardown against a diagnostic read
            // from another thread. Nothing on the real-time path takes it; the device callback is a
            // C function that never enters this module.
            implementation(libs.kotlinx.atomicfu)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        getByName("androidDeviceTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.androidx.test.core)
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.ext.junit)
        }
    }
}
