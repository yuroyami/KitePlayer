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
 * :kiteplayer-compose-ui is the runtime-choice layer: one KitePlayerVideo composable hosting
 * either the native-view path (compose-interop) or the true Compose path (compose-video), and
 * able to swap between them while a caller-owned player runs. It selects no player factory or
 * network transport. The complete entry point is :kiteplayer-compose; consumers wanting exactly
 * one path can keep depending on that path's module directly. On the web the Compose canvas is the
 * one real path (#125): KitePlayerSurface is an empty placeholder there, so every request resolves
 * to KiteVideo, and the display is held through the page's wake lock in kiteplayer-output.
 */
kotlin {
    explicitApi()
    jvmToolchain(21)

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {}

    iosArm64()
    iosSimulatorArm64()
    jvm()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    android {
        namespace = "io.github.yuroyami.kiteplayer.compose.ui"
        compileSdk { version = release(37) { minorApiLevel = 2 } }
        minSdk = 26
        withHostTest {}
    }

    sourceSets {
        commonMain.dependencies {
            // What KitePlayerVideo's own signature names. The two paths it switches between are an
            // implementation detail, so an app sees one video composable (#387). An app that draws
            // with KitePlayerSurface or KiteVideo directly adds that module itself.
            api(project(":kiteplayer-core"))
            api(compose.runtime)
            api(compose.ui)
            // The default controls (#469). An application already has it through compose-video.
            implementation(compose.foundation)
            implementation(project(":kiteplayer-compose-interop"))
            implementation(project(":kiteplayer-compose-video"))
            // The screen reader wording, shared with the platform views.
            implementation(project(":kiteplayer-view"))
        }
        wasmJsMain.dependencies {
            implementation(project(":kiteplayer-output"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            // Skia's native library, which a headless Compose scene needs to draw.
            implementation(compose.desktop.currentOs)
            // The semantics tree, which is what a screen reader reads. Test scope only.
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
    }
}

// No web test runs here yet. Compose wires a check into the wasmJs browser test task that fails when
// a test compilation depends on Skiko and no executable binary bundles it; with nothing to bundle, it
// only fails the task. Remove this when a web test is added here.
tasks.matching { it.name.startsWith("checkComposeUiTestConfigurationFor") }.configureEach {
    enabled = false
}
