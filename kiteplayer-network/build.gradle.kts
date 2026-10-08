plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
}

/*
 * :kiteplayer-network is the Ktor half of the custom AVIO bridge plus the
 * Kotlin adaptive layer's manifest parsing: a MediaIoResolver that
 * makes http and https play with the OS supplying TLS (OkHttp on Android and the JVM,
 * NSURLSession on Apple, the browser itself on the web), and a zero-dependency DASH manifest
 * parser in commonMain.
 *
 * Optional by construction: an app that plays files only never depends on this module and
 * ships no Ktor. Pure Kotlin throughout; the no-new-native-libraries rule is not
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
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    js {
        browser()
        nodejs()
        binaries.library()
    }
    // The web. Ktor's js engine issues `fetch`, so the BROWSER terminates TLS: the same
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
        compileSdk { version = release(37) { minorApiLevel = 2 } }
        minSdk = 26
        withHostTest {}
        optimization {
            consumerKeepRules.publish = true
            consumerKeepRules.file("consumer-rules.pro")
        }
    }

    sourceSets {
        // The bounded XML reader is shared with kiteplayer-subtitles behind this marker (#492).
        all { languageSettings.optIn("io.github.yuroyami.kiteplayer.KitePlayerInternalApi") }
        commonMain.dependencies {
            api(project(":kiteplayer-core"))
            // The DASH door, whose public names this module held before the browser worker needed
            // them without Ktor (#546).
            api(project(":kiteplayer-adaptive"))
            api(libs.ktor.client.core)
            implementation(project(":kiteplayer-subtitles"))
            // The lock of the segment store. The library only, never the Gradle plugin.
            implementation(libs.kotlinx.atomicfu)
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
        getByName("jsMain").dependencies {
            implementation(libs.ktor.client.js)
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
            // HTTP server through the Ktor reader and the byte cache. Test-only dependency,
            // so the shipped module stays backend-free.
            implementation(project(":kiteplayer-ffmpeg"))
        }
    }
}

// The HLS end-to-end test serves the fixtures at the repo root, and a native test's working
// directory is the module, so the location is passed in explicitly.
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    environment("KITEPLAYER_TESTMEDIA", rootDir.resolve("testmedia").absolutePath)
}

// Dokka 2.3 analyses the source set that the JVM and Android share with no classpath of its own,
// and then reports the types of other modules there as unresolved. The JVM's classpath has them.
dokka {
    dokkaSourceSets.matching { it.name == "jvmAndAndroidMain" }.configureEach {
        classpath.from(configurations.named("jvmCompileClasspath"))
    }
}
