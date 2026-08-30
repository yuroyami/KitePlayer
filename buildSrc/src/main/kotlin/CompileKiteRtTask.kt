package io.github.yuroyami.kiteplayer.buildtools

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

/**
 * Compiles every `.c` file under `kiteplayer-rt/native/src` into one static archive per
 * Kotlin/Native target and leaves it at `<outputDir>/libkiteplayerrt.a`, where the `kitert` cinterop
 * picks it up through the `staticLibraries = libkiteplayerrt.a` line of `kitert.def` plus a
 * `-libraryPath` pointing here.
 *
 * **This is a deliberate copy of KiteFFmpeg's `CompileKiteFFmpegCTask`, not a shared class.** Plan
 * section 15.2 B1.7 step 1 states the rule: "the same shape as KiteFFmpeg's, and the two must not be
 * shared across repositories". KiteFFmpeg is a public FFmpeg binding and KitePlayer is a private
 * player. Sharing the build task would make one a build dependency of the other, in the direction
 * that turns KitePlayer's real-time audio core into a transitive consequence of a codec dependency,
 * which is the same argument that put the ring in `kiteplayer-rt` rather than in `kitecodec-c`. The
 * two differ in substance as well: this one compiles a translation unit that includes no third party
 * header at all, so it has no FFmpeg include directories and no build-provenance defines, and it
 * knows six target triples KiteFFmpeg's does not because `kiteplayer-core` declares tvOS and watchOS
 * and KiteFFmpeg does not.
 *
 * **Why the compiler is konan's and not Apple's.** The archive is embedded in a klib and is linked
 * into whatever the consumer builds by Kotlin/Native's own linker. Using the compiler Kotlin/Native
 * itself uses, `<konan data>/dependencies/<llvm package>/bin/clang`, keeps the object format, the
 * target triples and the runtime assumptions identical to everything else in that link. Apple clang
 * is the right choice for the host test binaries of `native/scripts/build-host.sh` and the wrong one
 * here.
 *
 * **Why no make, no cmake and no ninja** (register item B1-15): cmake is not installed on the
 * proving machine, and GNU make starts a comment at an unescaped `#` while both repositories live
 * under `/Users/macbook/StudioProjects/#Kite/`. Driving clang and `llvm-ar` directly is the only
 * form that is both available and safe under this path.
 *
 * **Two properties are load bearing rather than incidental.**
 *
 *  - [outputDir] is keyed by the konan target name and is never shared between targets, and the
 *    task refuses to run when the directory it was handed is not named after its own target. That is
 *    register item B1-11: cinterop embeds a wrong-architecture archive without a word of complaint
 *    and it fails only at the consumer's final link, with
 *    `ld: archive member '/' not a mach-o file`. The producer must catch it, so every object is run
 *    past [verifyObjectArchitecture] before it is archived.
 *  - `xcrun` runs inside [compile] and never at configuration time. This project has the
 *    configuration cache on (`gradle.properties`), and starting an external process while
 *    configuring is one of the things it rejects outright.
 */
abstract class CompileKiteRtTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {

    /**
     * The konan target name, spelled the way Kotlin/Native spells it: `macos_arm64`, `linux_x64`,
     * `watchos_arm32` and so on. Chooses the triple and the sysroot, and must equal the name of
     * [outputDir].
     */
    @get:Input
    abstract val konanTargetName: Property<String>

    /** `kiteplayer-rt/native/src`, holding one or more `.c` files. All of them are compiled. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

    /** `kiteplayer-rt/native/include`, holding `kite_rt.h`. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val includeDir: DirectoryProperty

    /**
     * The konan data directory, `~/.konan` unless `KONAN_DATA_DIR` says otherwise. Deliberately
     * [Internal]: declaring it an input directory would hash an entire LLVM distribution on every
     * build. What is tracked instead is [llvmPackageName].
     */
    @get:Internal
    abstract val konanDataDir: DirectoryProperty

