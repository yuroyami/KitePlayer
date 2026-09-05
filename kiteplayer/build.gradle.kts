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
 * automatic network transport. Android, iOS, JVM and Wasm have real stacks; JavaScript retains an
 * explicit unavailable facade. Native view adapters live below this assembly in view-bindings,
 * so a UI-only consumer does not inherit a default runtime or its transport.
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
        namespace = "io.github.yuroyami.kiteplayer.runtime"
        compileSdk = 36
        minSdk = 26
        withHostTest {}
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":kiteplayer-core"))
            api(project(":kiteplayer-network"))
        }
        androidMain.dependencies {
            api(project(":kiteplayer-ffmpeg"))
            api(project(":kiteplayer-output"))
            api(project(":kiteplayer-view-bindings"))
        }
        iosMain.dependencies {
            api(project(":kiteplayer-ffmpeg"))
            api(project(":kiteplayer-output"))
            api(project(":kiteplayer-view-bindings"))
        }
        // The desktop default includes the JNI media backend, audio output and native-view adapter.
        jvmMain.dependencies {
            api(project(":kiteplayer-ffmpeg"))
            api(project(":kiteplayer-output"))
            api(project(":kiteplayer-view-bindings"))
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
