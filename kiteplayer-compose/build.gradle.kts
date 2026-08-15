plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
}

/*
 * Compatibility umbrella for the 0.0.2 coordinate that combined two different rendering
 * products. New code chooses :kiteplayer-compose-interop for native-view hosting or
 * :kiteplayer-compose-video for true Compose drawing. No clean module depends on this one.
 */
kotlin {
    explicitApi()
    jvmToolchain(21)

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {}

    iosArm64()
    iosSimulatorArm64()
    // Umbrella-only jvm presence: every re-exported module publishes jvm, so the umbrella
    // resolves from a consumer's commonMain when that consumer also compiles a desktop target.
    jvm()

    android {
        namespace = "io.github.yuroyami.kiteplayer.compose.compat"
        compileSdk = 37
        minSdk = 26
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":kiteplayer-compose-interop"))
            api(project(":kiteplayer-compose-video"))
            // 0.0.2 exported the phone aggregate transitively. Keep that source-level migration
            // path here without making either clean Compose module depend on it.
            api(project(":kiteplayer-phone"))
        }
    }
}
