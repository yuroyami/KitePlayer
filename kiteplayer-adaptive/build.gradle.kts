plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
}

/*
 * :kiteplayer-dash reads a DASH manifest and plays it, in Kotlin, with no HTTP client of its own.
 * It parses the manifest, plans the segments and serves the presentation to the player's HLS path.
 * A transport makes every request: :kiteplayer-network gives it Ktor's, and the player in a Web
 * Worker gives it synchronous ones (#546).
 *
 * The package stays io.github.yuroyami.kiteplayer.network.dash, where this code lived while it was
 * part of :kiteplayer-network, so every public name keeps its full name.
 */
kotlin {
    explicitApi()
    jvmToolchain(21)

    applyDefaultHierarchyTemplate()

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {
        // Declaring the block is what switches tracking on.
    }

    macosArm64()
    iosArm64()
    iosSimulatorArm64()
    jvm()
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    js {
        browser()
        nodejs()
        binaries.library()
    }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        nodejs()
    }
    android {
        namespace = "io.github.yuroyami.kiteplayer.dash"
        compileSdk { version = release(37) { minorApiLevel = 2 } }
        minSdk = 26
        withHostTest {}
    }

    sourceSets {
        // The bounded XML reader of kiteplayer-subtitles and the fragmented MP4 rewriter of
        // kiteplayer-core are shared behind this marker (#492, #464).
        all { languageSettings.optIn("io.github.yuroyami.kiteplayer.KitePlayerInternalApi") }
        commonMain.dependencies {
            api(project(":kiteplayer-core"))
            implementation(project(":kiteplayer-subtitles"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
