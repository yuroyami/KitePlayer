import io.github.yuroyami.kitecodec.gradle.FFmpegLicense
import io.github.yuroyami.kitecodec.gradle.FFmpegSource
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.process.CommandLineArgumentProvider
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.testing.Test
import java.io.File

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
    id("io.github.yuroyami.kitecodec") version "0.0.1"
}

/** Execution-time, content-tracked JVM paths without capturing the Gradle script in the test task. */
abstract class KitePlayerJvmTestArguments : CommandLineArgumentProvider {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val jniLibrary: RegularFileProperty

    @get:Internal
    abstract val transcript: RegularFileProperty

    override fun asArguments(): Iterable<String> = listOf(
        "-Dkitecodec.jni.path=${jniLibrary.get().asFile.absolutePath}",
        "-Ds1c.transcript.path=${transcript.get().asFile.absolutePath}",
    )
}

/*
 * :kiteplayer-ffmpeg implements the engine's source and decoder interfaces on top of KiteCodec.
 *
 * It is the only module that knows FFmpeg exists. Everything above it works against the four
 * interfaces in :kiteplayer-core, which is what lets a completely different backend (WebCodecs on
 * the web, a platform decoder, a test fake) take its place without the engine noticing.
 *
 * Kotlin/Native links KiteCodec directly. JVM loads the test-only local JNI dylib, while Android
 * consumes the JNI libraries from KiteCodec's published AAR. This module never rebuilds or packages
 * the desktop JNI library.
 */
// The media fixtures live at the repo root and a native test's working directory is not something
// to rely on, so the location is passed in explicitly.
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    environment("KITEPLAYER_TESTMEDIA", rootDir.resolve("testmedia").absolutePath)
    // `simctl spawn` forwards only SIMCTL_CHILD_-prefixed variables to the spawned binary, so a
    // simulator test sees the plain name only through this twin (S1.e.3).
    environment("SIMCTL_CHILD_KITEPLAYER_TESTMEDIA", rootDir.resolve("testmedia").absolutePath)
}

val localJniPath = providers.gradleProperty("kitecodec.jni.localPath")
tasks.withType<Test>().configureEach {
    systemProperty("KITEPLAYER_TESTMEDIA", rootDir.resolve("testmedia").absolutePath)
}

val transcriptRoot = layout.buildDirectory.dir("s1c-transcripts")
tasks.withType<Test>().configureEach {
    if (name == "jvmTest") {
        val transcript = transcriptRoot.map { it.file("jvm.txt") }
        outputs.file(transcript).withPropertyName("s1cJvmTranscript")
        jvmArgumentProviders.add(
            objects.newInstance<KitePlayerJvmTestArguments>().apply {
                jniLibrary.fileProvider(localJniPath.map { File(it).absoluteFile.normalize() })
                this.transcript.set(transcript)
            },
        )
    }
}
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
        minSdk = 24
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
            implementation("io.github.yuroyami:kitecodec-core:0.0.3")
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

        // The unchanged runtime-gate contract is meaningful on JNI JVM and direct-link native,
        // but Android host tests deliberately use fake drivers and never load a device JNI library.
        val commonTest = getByName("commonTest")
        val jvmAndNativeTest = maybeCreate("jvmAndNativeTest").apply {
            dependsOn(commonTest)
        }
        getByName("jvmTest").dependsOn(jvmAndNativeTest)
        getByName("nativeTest").dependsOn(jvmAndNativeTest)

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
