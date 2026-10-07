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

    // From Kotlin 2.4.20 this tracks the Android surface too, in api/android/, so `KitePlayerView`
    // and `SubtitleOverlayView` in androidMain are guarded like the jvm and klib surfaces.
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {}

    iosArm64()
    iosSimulatorArm64()
    // macOS carries only the picture in picture class. Its window lives in kiteplayer-output.
    macosArm64()
    jvm()
    // The web carries only its picture in picture class, over the page's own canvas.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        nodejs()
    }

    android {
        namespace = "io.github.yuroyami.kiteplayer.view"
        compileSdk { version = release(37) { minorApiLevel = 2 } }
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
        // The web tests wait for a browser promise, so they need a coroutine test scope.
        getByName("wasmJsTest").dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
