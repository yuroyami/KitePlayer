plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
}

/*
 * Complete Compose entry point: the standard runtime and its network transport, plus both video
 * presentation paths through compose-ui. The legacy phone API remains re-exported for existing
 * consumers, while UI-only applications can choose compose-ui without this runtime.
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
            // Preserve the old phoneBackends and view names without making UI modules own them.
            api(project(":kiteplayer-phone"))
        }
    }
}
