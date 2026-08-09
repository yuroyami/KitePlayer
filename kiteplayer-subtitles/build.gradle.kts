import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
}

/*
 * :kiteplayer-subtitles parses subtitle formats. Pure Kotlin. Today that is SubRip and nothing else:
 * no cue is timed, laid out or drawn, and the player never reads a subtitle track.
 *
 * It will not rasterise glyphs when it does more. Font matching, complex script shaping and
 * bidirectional text are decades of work that every platform already ships, so layout will measure
 * and position text and let the platform draw it. That work is in KPKMP.md section 11.
 */
kotlin {
    explicitApi()
    jvmToolchain(21)

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {
        // Declaring the block is what switches tracking on.
    }

    android {
        namespace = "io.github.yuroyami.kiteplayer.subtitles"
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
        commonMain.dependencies {
            api(projects.kiteplayerCore)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