    /**
     * The LLVM package under `<konan data>/dependencies` that supplies `clang` and `llvm-ar`. An
     * input, so bumping the Kotlin version, and with it the compiler, rebuilds every archive.
     *
     * When the named package is absent, [resolveLlvmBinDir] falls back to the newest LLVM package
     * present and logs which one it chose, so a machine with a different Kotlin/Native distribution
     * still builds.
     */
    @get:Input
    abstract val llvmPackageName: Property<String>

    /**
     * Where `libkiteplayerrt.a` and the objects behind it land. Its last path segment must be
     * [konanTargetName]; see the class note on register item B1-11.
     */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    init {
        group = "kiteplayer"
        description = "Compile the real-time audio ring into a static archive for one Kotlin/Native target."
        llvmPackageName.convention(DEFAULT_LLVM_PACKAGE)
    }

    @TaskAction
    fun compile() {
        val target = konanTargetName.get()
        val spec = specFor(target)
        val out = outputDir.get().asFile
        if (out.name != target) {
            throw GradleException(
                "The C archive output directory must be named after its konan target and shared " +
                    "with no other target (register item B1-11): target '$target' was handed " +
                    "'${out.absolutePath}', whose name is '${out.name}'.",
            )
        }

        val dependencies = konanDataDir.get().asFile.resolve("dependencies")
        val llvmBin = resolveLlvmBinDir(dependencies, llvmPackageName.get()) { message ->
            logger.lifecycle("[KitePlayer] $message")
        }
        val clang = resolveTool(llvmBin, "clang") ?: throw GradleException(
            "Cannot compile the real-time audio ring for '$target': no clang (or clang.exe) under " +
                "${llvmBin.absolutePath}. It arrives with the Kotlin/Native distribution, so a " +
                "Gradle build that has already compiled Kotlin/Native code has it.",
        )
        val archiver = resolveTool(llvmBin, "llvm-ar") ?: throw GradleException(
            "Cannot compile the real-time audio ring for '$target': no llvm-ar (or llvm-ar.exe) " +
                "under ${llvmBin.absolutePath}.",
        )

        val sysrootArgs = when {
            spec.appleSdk != null -> listOf("-isysroot", xcrunSdkPath(spec.appleSdk))
            spec.konanSysroot != null -> {
                val sysroot = dependencies.resolve(spec.konanSysroot)
                if (!sysroot.isDirectory) {
                    throw GradleException(
                        "Cannot compile the real-time audio ring for '$target': no sysroot at " +
                            "${sysroot.absolutePath}. It is part of the konan dependency package " +
                            "'${spec.konanSysroot.substringBefore('/')}'.",
                    )
                }
                listOf("--sysroot=${sysroot.absolutePath}")
            }
            else -> emptyList()
        }

        val sources = sourceDir.get().asFile.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "c" }
            .sortedBy { it.name }
        if (sources.isEmpty()) {
            throw GradleException("No .c sources under ${sourceDir.get().asFile.absolutePath}.")
        }

        // A stale object from a previous run must never end up in the archive, and `llvm-ar crs`
        // updates an existing archive in place rather than replacing it, so both are cleared first.
        val objectDir = out.resolve("obj")
        objectDir.deleteRecursively()
        objectDir.mkdirs()
        val archive = out.resolve(ARCHIVE_NAME)
        archive.delete()

        val includeArgs = listOf("-I${includeDir.get().asFile.absolutePath}")

        logger.lifecycle(
            "[KitePlayer] compiling ${sources.size} C source(s) for $target " +
                "(${spec.triple}) with ${clang.absolutePath}",
        )

        val objects = sources.map { source ->
            val objectFile = objectDir.resolve("${source.nameWithoutExtension}.o")
            val command = listOf(clang.absolutePath, "-target", spec.triple) +
                sysrootArgs + COMPILER_FLAGS + includeArgs +
                listOf("-c", source.absolutePath, "-o", objectFile.absolutePath)
            logger.info("[KitePlayer] " + command.joinToString(" "))
            execOperations.exec {
                commandLine(command)
            }
            verifyObjectArchitecture(target, objectFile, describeFile(objectFile))
            objectFile
        }

