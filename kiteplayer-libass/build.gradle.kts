import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.dokka)
}

/*
 * :kiteplayer-libass is the OPTIONAL full ASS renderer (KPKMP 17.12 phase L, pulled forward by
 * owner order 2026-08-16, decision D-7): libass and its chain rendering typesetting-grade
 * subtitles through the engine's existing bitmap-cue path. An app that skips this module ships
 * not one extra native byte; the Kotlin dialogue tier in :kiteplayer-subtitles remains the
 * default everywhere.
 *
 * Targets: the macOS host always (Homebrew's libass, the proving ground), and every other
 * Kotlin/Native target this project ships when -Pkiteplayer.libass.root points at a KiteCodec
 * `native-libs/deps` tree holding cross-built ass-chain installs (buildAssChainFor<Target>).
 * That now means the iOS pair AND the Linux and Windows desktop triples.
 *
 * The renderer itself is plain Kotlin/Native over the cinterop bindings, which is why widening
 * this list cost a link line and not a rewrite: it lived in appleMain only because that was the
 * only place targets existed, and it moved to nativeMain unchanged.
 *
 * Android and the JVM are the ones still missing, and for a reason no link line fixes: this module
 * reaches libass through Kotlin/Native cinterop, and both of those are JVM targets that would need
 * a JNI bridge (a C shim, per-ABI .so packaging) exactly like KiteCodec's. wasm needs libass built
 * to emscripten and a binding besides. Those stay the recorded next slices.
 */

val libassDepsRoot: File? = providers.gradleProperty("kiteplayer.libass.root")
    .map { File(it).absoluteFile.normalize() }
    .orNull

/**
 * The Android NDK, for the JNI adapter only.
 *
 * `local.properties` is consulted as well as the environment, because that is where this project
 * already records its SDK and the NDK lives inside it. The native tasks in KiteCodec read only the
 * environment, which is why an Android build there needs ANDROID_NDK_HOME exported by hand.
 */
fun resolveNdk(): File? {
    sequenceOf("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT", "ANDROID_NDK_LATEST_HOME")
        .mapNotNull(System::getenv)
        .map(::File)
        .firstOrNull { it.isDirectory }
        ?.let { return it }
    val fromLocalProperties: File? = rootProject.file("local.properties")
        .takeIf { it.isFile }
        ?.let { file ->
            val loaded = Properties()
            file.inputStream().use { stream -> loaded.load(stream) }
            loaded.getProperty("sdk.dir")?.let { File(it) }
        }
    val sdkDirs: List<File> = listOfNotNull(
        fromLocalProperties,
        File(System.getProperty("user.home"), "Library/Android/sdk"),
        File(System.getProperty("user.home"), "Android/Sdk"),
    )
    return sdkDirs.map { it.resolve("ndk") }.firstOrNull { it.isDirectory }
        ?.listFiles { f: File -> f.isDirectory }?.maxByOrNull { it.name }
}

