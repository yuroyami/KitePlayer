import io.github.yuroyami.kitecodec.gradle.FFmpegLicense
import io.github.yuroyami.kitecodec.gradle.FFmpegSource

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // Not optional. The Maven coordinate alone does not link: KiteCodec's cinterop declares its
    // linker options as bare -lavformat and friends with no -L, so every module whose link task
    // pulls FFmpeg in needs this plugin to supply the library directory.
    id("io.github.yuroyami.kitecodec") version "0.0.1"
}

/*
 * The sample is how a claim of working playback is checked rather than asserted. It is a plain CLI:
 * point it at a file, hear the audio, watch the position advance.
 */
kotlin {
    jvmToolchain(21)

    macosArm64 {
        binaries.executable {
            entryPoint = "io.github.yuroyami.kiteplayer.sample.main"
            baseName = "kiteplayer"
        }
    }

    sourceSets {
        macosArm64Main.dependencies {
            implementation(projects.kiteplayerFfmpeg)
            implementation(projects.kiteplayerOutput)
        }
    }
}

kitecodec {
    ffmpeg {
        source.set(FFmpegSource.System)
        license.set(FFmpegLicense.LGPL)
    }
}
