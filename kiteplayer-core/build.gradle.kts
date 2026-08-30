import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
}

/*
 * :kiteplayer-core is the engine. It holds every playback decision: the player class, the session
 * loop, the clock, A/V synchronisation, packet and frame queues, buffering policy, the seek state
 * machine and track selection. Cue timing is not among them; no cue is timed anywhere yet.
 *
 * It depends on kotlinx-coroutines and atomicfu, and nothing else. Every playback decision in it is
 * platform free, which is why it compiles for every target it declares, including js and wasm,
 * before any backend for those targets exists. There is exactly one platform-dependent declaration,
 * `platformPlaybackDispatchers`, because a thread per worker cannot be asked for in common code:
 * `newSingleThreadContext` does not exist on every target. Its actual is one expression per platform.
 *
 * Time enters through the MonotonicClock interface, so every timing rule in the engine is
 * testable in virtual time. That property is the reason the engine is shaped this way.
 */
kotlin {
    explicitApi()
    jvmToolchain(21)

    // Public API tracking. `updateKotlinAbi` refreshes api/*.api, `checkKotlinAbi` fails the
    // build when the committed dump and the code disagree.
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {
        // Declaring the block is what switches tracking on.
    }

    android {
        namespace = "io.github.yuroyami.kiteplayer.core"
        compileSdk = 36
        minSdk = 26
        withHostTest {}
    }

    iosSimulatorArm64(); iosArm64(); iosX64()
    macosArm64()
    tvosArm64(); tvosSimulatorArm64()
    watchosArm32(); watchosArm64(); watchosDeviceArm64(); watchosSimulatorArm64()
    androidNativeArm32(); androidNativeArm64(); androidNativeX64(); androidNativeX86()
    linuxX64(); linuxArm64()
    mingwX64()
    jvm()

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    js {
        browser()
        nodejs()
        binaries.library()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        nodejs()
    }

    sourceSets {
        all {
            languageSettings {
                optIn("kotlin.RequiresOptIn")
            }
        }

        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.atomicfu)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        /*
         * The C audio ring, for `internal/NativeAudioRing.kt` and the differential oracle in
         * `nativeTest`. Native only: js and wasmJs can never contain C, which is exactly why
         * `KotlinAudioRing` stays the portable implementation and cannot become an `expect class`.
         *
         * `api` and not `implementation`: what this dependency really carries is a cinterop klib, and
         * `nativeTest` is a different compilation from `nativeMain`, so the bindings have to be
         * exposed rather than hidden. The generated `kitert` bindings are the public surface of
         * `kiteplayer-rt`, and publishing the module makes that cinterop klib consumable. The raw C
         * ring handed across the engine seam is marked `RawRingApi`, so callers cross it deliberately.
         *
         * In B1.7 nothing on the shipped path constructed a `NativeAudioRing`: `AudioPlayback.open`
         * built a `KotlinAudioRing` and the device callback was still a Kotlin closure. B1.8 changed
         * that, once, and it is the only sub-phase in B1 that touched the real-time path. Since then a
         * sink that owns a C callback is handed a C ring and every other sink is not, which is decided
         * in `internal/AudioPath.kt` and nowhere else.
         */
        nativeMain.dependencies {
            api(projects.kiteplayerRt)
        }
    }
}
