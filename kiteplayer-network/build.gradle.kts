plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.dokka)
}

/*
 * :kiteplayer-network is the Ktor half of the custom AVIO bridge plus the
 * Kotlin adaptive layer's manifest parsing (the un-parked D-4 work): a MediaIoResolver that
 * makes http and https play with the OS supplying TLS (OkHttp on Android and the JVM,
 * NSURLSession on Apple, the browser itself on the web), and a zero-dependency DASH manifest
 * parser in commonMain.
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
        // libraries; the kiteffmpeg plugin adds the search path only to that module's own
        // binaries, so the host test here names the System (Homebrew) location itself.
        binaries.all {
            linkerOpts("-L/opt/homebrew/lib")
        }
    }
    iosArm64()
    iosSimulatorArm64()
    jvm()
    // The web (17.14). Ktor's js engine issues `fetch`, so the BROWSER terminates TLS: the same
    // arrangement every other target has, with the one TLS implementation nobody has to maintain.
    // It is also the target where https matters most, since loading media over the network is
    // what a web player does.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        nodejs()
    }
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
        getByName("wasmJsMain").dependencies {
            implementation(libs.ktor.client.js)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        /*
         * Tests that stand a real Ktor server up and block on it, for the targets that can.
         *
         * They were commonTest until the web target arrived and could compile neither half:
         * `runBlocking` does not exist where the only thread is the event loop, and ktor-server has
         * no wasm artifact to resolve. Splitting them keeps the web honest instead of thinning what
         * the other targets prove. What stays in commonTest is what is genuinely common: the DASH
         * manifest parser, which touches no socket and runs everywhere including the browser.
         */
        val serverBackedTest = maybeCreate("serverBackedTest").apply {
            dependsOn(getByName("commonTest"))
            dependencies {
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.cio)
            }
        }
        getByName("appleTest").dependsOn(serverBackedTest)
        getByName("jvmTest").dependsOn(serverBackedTest)
        getByName("androidHostTest").dependsOn(serverBackedTest)
        val macosArm64Test = getByName("macosArm64Test")
        macosArm64Test.dependencies {
            // The end-to-end proof: real FFmpeg demuxes real bytes served by a real local
            // HTTP server through the Ktor reader and the M5 cache. Test-only dependency,
            // so the shipped module stays backend-free.
            implementation(project(":kiteplayer-ffmpeg"))
        }
    }
}
