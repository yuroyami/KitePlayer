import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlinx.atomicfu)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
}

/*
 * :kiteplayer-core is the engine. It holds every playback decision: the clock, A/V
 * synchronisation, packet and frame queues, buffering policy, the seek state machine, track
 * selection and subtitle timing.
 *
 * It depends on kotlinx-coroutines and atomicfu, and nothing else. It contains no expect
 * declaration and no platform API call, which is why it compiles for every target Kotlin
 * supports, including js and wasm, before any backend for those targets exists.
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
        minSdk = 21
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
    }
}
