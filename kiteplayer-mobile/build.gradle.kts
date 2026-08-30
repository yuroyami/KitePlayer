import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import java.io.File

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
}

/*
 * :kiteplayer-mobile is the default platform assembly. Android and iOS provide the real
 * KiteFFmpeg/output/view stack; JVM, JS and Wasm publish explicit unavailable placeholders so a
 * consumer can keep this dependency in commonMain without inventing an application-side target
 * hierarchy. Rendering widgets and Compose adapters still live in their own modules.
 */
// The desktop end-to-end test plays a real file, and a Test task's working directory is the module
// rather than the repo root, so the location is passed in explicitly like the native tasks do.
tasks.withType<Test>().configureEach {
    environment("KITEPLAYER_TESTMEDIA", rootDir.resolve("testmedia").absolutePath)
}

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
        // The desktop JVM stopped being a placeholder in phase W: KiteFFmpeg's jvm variant carries
        // the JNI adapter, and :kiteplayer-output has a SourceDataLine sink and an AWT rasterizer.
        // The view module joined them on 2026-08-30: desktop now has a real platform video view,
        // an AWT canvas painted off the Compose clock, so this module supplies its renderer
        // adapter exactly as it does for Android and iOS. The comment that used to sit here said
        // a desktop consumer can only draw through Compose, which is no longer true.
        jvmMain.dependencies {
            api(project(":kiteplayer-ffmpeg"))
            api(project(":kiteplayer-output"))
            api(project(":kiteplayer-view"))
        }
        // The web carries the same two, now that both have a wasmJs target.
        wasmJsMain.dependencies {
            api(project(":kiteplayer-ffmpeg"))
            api(project(":kiteplayer-output"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

