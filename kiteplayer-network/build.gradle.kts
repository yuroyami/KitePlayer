plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.dokka)
}

/*
 * :kiteplayer-network is the Ktor half of the custom AVIO bridge (KPKMP 17.12 M1) plus the
 * Kotlin adaptive layer's manifest parsing (the un-parked D-4 work): a MediaIoResolver that
 * makes http and https play with the OS supplying TLS (OkHttp on Android and the JVM,
 * NSURLSession on Apple), and a zero-dependency DASH manifest parser in commonMain.
 *
 * Optional by construction: an app that plays files only never depends on this module and
 * ships no Ktor. Pure Kotlin throughout; decision D-7's no-new-native-libraries rule is not
 * even approached.
 */
kotlin {
    explicitApi()
    jvmToolchain(21)

    applyDefaultHierarchyTemplate()

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {
        // Declaring the block is what switches tracking on.
    }

    macosArm64 {
        // The e2e test binary consumes :kiteplayer-ffmpeg, whose klib names the libav*
        // libraries; the kitecodec plugin adds the search path only to that module's own
        // binaries, so the host test here names the System (Homebrew) location itself.
        binaries.all {
            linkerOpts("-L/opt/homebrew/lib")
        }
    }
    iosArm64()
    iosSimulatorArm64()
    jvm()
    android {
        namespace = "io.github.yuroyami.kiteplayer.network"
        compileSdk = 36
        minSdk = 26
        withHostTest {}
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":kiteplayer-core"))
            api(libs.ktor.client.core)
        }
        val jvmAndAndroidMain = maybeCreate("jvmAndAndroidMain").apply {
            dependsOn(getByName("commonMain"))
            dependencies {
                implementation(libs.ktor.client.okhttp)
            }
        }
        getByName("jvmMain").dependsOn(jvmAndAndroidMain)
        getByName("androidMain").dependsOn(jvmAndAndroidMain)
        appleMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
        }
        val macosArm64Test = getByName("macosArm64Test")
        macosArm64Test.dependencies {
            // The end-to-end proof: real FFmpeg demuxes real bytes served by a real local
            // HTTP server through the Ktor reader and the M5 cache. Test-only dependency,
            // so the shipped module stays backend-free.
            implementation(project(":kiteplayer-ffmpeg"))
        }
    }
}