kotlin {
    explicitApi()
    jvmToolchain(21)

    applyDefaultHierarchyTemplate()

    // Android is the one JVM target so far, and it is a different SHAPE of target rather than one
    // more entry in the list below: it reaches libass through the JNI adapter in native/src, built
    // by BuildLibassJniTask into the jniLibs layout AGP packages. It appears only when its chains
    // exist, on the same rule as the cross targets.
    val androidAbisReady = BuildLibassJniTask.ABIS.all {
        libassDepsRoot?.resolve("${it.depsDirName}/ass-chain/lib/libass.a")?.isFile == true
    }
    val ndkForJni: File? = if (androidAbisReady) resolveNdk() else null
    if (androidAbisReady && ndkForJni != null) {
        android {
            namespace = "io.github.yuroyami.kiteplayer.libass"
            compileSdk = 36
            minSdk = 26
            // Device tests only. A host test could not load the adapter: it is an Android .so, and
            // proving this half means proving the library loads and renders on a real runtime.
            withDeviceTestBuilder {
                sourceSetTreeName = "test"
            }.configure {
                instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
        }
    } else if (libassDepsRoot != null) {
        logger.lifecycle(
            "[kiteplayer-libass] Android target skipped: " +
                if (!androidAbisReady) {
                    "no ass-chain for ${BuildLibassJniTask.ABIS.joinToString { it.depsDirName }}."
                } else {
                    "no Android NDK found (set ANDROID_NDK_HOME or sdk.dir in local.properties)."
                },
        )
    }

    macosArm64()

    // A cross target appears only when its chain is actually ON DISK, not merely when a deps root
    // was named. Declaring a target whose ass-chain is missing produces a link failure at the far
    // end of a long build, naming -lass rather than the absent directory; this way an unbuilt
    // chain is a target that quietly is not there, which is what "optional module" should mean.
    fun chainPresent(dirName: String): Boolean =
        libassDepsRoot?.resolve("$dirName/ass-chain/lib/libass.a")?.isFile == true

    val optionalTargets = mapOf(
        "ios-arm64" to { iosArm64(); Unit },
        "ios-simulator-arm64" to { iosSimulatorArm64(); Unit },
        "linux-x64" to { linuxX64(); Unit },
        "linux-arm64" to { linuxArm64(); Unit },
        "mingw-x64" to { mingwX64(); Unit },
    )
    val missing = optionalTargets.keys.filterNot(::chainPresent)
    optionalTargets.filterKeys(::chainPresent).values.forEach { it() }
    if (libassDepsRoot == null) {
        logger.lifecycle(
            "[kiteplayer-libass] cross targets skipped: set -Pkiteplayer.libass.root to a " +
                "KiteCodec native-libs/deps tree with ass-chain installs to enable them.",
        )
    } else if (missing.isNotEmpty()) {
        logger.lifecycle(
            "[kiteplayer-libass] no ass-chain under $libassDepsRoot for: ${missing.joinToString()}. " +
                "Run :kitecodec-core:buildAssChainFor<Target> for each to enable them.",
        )
    }

    /** The `deps/<target>/ass-chain` directory name for a konan target, or null for the host. */
    fun chainDirName(konanTargetName: String): String? = when (konanTargetName) {
        "ios_arm64" -> "ios-arm64"
        "ios_simulator_arm64" -> "ios-simulator-arm64"
        "linux_x64" -> "linux-x64"
        "linux_arm64" -> "linux-arm64"
        "mingw_x64" -> "mingw-x64"
        else -> null
    }

    targets.withType(KotlinNativeTarget::class.java).configureEach {
        val chainDir = chainDirName(konanTarget.name)
            ?.let { libassDepsRoot?.resolve("$it/ass-chain") }
        val isApple = konanTarget.name.startsWith("ios_") || konanTarget.name.startsWith("macos_")
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
            if (chainDir == null) {
                linkerOpts("-L/opt/homebrew/lib", "-lass")
                return@all
            }
            // The chain is the same four archives everywhere, dependents first, because a GNU
            // linker resolves static archives left to right and libass draws from all three below
            // it. On the GNU targets they additionally go in a GROUP: harfbuzz and freetype
            // reference each other, and a single left-to-right pass cannot close a cycle. Apple's
            // ld needs no group (it re-scans) and does not understand the flag.
            linkerOpts("-L${chainDir.resolve("lib")}")
            if (isApple) {
                linkerOpts("-lass", "-lharfbuzz", "-lfreetype", "-lfribidi")
            } else {
                // Named by ABSOLUTE PATH rather than -l, and inside a group. `-l` left the GNU
                // targets resolving `FT_*` against nothing at all while reporting no missing
                // library, which is what a lookup that quietly picked something else looks like.
                // A path cannot be mistaken for another file, and the group closes the
                // harfbuzz/freetype cycle that one left-to-right pass cannot.
                val lib = chainDir.resolve("lib")
                linkerOpts("-Wl,--start-group")
                listOf("libass.a", "libharfbuzz.a", "libfreetype.a", "libfribidi.a").forEach {
                    linkerOpts(lib.resolve(it).absolutePath)
                }
                linkerOpts("-Wl,--end-group")
            }
            // What differs per platform is only what the C++ half of harfbuzz and the text stack
            // need underneath: Apple ships its font provider as frameworks and its C++ runtime as
            // libc++, the GNU targets link libstdc++ and their own math library.
            if (isApple) {
                linkerOpts(
                    "-lz", "-liconv", "-lc++",
                    "-framework", "CoreText",
                    "-framework", "CoreFoundation",
                    "-framework", "CoreGraphics",
                )
            } else {
                // -lz is not optional and the linkage test is what proved it: freetype is built
                // against the system zlib, so libfreetype.a carries undefined `inflate*` that
                // nothing else in the chain resolves. -lstdc++ is harfbuzz's, which is the only
                // C++ member; -lm is freetype's.
                linkerOpts("-lz", "-lstdc++", "-lm")
                if (konanTarget.name == "mingw_x64") {
                    // Windows is the one platform where libass HAS a system font provider, so
                    // unlike the Linux build it is not font-less: it enumerates through GDI and
                    // shapes through DirectWrite, and those are OS libraries rather than chain
                    // members. Without them the link ends on CreateFontIndirectW and friends.
                    linkerOpts("-lgdi32", "-ldwrite", "-lole32", "-luuid", "-luser32")
                    // libass recodes legacy-encoded scripts through GNU libiconv here, exactly as
                    // it does on Apple; the GNU spelling of the symbols is `libiconv_*`.
                    linkerOpts("-liconv")
                }
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
        if (androidAbisReady && ndkForJni != null) {
            getByName("androidDeviceTest").dependencies {
                implementation(kotlin("test"))
                implementation(libs.androidx.test.core)
                implementation(libs.androidx.test.runner)
                implementation(libs.androidx.test.ext.junit)
            }
        }
    }
}

// The JNI adapter, and the one wiring that puts it in the AAR. The task's output root sits one
// level ABOVE the ABI directories on purpose: `addGeneratedSourceDirectory` packages `arm64-v8a/`
// and `x86_64/` from underneath it, which is the layout Android's loader expects to find.
if (libassDepsRoot != null && BuildLibassJniTask.ABIS.all {
        libassDepsRoot.resolve("${it.depsDirName}/ass-chain/lib/libass.a").isFile
    }
) {
    resolveNdk()?.let { ndk ->
        val buildJni = tasks.register<BuildLibassJniTask>("buildLibassJni") {
            sourceFile.set(project.file("native/src/libass_jni.c"))
            assChainRoot.set(libassDepsRoot)
            ndkDirectory.set(ndk.absolutePath)
            outputDir.set(layout.buildDirectory.dir("libass-jni"))
        }
        extensions.configure<KotlinMultiplatformAndroidComponentsExtension> {
            onVariants { variant ->
                val jniLibs = checkNotNull(variant.sources.jniLibs) {
                    "AGP exposed no jniLibs sources for Android variant ${variant.name}."
                }
                jniLibs.addGeneratedSourceDirectory(buildJni, BuildLibassJniTask::outputDir)
            }
        }
    }
}
