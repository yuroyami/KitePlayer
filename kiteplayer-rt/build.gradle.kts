import io.github.yuroyami.kiteplayer.buildtools.CompileKiteRtTask
import org.gradle.api.tasks.PathSensitivity
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
// Named explicitly because inside a Gradle Kotlin script `java` resolves to the java extension,
// so `java.io.File(...)` does not compile.
import java.io.File

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.vanniktech.publish)
}

/*
 * :kiteplayer-rt is KitePlayer's real-time audio core, written in C.
 *
 * WHY IT IS ITS OWN MODULE, AND WHY IT IS HERE RATHER THAN IN KiteCodec. A lock-free audio ring has
 * nothing to do with FFmpeg. Putting it in `kitecodec-c` would make KitePlayer's real-time core a
 * transitive consequence of a codec dependency, and would make a private player's scheduling
 * decisions part of a public binding's surface. Plan section 15.2 B1.7 step 1 settles it: the
 * library is `kiteplayer-rt`, in KitePlayer, with the symbol prefix `kprt_`.
 *
 * WHAT IT CONTAINS. B1.7 put the ring here, built and proved against the Kotlin ring and deliberately
 * off the device path. B1.8 added the CoreAudio device callback and the device glue beside it, which
 * was the one time in B1 that the shipped real-time audio path changed, and since then this module IS
 * the macOS audio path: the ring holds the samples and a `static` C function renders them. The device
 * implementation is macOS only and every other target's entry points refuse with
 * `KPRT_SINK_UNSUPPORTED_PLATFORM`.
 *
 * NO KOTLIN DECLARATIONS OR CALLABLE API OF ITS OWN, ON PURPOSE. The Kotlin wrapper,
 * `NativeAudioRing`, lives in `kiteplayer-core`'s `nativeMain`, because it implements
 * `internal interface AudioRingHandle` and an internal interface cannot be implemented from another
 * module. A package-only source makes publication carry the declaration-free main klib required by
 * the Kotlin publication model. This module exposes exactly one callable surface beside it: the
 * `kitert` cinterop klib. Its own `macosArm64Test` source set holds the binding-level tests, which are
 * the ones that would catch a def or archive wiring mistake before the engine ever sees it.
 *
 * EVERY NATIVE TARGET kiteplayer-core DECLARES. Seventeen of them, because `NativeAudioRing` sits in
 * a shared `nativeMain` source set and a Gradle dependency that resolves for only some targets is a
 * dependency that fails at whichever target nobody compiled. All 17 were measured compiling the real
 * ring source on this machine; that is level 7 evidence in the terms of plan section 2 and says
 * nothing at all about behaviour on any target other than macOS arm64.
 */
kotlin {
    explicitApi()
    jvmToolchain(21)

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {
        // Declaring the block is what switches tracking on.
    }

    val nativeTargets: List<KotlinNativeTarget> = listOf(
        iosSimulatorArm64(), iosArm64(), iosX64(),
        macosArm64(),
        tvosArm64(), tvosSimulatorArm64(),
        watchosArm32(), watchosArm64(), watchosDeviceArm64(), watchosSimulatorArm64(),
        androidNativeArm32(), androidNativeArm64(), androidNativeX64(), androidNativeX86(),
        linuxX64(), linuxArm64(),
        mingwX64(),
    )

    val cSourceDir = layout.projectDirectory.dir("native/src")
    val cIncludeDir = layout.projectDirectory.dir("native/include")

    nativeTargets.forEach { target ->
        val konanName = target.konanTarget.name
        val archiveDir = layout.buildDirectory.dir("kiteplayer-rt-c/$konanName")

        val compileC = tasks.register<CompileKiteRtTask>(
            "compileKiteRtCFor${konanName.toGradleSuffix()}",
        ) {
            konanTargetName.set(konanName)
            sourceDir.set(cSourceDir)
            includeDir.set(cIncludeDir)
            // java.io.File and not project.file(...): the latter captures a reference to this script
            // inside the provider, which the configuration cache refuses to serialize with
            // "cannot serialize Gradle script object references".
            konanDataDir.fileProvider(
                providers.environmentVariable("KONAN_DATA_DIR")
                    .orElse(providers.systemProperty("user.home").map { home -> "$home/.konan" })
                    .map { path -> File(path) },
            )
            outputDir.set(archiveDir)
        }

        target.compilations.getByName("main").cinterops {
            create("kitert") {
                defFile(project.file("src/nativeInterop/cinterop/kitert.def"))
                includeDirs.allHeaders(cIncludeDir)
                compilerOpts("-I${cIncludeDir.asFile.absolutePath}")
                // The archive directory, per target, as a -libraryPath rather than a def line: a
                // def-relative library path resolves against the project directory, and each target
                // has its own directory (register item B1-11).
                extraOpts("-libraryPath", archiveDir.get().asFile.absolutePath)
            }
        }

        /*
         * cinterop embeds the archive, so it has to exist first AND be a declared input of the
         * cinterop task.
         *
         * The dependency alone is not enough. Measured in KiteCodec at B1.3 and recorded in plan
         * section 15.0: editing only a C body re-executes the compile and writes a new archive, and
         * the cinterop task then reports UP-TO-DATE and keeps the STALE archive inside the klib, with
         * the configuration cache on or off. Gradle says why under `--info`: "CInterop task uses
         * custom Up-To-Date check for content of headers instead of Gradle mechanisms", and that
         * check covers the def and the headers, not a library the def merely names. `inputs.files` on
         * the archive fixes it, because an input change makes a task out of date whatever its own
         * predicate says. The missing-archive direction never needed this: cinterop fails loudly with
         * a non-zero exit when `staticLibraries` cannot be found.
         *
         * `matching { }.configureEach { }` rather than `named(...)`: the cinterop task is registered
         * by the Kotlin plugin after this block runs, and a filtered live collection covers tasks
         * added later while `named` would fail on a task that does not exist yet.
         */
        val cinteropTaskName = "cinteropKitert${target.name.replaceFirstChar { it.uppercaseChar() }}"
        tasks.matching { it.name == cinteropTaskName }.configureEach {
            dependsOn(compileC)
            inputs.files(compileC.map { c -> c.outputDir.file(CompileKiteRtTask.ARCHIVE_NAME) })
                .withPropertyName("kiteRtCArchive")
                .withPathSensitivity(PathSensitivity.NAME_ONLY)
        }
    }

    sourceSets {
        all {
            languageSettings {
                optIn("kotlin.RequiresOptIn")
                optIn("kotlinx.cinterop.ExperimentalForeignApi")
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

/** `macos_arm64` to `MacosArm64`, so a task name reads the way every other Gradle task name does. */
fun String.toGradleSuffix(): String =
    split('_').joinToString("") { part -> part.replaceFirstChar { it.uppercaseChar() } }
