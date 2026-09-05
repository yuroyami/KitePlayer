import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
}

/*
 * :kiteplayer-compose-interop is deliberately tiny: AndroidView over KitePlayerView and UIKitView
 * over KitePlayerUIView, with SwingPanel hosting the desktop view. JS and Wasm keep an empty
 * layout-preserving surface. The adapter dependency contains no player factory or transport;
 * native views remain usable without Compose and true Compose drawing lives in compose-video.
 */
kotlin {
    explicitApi()
    jvmToolchain(21)

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {}

    iosArm64()
    iosSimulatorArm64()
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

    android {
        namespace = "io.github.yuroyami.kiteplayer.compose.interop"
        compileSdk = 37
        minSdk = 26
        withHostTest {}
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":kiteplayer-core"))
            api(compose.runtime)
            api(compose.ui)
        }
        androidMain.dependencies {
            implementation(project(":kiteplayer-view-bindings"))
        }
        iosMain.dependencies {
            implementation(project(":kiteplayer-view-bindings"))
        }
        jvmMain.dependencies {
            implementation(project(":kiteplayer-view-bindings"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // The one composable this module publishes had no test at all, because testing a
        // composable needs a composition to put it in. Compose's own desktop UI test harness is a
        // TEST-scope dependency and adds nothing to the published artifact.
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
    }
}
