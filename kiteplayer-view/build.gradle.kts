plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
}

/*
 * :kiteplayer-view owns the native presentation widgets. KitePlayerView is a normal Android View
 * usable from XML, Java/Kotlin, or AndroidView; KitePlayerUIView is the UIKit twin. Compose-free
 * by construction. Renderer adapters are injected by :kiteplayer-mobile or by the application,
 * so custom media backends never encounter a hidden KiteCodec frame cast in this module.
 *
 * The JVM target publishes the common surface only, no widget: it exists so a consumer whose
 * commonMain depends on this module (directly or through an umbrella) still resolves when that
 * consumer also compiles a desktop target.
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
        namespace = "io.github.yuroyami.kiteplayer.view"
        compileSdk = 36
        minSdk = 26
        withHostTest {}
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":kiteplayer-core"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
