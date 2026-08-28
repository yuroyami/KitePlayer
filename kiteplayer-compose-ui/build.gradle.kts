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
 * able to swap between them while media plays. Consumers wanting exactly one path keep
 * depending on that path's module directly. No web targets: neither underlying path has a real
 * web playback surface yet.
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
        namespace = "io.github.yuroyami.kiteplayer.compose.ui"
        compileSdk = 37
        minSdk = 26
        withHostTest {}
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":kiteplayer-compose-interop"))
            api(project(":kiteplayer-compose-video"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
