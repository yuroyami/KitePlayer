import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import java.io.File

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.dokka)
}

/*
 * :kiteplayer-libass is the OPTIONAL full ASS renderer (KPKMP 17.12 phase L, pulled forward by
 * owner order 2026-08-16, decision D-7): libass and its chain rendering typesetting-grade
 * subtitles through the engine's existing bitmap-cue path. An app that skips this module ships
 * not one extra native byte; the Kotlin dialogue tier in :kiteplayer-subtitles remains the
 * default everywhere.
 *
 * Targets are deliberately narrow today: the macOS host (Homebrew's libass, the proving
 * ground) always, and the iOS pair only when -Pkiteplayer.libass.root points at a KiteCodec
 * `native-libs/deps` tree holding cross-built ass-chain installs (buildAssChainFor<Target>).
 * The Android half needs a JNI bridge exactly like KiteCodec's and is the recorded next slice.
 */

val libassDepsRoot: File? = providers.gradleProperty("kiteplayer.libass.root")
    .map { File(it).absoluteFile.normalize() }
    .orNull

kotlin {
    explicitApi()
    jvmToolchain(21)

    applyDefaultHierarchyTemplate()

    macosArm64()
    if (libassDepsRoot != null) {
        iosArm64()
        iosSimulatorArm64()
    } else {
        logger.lifecycle(
            "[kiteplayer-libass] iOS targets skipped: set -Pkiteplayer.libass.root to a KiteCodec " +
                "native-libs/deps tree with ass-chain installs to enable them.",
        )
    }

    targets.withType(KotlinNativeTarget::class.java).configureEach {
        val chainDir = when (konanTarget.name) {
            "ios_arm64" -> libassDepsRoot?.resolve("ios-arm64/ass-chain")
            "ios_simulator_arm64" -> libassDepsRoot?.resolve("ios-simulator-arm64/ass-chain")
            else -> null
        }
        compilations.getByName("main").cinterops.create("libass") {
            defFile(project.file("src/nativeInterop/cinterop/libass.def"))
            if (chainDir != null) {
                includeDirs(chainDir.resolve("include"))
            } else {
                // The macOS host proves the module against Homebrew's libass, the same source
                // the desktop FFmpeg profile links its text stack from.
                includeDirs("/opt/homebrew/include")
            }
        }
        binaries.all {
            if (chainDir != null) {
                linkerOpts(
                    "-L${chainDir.resolve("lib")}",
                    "-lass", "-lharfbuzz", "-lfreetype", "-lfribidi",
                    "-lz", "-liconv", "-lc++",
                    "-framework", "CoreText",
                    "-framework", "CoreFoundation",
                    "-framework", "CoreGraphics",
                )
            } else {
                linkerOpts("-L/opt/homebrew/lib", "-lass")
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":kiteplayer-core"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
