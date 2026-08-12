import io.github.yuroyami.kitecodec.gradle.FFmpegLicense
import io.github.yuroyami.kitecodec.gradle.FFmpegSource
import java.io.File

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
    // Not optional. KiteCodec's cinterop declares bare -lavformat and friends with no -L, so
    // every module whose own link tasks pull FFmpeg (here: the iOS test binaries) needs this
    // plugin to supply the library directory. Same rule and comment as :kiteplayer-sample.
    id("io.github.yuroyami.kitecodec") version "0.0.1"
}

/*
 * :kiteplayer-phone is the phone aggregate: one coordinate, two reusable views, zero policy.
 *
 * It api-depends on :kiteplayer-ffmpeg and :kiteplayer-output so a phone application adds ONE
 * implementation line and has the playable stack. The views own exactly the wiring every phone
 * app would otherwise repeat: a surface lifecycle, a renderer built at the right moment, a
 * close in the right order. Every playback decision stays in :kiteplayer-core, and this module
 * stays Compose-free so a plain-View consumer never pulls Compose (that is :kiteplayer-compose,
 * a separate optional module, per D-6).
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
        namespace = "io.github.yuroyami.kiteplayer.phone"
        compileSdk = 36
        minSdk = 24
        withHostTest {}
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":kiteplayer-ffmpeg"))
            api(project(":kiteplayer-output"))
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
