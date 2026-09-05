plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
}

/*
 * :kiteplayer-view owns the native presentation widgets. KitePlayerView is a normal Android View
 * usable from XML, Java/Kotlin, or AndroidView; KitePlayerUIView is the UIKit twin. Compose-free
 * by construction. Renderer adapters are injected by :kiteplayer-view-bindings or by the application,
 * so custom media backends never encounter a hidden KiteFFmpeg frame cast in this module.
 *
 * The JVM target owns the AWT video view. All widgets accept renderer adapters, keeping this
 * module independent of the media backend and automatic network transport.
 */
kotlin {
    explicitApi()
    jvmToolchain(21)

    // This tracks the jvm and klib surfaces and NOT the Android one. Measured on Kotlin
    // 2.4.10, 2026-08-25: `internalDumpKotlinAbi` emits exactly two variants, `jvm` and `.klib.api`,
    // so `KitePlayerView` and `SubtitleOverlayView` in androidMain appear in no committed dump and
    // have nothing to disagree with. Renaming or removing an Android public member ships silently.
    // Waiting on Kotlin to add an Android variant rather than hand-rolling a second checker;
    // re-measure on each Kotlin bump.
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
