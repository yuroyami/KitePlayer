import io.github.yuroyami.kitecodec.gradle.FFmpegLicense
import io.github.yuroyami.kitecodec.gradle.FFmpegSource
import java.io.File

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
    id("io.github.yuroyami.kitecodec") version "0.0.1"
}

/*
 * :kiteplayer-ffmpeg implements the engine's source and decoder interfaces on top of KiteCodec.
 *
 * It is the only module that knows FFmpeg exists. Everything above it works against the four
 * interfaces in :kiteplayer-core, which is what lets a completely different backend (WebCodecs on
 * the web, a platform decoder, a test fake) take its place without the engine noticing.
 *
 * Targets follow KiteCodec's reach, which today is Kotlin/Native only. Android and JVM desktop need
 * a JNI bridge over the same C helper layer, and that work belongs in KiteCodec.
 */
// The media fixtures live at the repo root and a native test's working directory is not something
// to rely on, so the location is passed in explicitly.
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    environment("KITEPLAYER_TESTMEDIA", rootDir.resolve("testmedia").absolutePath)
}

val ffmpegLocalRoot = providers.gradleProperty("kitecodec.ffmpeg.localRoot").map { path ->
    File(path).absoluteFile.normalize()
}

kotlin {
    explicitApi()
    jvmToolchain(21)

    // Public API tracking, the same as every other library module here. `updateKotlinAbi` refreshes
    // api/*.api, `checkKotlinAbi` fails the build when the committed dump and the code disagree.
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {
        // Declaring the block is what switches tracking on.
    }

    macosArm64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":kiteplayer-core"))
            implementation("io.github.yuroyami:kitecodec-core:0.0.2")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            // Test only, and only for the tests that drive the whole player: it needs an output backend to
            // have a clock and an audio device, and this module is the one place where real media, the real
            // FFmpeg backend and a real device can all be reached at once.
            implementation(project(":kiteplayer-output"))
        }
    }
}

kitecodec {
    ffmpeg {
        // The standing host gate uses Homebrew. Phone links pass a complete local tree explicitly;
        // this provider branch stays lazy and Local registers no network work.
        source.set(ffmpegLocalRoot.map { FFmpegSource.Local }.orElse(FFmpegSource.System))
        localRoot.fileProvider(ffmpegLocalRoot)
        license.set(FFmpegLicense.LGPL)
    }
}
