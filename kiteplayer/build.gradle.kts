import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
}

/*
 * :kiteplayer is the standard runtime: the common factory, platform media/output defaults,
 * automatic network transport and the libass subtitle typesetter. Android, iOS, macOS, JVM and
 * Wasm have real stacks. Linux and Windows native have the FFmpeg backend but no audio output, and
 * JavaScript has neither, so those answer unavailable. Native view adapters live below this
 * assembly in view-bindings, so a UI-only consumer does not inherit a default runtime or its
 * transport.
 */
// The desktop end-to-end tests play a real file, and a test's working directory is the module
// rather than the repo root, so the location is passed in explicitly.
tasks.withType<Test>().configureEach {
    environment("KITEPLAYER_TESTMEDIA", rootDir.resolve("testmedia").absolutePath)
}
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    environment("KITEPLAYER_TESTMEDIA", rootDir.resolve("testmedia").absolutePath)
}

kotlin {
    explicitApi()
    jvmToolchain(21)

    // Kept explicit because networkMain below adds edges of its own.
    applyDefaultHierarchyTemplate()

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {}

    iosArm64()
    iosSimulatorArm64()
    // The Kotlin/Native desktops. macOS gets the whole stack; Linux and Windows have no audio
    // output and no TLS, so their default answers unavailable and a caller brings an output.
    macosArm64()
    linuxX64()
    linuxArm64()
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

    android {
        namespace = "io.github.yuroyami.kiteplayer.runtime"
        compileSdk = 37
        minSdk = 26
        withHostTest {}
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":kiteplayer-core"))
            // The typesetting engine rides the standard runtime the way the network transport does:
            // adding this entry point is what makes ASS tracks render through libass.
            api(project(":kiteplayer-libass"))
            // The input doors for platform types: a JVM File or stream, an Android content URI or asset,
            // a native file path, an Apple file URL.
            api(project(":kiteplayer-io"))
        }
        // Every target that has HTTP and HTTPS transport, which is every target except Linux and
        // Windows native. One shared set rather than five edges, so a consumer's common code still
        // sees the transport's API when all of its targets have it.
        val networkMain = create("networkMain") {
            dependsOn(commonMain.get())
            dependencies { api(project(":kiteplayer-network")) }
        }
        listOf("androidMain", "appleMain", "jvmMain", "jsMain", "wasmJsMain").forEach {
            getByName(it).dependsOn(networkMain)
        }
        androidMain.dependencies {
            api(project(":kiteplayer-ffmpeg"))
            api(project(":kiteplayer-output"))
            api(project(":kiteplayer-view-bindings"))
        }
        // iOS, macOS, Linux and Windows native. Output has no backend on Linux and Windows; it comes
        // along there for its shared frame layout and subtitle helpers.
        nativeMain.dependencies {
            api(project(":kiteplayer-ffmpeg"))
            api(project(":kiteplayer-output"))
        }
        iosMain.dependencies {
            api(project(":kiteplayer-view-bindings"))
        }
        // macOS has no view bindings. It takes the view module for the picture in picture class.
        macosMain.dependencies {
            api(project(":kiteplayer-view"))
        }
        // The desktop default includes the JNI media backend, audio output and native-view adapter.
        jvmMain.dependencies {
            api(project(":kiteplayer-ffmpeg"))
            api(project(":kiteplayer-output"))
            api(project(":kiteplayer-view-bindings"))
        }
        // The web carries the same two, now that both have a wasmJs target, and the view module for
        // the picture in picture class.
        wasmJsMain.dependencies {
            api(project(":kiteplayer-ffmpeg"))
            api(project(":kiteplayer-output"))
            api(project(":kiteplayer-view"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
