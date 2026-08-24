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
 * testable on the development machine, and because iOS then shares the same code. The desktop JVM
 * came next in phase W: SourceDataLine for audio and the JDK's own text engine for subtitles, which
 * is decision W-D2, Kotlin first under D-7. The Kotlin/Native desktops publish the common surface
 * only for now, so a consumer that compiles them still resolves; their C device sinks are register
 * items W-08 and W-09.
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
    // The Kotlin/Native desktops (phase W). They publish the common surface plus whatever backend
    // exists for them, which today is none: a Kotlin/Native desktop consumer has the engine and
    // the FFmpeg backend but no device sink yet, and register items W-08 and W-09 are where that
    // lands. Declaring the targets is what lets :kiteplayer-ffmpeg's real-media tests resolve this
    // module on Linux at all.
    linuxX64()
    linuxArm64()
    mingwX64()
    jvm()
    // The web (17.14 X-12): a clock and a sink, no renderer, exactly like the desktop backend.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        nodejs()
    }

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
            // The library only, never the Gradle plugin: KPKMP-PAST.md contract item 6. Used by
            // CoreAudioSink for one lock, which orders its own teardown against a diagnostic read
            // from another thread. Nothing on the real-time path takes it; the device callback is a
            // C function that never enters this module.
            implementation(libs.kotlinx.atomicfu)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // The web sink is a pump, so its tests need a coroutine scope to run it in.
        getByName("wasmJsTest").dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("androidDeviceTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.androidx.test.core)
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.ext.junit)
        }
    }
}

/*
 * One assertion in CoreAudioSinkTest needs REAL AUDIO HARDWARE, and CI has none.
 *
 * `-Pkiteplayer.noAudioHardware=true` drops exactly that test and nothing else. Everything else in
 * this module, the rest of the CoreAudio suites included, still runs: a GitHub macOS runner opens
 * CoreAudio successfully and passes 94 of the 95 macosArm64 tests. The one that cannot pass is
 * `the anchor the device publishes is in the near future on the engine clock`, which measured
 * -451.7 ms against a window of -5 to +500 ms. That is the DEVICE, not the clock bases: a base
 * mismatch would be enormous, as the test's own note says, rather than half a second. A runner's
 * virtual output device lags where real hardware leads.
 *
 * WHY A FILTER AND NOT A SKIP INSIDE THE TEST. A test that returns early reports as PASSED, and a
 * green tick over an assertion nobody made is the exact failure this project keeps paying for. A
 * filter makes Gradle report it as not run, which is the truth. The default is unset, so a developer
 * on a real Mac runs it without knowing this flag exists.
 */
if (providers.gradleProperty("kiteplayer.noAudioHardware").map(String::toBoolean).getOrElse(false)) {
    tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
        filter.isFailOnNoMatchingTests = false
        filter.excludeTestsMatching("*CoreAudioSinkTest.the anchor the device publishes*")
    }
    logger.lifecycle(
        "[kiteplayer-output] kiteplayer.noAudioHardware=true: the CoreAudio anchor assertion is " +
            "NOT run. Every other test in this module still is.",
    )
}
