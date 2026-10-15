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
 * Complete Compose entry point: the standard runtime and its network transport, plus both video
 * presentation paths behind compose-ui's KitePlayerVideo, and rememberKitePlayer. UI-only
 * applications can choose compose-ui without this runtime.
 */
kotlin {
    explicitApi()
    jvmToolchain(21)

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {}

    iosArm64()
    iosSimulatorArm64()
    jvm()

    // The web resolves too (#125). rememberKitePlayer needs the codec module loaded first there, as
    // KitePlayer() does, and KitePlayerVideo draws through KiteVideo's WebGL path.
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    android {
        namespace = "io.github.yuroyami.kiteplayer.compose"
        compileSdk { version = release(37) { minorApiLevel = 2 } }
        minSdk = 26
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":kiteplayer"))
            api(project(":kiteplayer-compose-ui"))
            // rememberKitePlayer is a composable.
            implementation(compose.runtime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            // Skia's native library, which a headless Compose scene needs.
            implementation(compose.desktop.currentOs)
        }
    }
}

// No web test runs here yet. Compose wires a check into the wasmJs browser test task that fails when
// a test compilation depends on Skiko and no executable binary bundles it; with nothing to bundle, it
// only fails the task. Remove this when a web test is added here.
tasks.matching { it.name.startsWith("checkComposeUiTestConfigurationFor") }.configureEach {
    enabled = false
}
