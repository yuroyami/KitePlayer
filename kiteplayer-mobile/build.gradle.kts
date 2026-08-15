import io.github.yuroyami.kitecodec.gradle.FFmpegLicense
import io.github.yuroyami.kitecodec.gradle.FFmpegSource
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import java.io.File

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
    alias(libs.plugins.kitecodec)
}

/*
 * :kiteplayer-mobile is the default platform assembly. Android and iOS provide the real
 * KiteCodec/output/view stack; JVM, JS and Wasm publish explicit unavailable placeholders so a
 * consumer can keep this dependency in commonMain without inventing an application-side target
 * hierarchy. Rendering widgets and Compose adapters still live in their own modules.
 */
kotlin {
    explicitApi()
    jvmToolchain(21)

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {}

    iosArm64()
    iosSimulatorArm64()
    jvm()

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    js {
        browser()
        nodejs()
        binaries.library()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        nodejs()
    }

    android {
        namespace = "io.github.yuroyami.kiteplayer.mobile"
        compileSdk = 36
        minSdk = 26
        withHostTest {}
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":kiteplayer-core"))
        }
        androidMain.dependencies {
            api(project(":kiteplayer-ffmpeg"))
            api(project(":kiteplayer-output"))
            api(project(":kiteplayer-view"))
        }
        iosMain.dependencies {
            api(project(":kiteplayer-ffmpeg"))
            api(project(":kiteplayer-output"))
            api(project(":kiteplayer-view"))
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
