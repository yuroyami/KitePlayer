import io.github.yuroyami.kitecodec.gradle.FFmpegLicense
import io.github.yuroyami.kitecodec.gradle.FFmpegSource

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlinx.atomicfu)
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
 * the JNI bridge described in KITEPLAYER.md section 15, and that work belongs in KiteCodec.
 */
kotlin {
    explicitApi()
    jvmToolchain(21)

    macosArm64()

    sourceSets {
        commonMain.dependencies {
            api(projects.kiteplayerCore)
            implementation("io.github.yuroyami:kitecodec-core:0.0.1")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

kitecodec {
    ffmpeg {
        // The development machine has FFmpeg 8.0 from Homebrew, which is the route KiteCodec's own
        // CI uses for a consumer project. Prebuilt is the plugin's default and cannot work yet: the
        // release assets it downloads do not exist. See KITEPLAYER.md section 5.1.
        source.set(FFmpegSource.System)
        license.set(FFmpegLicense.LGPL)
    }
}
