plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
}

/*
 * Compatibility umbrella for 0.0.2 source consumers. New code should depend on
 * :kiteplayer-mobile for the default stack or :kiteplayer-view for native views alone. This
 * module owns no implementation and must never become a dependency of either clean module.
 */
kotlin {
    explicitApi()
    jvmToolchain(21)

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {}

    iosArm64()
    iosSimulatorArm64()
    // Umbrella-only jvm presence: both leaves publish jvm, so the umbrella resolves from a
    // consumer's commonMain when that consumer also compiles a desktop target.
    jvm()

    android {
        namespace = "io.github.yuroyami.kiteplayer.phone"
        compileSdk = 36
        minSdk = 26
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":kiteplayer-mobile"))
            api(project(":kiteplayer-view"))
        }
    }
}
