import io.github.yuroyami.kitecodec.gradle.FFmpegLicense
import io.github.yuroyami.kitecodec.gradle.FFmpegSource
import java.io.File

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
    alias(libs.plugins.kitecodec)
}

/*
 * :kiteplayer-ffmpeg implements the engine's source and decoder interfaces on top of KiteCodec.
 *
 * It is the only module that knows FFmpeg exists. Everything above it works against the four
 * interfaces in :kiteplayer-core, which is what lets a completely different backend (WebCodecs on
 * the web, a platform decoder, a test fake) take its place without the engine noticing.
 *
 * Kotlin/Native links KiteCodec directly, while Android consumes the JNI libraries from KiteCodec's
 * published AAR. Public JVM is deliberately a placeholder until a supported desktop backend ships:
 * it compiles the common API and fails backend operations with KiteCodec's typed Unsupported error.
 */
// The media fixtures live at the repo root and a native test's working directory is not something
// to rely on, so the location is passed in explicitly.
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    environment("KITEPLAYER_TESTMEDIA", rootDir.resolve("testmedia").absolutePath)
    // `simctl spawn` forwards only SIMCTL_CHILD_-prefixed variables to the spawned binary, so a
    // simulator test sees the plain name only through this twin (S1.e.3).
    environment("SIMCTL_CHILD_KITEPLAYER_TESTMEDIA", rootDir.resolve("testmedia").absolutePath)
}

val transcriptRoot = layout.buildDirectory.dir("s1c-transcripts")
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    if (name == "macosArm64Test") {
        val transcript = transcriptRoot.map { it.file("macosArm64.txt") }
        outputs.file(transcript).withPropertyName("s1cMacosArm64Transcript")
        doFirst {
            environment("S1C_TRANSCRIPT_PATH", transcript.get().asFile.absolutePath)
            transcript.get().asFile.parentFile.mkdirs()
        }
    }
}

val ffmpegLocalRoot = providers.gradleProperty("kitecodec.ffmpeg.localRoot").map { path ->
    File(path).absoluteFile.normalize()
}

kotlin {
    explicitApi()
    jvmToolchain(21)

    // Keep Kotlin's normal Apple/native hierarchy while adding the one explicit JVM+Android share.
    applyDefaultHierarchyTemplate()

    // Public API tracking, the same as every other library module here. `updateKotlinAbi` refreshes
    // api/*.api, `checkKotlinAbi` fails the build when the committed dump and the code disagree.
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {
        // Declaring the block is what switches tracking on.
    }

    macosArm64()
    iosArm64()
    iosSimulatorArm64()
    jvm()
    android {
        namespace = "io.github.yuroyami.kiteplayer.ffmpeg"
        compileSdk = 36
        minSdk = 26
        withHostTest {}
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":kiteplayer-core"))
            // The text subtitle parsers (S4.c). Pure Kotlin; the decoder below is a thin shim
            // from packet payloads onto them.
            implementation(project(":kiteplayer-subtitles"))
            // api, not implementation: KiteCodecVideoFrame publicly exposes kitecodec.Frame, and
            // the phone/compose modules cast to it. Hiding the dependency made that public type
            // invisible to consumers compiling against this module's ABI (audit P1-25).
            api("io.github.yuroyami:kitecodec-core:0.0.6")
            implementation(libs.kotlinx.atomicfu)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        val commonMain = getByName("commonMain")
        val jvmAndAndroidMain = maybeCreate("jvmAndAndroidMain").apply {
            dependsOn(commonMain)
        }
        getByName("jvmMain").dependsOn(jvmAndAndroidMain)
        getByName("androidMain").dependsOn(jvmAndAndroidMain)

        // Real-media and FFmpeg-runtime tests belong only to direct-link native. Public JVM consumes
        // KiteCodec's unavailable placeholder, while Android host tests use fake drivers and never load
        // a device JNI library.
        val commonTest = getByName("commonTest")
        val nativeBackendTest = maybeCreate("nativeBackendTest").apply {
            dependsOn(commonTest)
        }
        getByName("nativeTest").dependsOn(nativeBackendTest)

        getByName("nativeTest").dependencies {
            // Test only, and only for the tests that drive the whole player: it needs an output backend to
            // have a clock and an audio device, and this module is the one place where real media, the real
            // FFmpeg backend and a real device can all be reached at once.
            implementation(project(":kiteplayer-output"))
        }
        getByName("androidDeviceTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.androidx.test.core)
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.ext.junit)
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
