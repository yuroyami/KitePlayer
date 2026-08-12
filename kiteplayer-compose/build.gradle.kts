import io.github.yuroyami.kitecodec.gradle.FFmpegLicense
import io.github.yuroyami.kitecodec.gradle.FFmpegSource
import java.io.File

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
    // Same rule as :kiteplayer-phone and :kiteplayer-sample: this module's own iOS test
    // binaries link FFmpeg through the phone dependency, and KiteCodec's cinterop declares
    // bare -lavformat and friends with no -L, so the plugin must supply the directory.
    id("io.github.yuroyami.kitecodec") version "0.0.1"
}

/*
 * :kiteplayer-compose is the optional Compose Multiplatform surface, carrying BOTH Compose paths
 * of D-6 and nothing else:
 *
 * - KitePlayerSurface, the BASELINE: one Composable wrapping the platform view (AndroidView over
 *   KitePlayerView, UIKitView over KitePlayerUIView). A SurfaceView/CALayer composits through
 *   the display controller, which is what wins sustained fullscreen battery.
 * - KiteVideo, the FLAGSHIP (17.9): decoded frames drawn through Compose's own pipeline, so
 *   video is real Compose content that clips, fades, rotates and animates like any other
 *   primitive. Its S1 cost is honest CPU conversion; S2 measures and lands the YUV path.
 *
 * Consumers that do not use Compose depend on :kiteplayer-phone (or lower) and never see this
 * module.
 */
kotlin {
    explicitApi()
    jvmToolchain(21)

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {
        // Declaring the block is what switches tracking on.
    }

    iosArm64()
    iosSimulatorArm64()

    android {
        namespace = "io.github.yuroyami.kiteplayer.compose"
        // 37, not 36: the Compose 1.12 Android artifacts refuse to compile against 36, and
        // this module is the only one that depends on them. compileSdk is not minSdk; nothing
        // about the shipped support floor changes.
        compileSdk = 37
        minSdk = 24
        withHostTest {}
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":kiteplayer-phone"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            // The library only, never the Gradle plugin: KPKMP.md contract item 6. The KiteVideo
            // renderer's counters and newest-wins slot are atomics, like its three siblings.
            implementation(libs.kotlinx.atomicfu)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

val ffmpegLocalRoot = providers.gradleProperty("kitecodec.ffmpeg.localRoot").map { path ->
    File(path).absoluteFile.normalize()
}

kitecodec {
    ffmpeg {
        source.set(ffmpegLocalRoot.map { FFmpegSource.Local }.orElse(FFmpegSource.System))
        localRoot.fileProvider(ffmpegLocalRoot)
        license.set(FFmpegLicense.LGPL)
    }
}
