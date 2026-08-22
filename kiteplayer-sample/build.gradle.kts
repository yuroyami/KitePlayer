import java.io.File

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // Not optional. The Maven coordinate alone does not link: KiteCodec's cinterop declares its
    // linker options as bare -lavformat and friends with no -L, so every module whose link task
    // pulls FFmpeg in needs this plugin to supply the library directory.
}

/*
 * The sample is how a claim of working playback is checked rather than asserted. macOS keeps its
 * command-line player; the iOS targets provide the private UIKit host used by the named simulator
 * smoke, without turning that host into a reusable application surface.
 */
kotlin {
    jvmToolchain(21)

    macosArm64 {
        binaries.executable {
            entryPoint = "io.github.yuroyami.kiteplayer.sample.main"
            baseName = "kiteplayer"
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "KitePlayerSample"
            isStatic = true
        }
    }

    sourceSets {
        macosArm64Main.dependencies {
            implementation(project(":kiteplayer-ffmpeg"))
            implementation(project(":kiteplayer-output"))
        }
        iosMain.dependencies {
            // The mobile aggregate carries the default backend, output and native view.
            implementation(project(":kiteplayer-mobile"))
        }
    }
}

