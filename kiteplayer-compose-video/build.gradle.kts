import java.io.File

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
}

/*
 * :kiteplayer-compose-video is the true Compose renderer. It draws frames through Compose, so
 * clip, alpha, transforms and effects apply to the video pixels. It never wraps a native View;
 * that separate presentation mechanism belongs to :kiteplayer-compose-interop.
 *
 * Its frame adapters are KiteFFmpeg implementations per platform: Android and iOS carry the
 * qualified mobile paths, and the JVM target carries the measured software path (KiteFFmpeg's
 * CPU converter into Skia rasters) so the module resolves from a consumer's commonMain even
 * when that consumer also compiles a desktop target. A GPU desktop path is future work.
 *
 * Those three share threadedMain: a worker thread converts each frame and Compose draws the image
 * it builds. The web has no thread to give a worker, and an image built from Kotlin bytes there
 * costs more than a frame's whole budget (#125), so a web target needs a state of its own. Only
 * the public surface is common.
 */
kotlin {
    explicitApi()
    jvmToolchain(21)
    // FrameImagePool is an expect class; the flag only silences the beta note the compiler prints for that shape.
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }
    // Applied by name so iosMain exists when threadedMain is wired below.
    applyDefaultHierarchyTemplate()

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {}

    iosArm64()
    iosSimulatorArm64()
    jvm()

    android {
        namespace = "io.github.yuroyami.kiteplayer.compose.video"
        compileSdk { version = release(37) { minorApiLevel = 2 } }
        minSdk = 26
        withHostTest {}
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    sourceSets {
        val threadedMain = maybeCreate("threadedMain").apply {
            dependsOn(getByName("commonMain"))
        }
        getByName("androidMain").dependsOn(threadedMain)
        getByName("iosMain").dependsOn(threadedMain)
        getByName("jvmMain").dependsOn(threadedMain)

        commonMain.dependencies {
            api(project(":kiteplayer-core"))
            api(compose.runtime)
            api(compose.ui)
            implementation(compose.foundation)
            implementation(libs.kotlinx.atomicfu)
        }
        androidMain.dependencies {
            implementation(project(":kiteplayer-ffmpeg"))
            implementation(project(":kiteplayer-output"))
        }
        iosMain.dependencies {
            implementation(project(":kiteplayer-ffmpeg"))
            implementation(project(":kiteplayer-output"))
        }
        jvmMain.dependencies {
            implementation(project(":kiteplayer-ffmpeg"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            // Skiko's native library, which is what actually rasterises here. Without it the JVM
            // frame path throws NoClassDefFoundError on org.jetbrains.skia.Image, so the desktop
            // renderer would have looked untestable when it is only unloaded.
            implementation(compose.desktop.currentOs)
        }
        getByName("androidDeviceTest").dependencies {
            implementation(kotlin("test"))
            implementation(project(":kiteplayer"))
            implementation(libs.androidx.test.core)
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.ext.junit)
            implementation(libs.androidx.activity.compose)
        }
    }
}

tasks.matching { it.name == "copyAndroidDeviceTestComposeResourcesToAndroidAssets" }.configureEach {
    enabled = false
}


tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    environment("KITEPLAYER_TESTMEDIA", rootDir.resolve("testmedia").absolutePath)
    environment("SIMCTL_CHILD_KITEPLAYER_TESTMEDIA", rootDir.resolve("testmedia").absolutePath)
}