        execOperations.exec {
            commandLine(listOf(archiver.absolutePath, "crs", archive.absolutePath) + objects.map { it.absolutePath })
        }
        if (!archive.isFile) {
            throw GradleException("llvm-ar reported success but produced no ${archive.absolutePath}.")
        }
        logger.lifecycle(
            "[KitePlayer] $target: ${archive.absolutePath} " +
                "(${archive.length()} bytes, ${objects.size} object(s), ${describeFile(archive)})",
        )
    }

    /**
     * `file -b <path>`, which is how the architecture of an object or an archive is read.
     * Protected and open since the interlude, for exactly one caller: the call-site test.
     * The review measured that deleting the verifyObjectArchitecture call from [compile] left the
     * whole suite green, because every case exercised the predicate directly and none proved the
     * task action invokes it. A test subclass overrides this to describe a wrong architecture,
     * and compile() must then throw; delete the call site and that test fails.
     */
    protected open fun describeFile(file: File): String {
        val stdout = ByteArrayOutputStream()
        execOperations.exec {
            commandLine(FILE_TOOL, "-b", file.absolutePath)
            standardOutput = stdout
        }
        return stdout.toString().trim()
    }

    /**
     * Resolves an Apple SDK sysroot. Called from the task action and never from configuration: the
     * configuration cache rejects starting an external process at configuration time.
     */
    private fun xcrunSdkPath(sdkName: String): String {
        val stdout = ByteArrayOutputStream()
        execOperations.exec {
            commandLine("xcrun", "--sdk", sdkName, "--show-sdk-path")
            standardOutput = stdout
        }
        val path = stdout.toString().trim()
        if (path.isEmpty() || !File(path).isDirectory) {
            throw GradleException("xcrun --sdk $sdkName --show-sdk-path returned '$path', which is not a directory.")
        }
        return path
    }

    /**
     * How one konan target is spelled to clang: its [triple] plus exactly one sysroot, either an
     * Apple SDK resolved through `xcrun` ([appleSdk]) or a path inside `<konan data>/dependencies`
     * ([konanSysroot]).
     */
    data class CTargetSpec(
        val triple: String,
        val appleSdk: String? = null,
        val konanSysroot: String? = null,
    )

    companion object {

        /** The archive name `kitert.def`'s `staticLibraries` line asks cinterop for. */
        const val ARCHIVE_NAME: String = "libkiteplayerrt.a"

        /** The compiler Kotlin/Native itself uses on an arm64 Mac, per konan.properties. */
        const val DEFAULT_LLVM_PACKAGE: String = "llvm-21-aarch64-macos-essentials-97"

        /** `file(1)`, which reads the architecture of an object file or an archive. */
        private val FILE_TOOL: String =
            if (File("/usr/bin/file").canExecute()) "/usr/bin/file" else "file"

        /**
         * The flag set, fixed rather than configurable.
         *
         *  - `-fvisibility=hidden` keeps the ring out of a consumer binary's dynamic symbol table;
         *    the entry points carry `KPRT_API` and so stay reachable. The static linker resolves
         *    them inside the one link that embeds the archive, which is the only link there is.
         *  - `-fPIC` because the archive can end up inside a shared library or a framework.
         *  - `-Werror` with `-Wall -Wextra`: the `.c` includes its own public header, so a
         *    declaration that does not match its definition is a hard failure here rather than a
         *    warning nobody reads.
         *  - `-Werror=vla` because a variable length array on a real-time path is a stack overflow
         *    waiting for a large frame count, and it is an allocation no symbol audit would show.
         *    B1.8's render audit depends on this flag being in force here as well as in the host
         *    build, so it is not a host-only nicety.
         */
        val COMPILER_FLAGS: List<String> = listOf(
            "-O2", "-std=c11",
            "-fvisibility=hidden", "-fPIC",
            "-Wall", "-Wextra", "-Werror", "-Werror=vla",
        )

        /**
         * Triple and sysroot per konan target, one row for each of the 17 native targets
         * `kiteplayer-core` declares.
         *
         * All 17 were measured on this host by compiling `native/src/kite_rt_ring.c` itself, not a
         * placeholder translation unit: every row produced an object, and none of them needed a
         * `__atomic_*` library call, which was checked because the ring uses 64 bit atomics and four
         * of these targets are 32 bit. Compiling is level 7 evidence in the terms of plan section 2
         * and says nothing about behaviour on any of these targets.
         *
         * Two traps are worth naming rather than rediscovering. The Android sysroot has to come
         * from the `target-toolchain-*-android_ndk` package, not from `target-sysroot-*-android_ndk`,
         * whose `--sysroot` fails with `'stdlib.h' file not found`. And watchOS spells its two
         * 32 bit pointer targets differently from the way their Kotlin names read:
         * `watchos_arm64` is `arm64_32`, a 32 bit pointer ABI, while `watchos_device_arm64` is the
         * ordinary 64 bit `arm64`.
         */
        /*
         * A NEW TARGET'S POINTER WIDTH MUST BE CHECKED against kprt_ring_create's byte-count guard
         * (interlude item I-01): the guard bounds the PRODUCT against SIZE_MAX, and three
         * _Static_asserts beside it prove the arithmetic at each width the seventeen current targets
         * have. A width neither 32 nor 64 bit would fail those asserts at compile time, which is the
         * check working; extend the asserts with the new width's expectation rather than deleting them.
         */
        fun specFor(konanTargetName: String): CTargetSpec = when (konanTargetName) {
            "macos_arm64" -> CTargetSpec("arm64-apple-macos11.0", appleSdk = "macosx")
            "macos_x64" -> CTargetSpec("x86_64-apple-macos11.0", appleSdk = "macosx")
            "ios_arm64" -> CTargetSpec("arm64-apple-ios14.0", appleSdk = "iphoneos")
            "ios_simulator_arm64" -> CTargetSpec("arm64-apple-ios14.0-simulator", appleSdk = "iphonesimulator")
            "ios_x64" -> CTargetSpec("x86_64-apple-ios14.0-simulator", appleSdk = "iphonesimulator")
            "tvos_arm64" -> CTargetSpec("arm64-apple-tvos14.0", appleSdk = "appletvos")
            "tvos_simulator_arm64" -> CTargetSpec("arm64-apple-tvos14.0-simulator", appleSdk = "appletvsimulator")
            "watchos_arm32" -> CTargetSpec("armv7k-apple-watchos7.0", appleSdk = "watchos")
            "watchos_arm64" -> CTargetSpec("arm64_32-apple-watchos7.0", appleSdk = "watchos")
            "watchos_device_arm64" -> CTargetSpec("arm64-apple-watchos7.0", appleSdk = "watchos")
            "watchos_simulator_arm64" -> CTargetSpec("arm64-apple-watchos7.0-simulator", appleSdk = "watchsimulator")
            "linux_x64" -> CTargetSpec(
                "x86_64-unknown-linux-gnu",
                konanSysroot = "x86_64-unknown-linux-gnu-gcc-8.3.0-glibc-2.19-kernel-4.9-2/" +
                    "x86_64-unknown-linux-gnu/sysroot",
            )
            "linux_arm64" -> CTargetSpec(
                "aarch64-unknown-linux-gnu",
                konanSysroot = "aarch64-unknown-linux-gnu-gcc-8.3.0-glibc-2.25-kernel-4.9-2/" +
                    "aarch64-unknown-linux-gnu/sysroot",
            )
            "android_arm64" -> CTargetSpec("aarch64-unknown-linux-android24", konanSysroot = androidToolchainSysroot())
            "android_arm32" -> CTargetSpec(
                "armv7a-unknown-linux-androideabi24",
                konanSysroot = androidToolchainSysroot(),
            )
            "android_x64" -> CTargetSpec("x86_64-unknown-linux-android24", konanSysroot = androidToolchainSysroot())
            "android_x86" -> CTargetSpec("i686-unknown-linux-android24", konanSysroot = androidToolchainSysroot())
            "mingw_x64" -> CTargetSpec("x86_64-pc-windows-gnu", konanSysroot = "msys2-mingw-w64-x86_64-2")
            else -> throw GradleException(
                "No C compilation triple is known for konan target '$konanTargetName'. Add one to " +
                    "CompileKiteRtTask.specFor together with the sysroot it needs.",
            )
        }

        /**
         * The Android NDK sysroot, from the toolchain package. Using `target-sysroot-1-android_ndk`
         * instead fails with `'stdlib.h' file not found`.
         */
        /**
         * Resolves a konan LLVM tool by its bare name and then by its `.exe` name (interlude item
         * I-20): a Windows konan package ships `clang.exe`, so `File("bin/clang").canExecute()`
         * is false there and every candidate used to be rejected. Null when neither exists.
         */
        internal fun resolveTool(binDir: File, name: String): File? =
            listOf(binDir.resolve(name), binDir.resolve("$name.exe")).firstOrNull { it.canExecute() }

                /**
         * The konan HOST infix, the word konan itself uses to name per-host dependency packages:
         * the authoritative konan.properties reads `targetToolchain.linux_x64-android_arm64 =
         * target-toolchain-2-linux-android_ndk` and `targetToolchain.mingw_x64-... =
         * target-toolchain-2-windows-...` beside the osx one. Hardcoding `osx` here was interlude
         * item I-20: on an Ubuntu or Windows runner the osx package never exists, so the C compile
         * threw before cinterop and four CI jobs could not pass. Parameterised on the os.name so a
         * test can drive every host shape from one machine.
         */
        internal fun konanHostInfix(osName: String = System.getProperty("os.name").orEmpty()): String = when {
            osName.startsWith("Mac") || osName.startsWith("Darwin") -> "osx"
            osName.startsWith("Windows") -> "windows"
            else -> "linux"
        }

        /** The Android NDK sysroot inside the konan dependencies tree, named after the BUILD host. */
        internal fun androidToolchainSysroot(osName: String = System.getProperty("os.name").orEmpty()): String =
            "target-toolchain-2-${konanHostInfix(osName)}-android_ndk/sysroot"

        /**
         * What `file -b` must report, as a prefix, for an object built for [konanTargetName]. Every
         * string here was measured on this host by compiling the ring for that target and reading
         * `file -b` off the result.
         *
         * `file` reads the architecture and not the platform, so macos_arm64, ios_arm64,
         * tvos_arm64 and the two arm64 watchOS targets share a string. That is the right scope:
         * register item B1-11 is about an archive of the wrong ARCHITECTURE reaching a target,
         * which is what was measured to pass silently through cinterop and fail at the consumer's
         * link. The platform is fixed by the triple and the sysroot, and cross-target mixing is
         * prevented by keying [outputDir] on the target name.
         */
        fun expectedObjectDescription(konanTargetName: String): String =
            acceptedObjectDescriptions(konanTargetName).first()

        /**
         * Every `file -b` prefix that is a CORRECT object for [konanTargetName]. The first entry is
         * the canonical one and is what an error message names.
         *
         * There is more than one entry for exactly one reason, and it is worth stating rather than
         * hiding: **`file` is not the same program everywhere.** The description above was measured
         * on the proving Mac, and a GitHub windows-latest runner describes the object its own konan
         * clang just produced as "x86-64 COFF object file" instead of "Intel amd64 COFF object
         * file". Same format, same architecture, two spellings. Pinning only the one this machine
         * says turned the first real Windows CI run red on a perfectly good object.
         *
         * Widening this list is not the same as weakening the check. B1-11 is about an object of the
         * wrong ARCHITECTURE reaching a target, which cinterop embeds without complaint and which
         * fails only at a consumer's final link. An ELF in the mingw slot is still refused, and a
         * test pins that.
         */
        fun acceptedObjectDescriptions(konanTargetName: String): List<String> = when (konanTargetName) {
            "macos_arm64", "ios_arm64", "ios_simulator_arm64",
            "tvos_arm64", "tvos_simulator_arm64",
            "watchos_device_arm64", "watchos_simulator_arm64",
            -> listOf("Mach-O 64-bit object arm64")
            "macos_x64", "ios_x64" -> listOf("Mach-O 64-bit object x86_64")
            "watchos_arm32" -> listOf("Mach-O object arm_v7k")
            "watchos_arm64" -> listOf("Mach-O object arm64_32")
            "linux_x64", "android_x64" -> listOf("ELF 64-bit LSB relocatable, x86-64")
            "linux_arm64", "android_arm64" -> listOf("ELF 64-bit LSB relocatable, ARM aarch64")
            "android_arm32" -> listOf("ELF 32-bit LSB relocatable, ARM, EABI5")
            "android_x86" -> listOf("ELF 32-bit LSB relocatable, Intel 80386")
            "mingw_x64" -> listOf("Intel amd64 COFF object file", "x86-64 COFF object file")
            else -> throw GradleException(
                "No expected object architecture is known for konan target '$konanTargetName'. Add " +
                    "one to CompileKiteRtTask.acceptedObjectDescriptions.",
            )
        }

        /**
         * Fails when [fileOutput], the `file -b` description of [objectFile], is not what
         * [konanTargetName] must produce. Pure on purpose, so the test can hand it a real object of
         * the wrong architecture and a real `file` output.
         */
        fun verifyObjectArchitecture(konanTargetName: String, objectFile: File, fileOutput: String) {
            val accepted = acceptedObjectDescriptions(konanTargetName)
            if (accepted.any(fileOutput::startsWith)) return
            val expected = accepted.first()
            throw GradleException(
                "Wrong object architecture for konan target '$konanTargetName': " +
                    "${objectFile.absolutePath} is '$fileOutput', expected '$expected'.\n" +
                    "Archiving it would embed a wrong-architecture library in the klib, which " +
                    "cinterop accepts without complaint and which then fails at the consumer's " +
                    "final link with `ld: archive member '/' not a mach-o file` (register item " +
                    "B1-11). Check the triple and the sysroot in CompileKiteRtTask.specFor.",
            )
        }

        /**
         * The `bin` directory holding `clang` and `llvm-ar`, inside [dependenciesDir]. Prefers
         * [preferredPackage]; when that is absent it takes the newest LLVM package present, reports
         * the substitution through [log], and lets the build proceed, because a machine with a
         * different Kotlin/Native distribution ships a differently named one.
         */
        fun resolveLlvmBinDir(
            dependenciesDir: File,
            preferredPackage: String,
            log: (String) -> Unit = {},
        ): File {
            val preferred = dependenciesDir.resolve(preferredPackage)
            if (resolveTool(preferred.resolve("bin"), "clang") != null) return preferred.resolve("bin")

            val alternative = dependenciesDir.listFiles()
                .orEmpty()
                .filter { it.isDirectory && it.name.startsWith("llvm-") }
                .filter { resolveTool(it.resolve("bin"), "clang") != null }
                .maxWithOrNull(LLVM_PACKAGE_ORDER)
                ?: throw GradleException(
                    "No LLVM package with a usable clang under ${dependenciesDir.absolutePath}. " +
                        "Expected '$preferredPackage'. It arrives with the Kotlin/Native " +
                        "distribution, so a build that has already compiled Kotlin/Native code " +
                        "has it.",
                )
            log(
                "the konan LLVM package '$preferredPackage' is not installed; " +
                    "compiling the real-time audio ring with '${alternative.name}' instead.",
            )
            return alternative.resolve("bin")
        }

        /**
         * Orders LLVM package directories by the numbers in their names rather than as text, so
         * `llvm-21-aarch64-macos-essentials-97` sorts above `llvm-9-...` and above
         * `llvm-21-...-essentials-79`. Plain string ordering would put `llvm-9` above `llvm-21`.
         */
        private val LLVM_PACKAGE_ORDER: Comparator<File> = Comparator { left, right ->
            val a = llvmPackageNumbers(left.name)
            val b = llvmPackageNumbers(right.name)
            var verdict = 0
            for (index in 0 until maxOf(a.size, b.size)) {
                verdict = a.getOrElse(index) { 0 }.compareTo(b.getOrElse(index) { 0 })
                if (verdict != 0) break
            }
            if (verdict != 0) verdict else left.name.compareTo(right.name)
        }

        private fun llvmPackageNumbers(name: String): List<Int> =
            Regex("""\d+""").findAll(name).mapNotNull { it.value.toIntOrNull() }.toList()
    }
}
