import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
}

/*
 * :kiteplayer-io holds the input doors that need a platform type, such as a JVM File or stream, an
 * Android content URI or asset, a file path on Apple and Linux, or an Apple file URL. Each door is a
 * MediaIoFactory, so the engine never learns that doors exist. They live here and not in the core,
 * because the core calls no platform API.
 *
 * The target list is the subtitles module's, the widest pure-Kotlin list in the tree, so this module
 * never narrows what a module above it can declare. A target with no door yet still needs a klib to
 * publish, and a target with no source file makes none, so commonMain must never be empty.
 */
kotlin {
    explicitApi()
    jvmToolchain(21)

    // Keeps Kotlin's normal Apple and native hierarchy beside the explicit JVM and Android shares below.
    applyDefaultHierarchyTemplate()

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {
        // Declaring the block is what switches tracking on.
    }

    android {
        namespace = "io.github.yuroyami.kiteplayer.io"
        compileSdk = 36
        minSdk = 26
        withHostTest {}
        // The device tests see commonTest, so the contract tests run against the real descriptor classes.
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    iosSimulatorArm64(); iosArm64(); iosX64()
    macosArm64()
    tvosArm64(); tvosSimulatorArm64()
    watchosArm32(); watchosArm64(); watchosDeviceArm64(); watchosSimulatorArm64()
    androidNativeArm32(); androidNativeArm64(); androidNativeX64(); androidNativeX86()
    linuxX64(); linuxArm64()
    mingwX64()
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

    sourceSets {
        commonMain.dependencies {
            api(project(":kiteplayer-core"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        // The JVM and Android doors share java.io and java.nio, and so do their tests, which the
        // Android host test runs as well as the JVM one.
        val jvmAndAndroidMain = maybeCreate("jvmAndAndroidMain").apply { dependsOn(getByName("commonMain")) }
        getByName("jvmMain").dependsOn(jvmAndAndroidMain)
        getByName("androidMain").dependsOn(jvmAndAndroidMain)
        val jvmAndAndroidTest = maybeCreate("jvmAndAndroidTest").apply { dependsOn(getByName("commonTest")) }
        getByName("jvmTest").dependsOn(jvmAndAndroidTest)
        getByName("androidHostTest").dependsOn(jvmAndAndroidTest)
        getByName("androidDeviceTest").dependsOn(jvmAndAndroidTest)
        getByName("androidDeviceTest").dependencies {
            implementation(libs.androidx.test.core)
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.ext.junit)
        }
        // The path door reads with pread, which the Apple and Linux targets share. Android native is
        // left out because pread takes a 32-bit offset on its two 32-bit targets, and Windows has no
        // pread. Those targets have no path door.
        val posixMain = maybeCreate("posixMain").apply { dependsOn(getByName("nativeMain")) }
        getByName("appleMain").dependsOn(posixMain)
        getByName("linuxMain").dependsOn(posixMain)
        val posixTest = maybeCreate("posixTest").apply { dependsOn(getByName("nativeTest")) }
        getByName("appleTest").dependsOn(posixTest)
        getByName("linuxTest").dependsOn(posixTest)
    }
}
