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

    android {
        namespace = "io.github.yuroyami.kiteplayer.compose"
        compileSdk = 37
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
