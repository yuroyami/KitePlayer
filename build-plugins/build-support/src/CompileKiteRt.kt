/* Copyright 2026 yuroyami. Apache License, Version 2.0 (see LICENSE). */

package io.github.yuroyami.kiteplayer.build

import java.io.File

/**
 * Compiles every `.c` file of `kiteplayer-rt/native/src` into one static archive for one Kotlin/Native
 * target, at `<outputDir>/libkiteplayerrt.a`. The `kitert` cinterop embeds that archive in the klib.
 *
 * The compiler is konan's own clang (`<konan data>/dependencies/<llvm package>/bin/clang`), so the object
 * format and the triples match everything else in the consumer's Kotlin/Native link.
 *
 * Two guards matter:
 * - [outputDir] is named after its konan target and holds no other target's files.
 * - Every object passes [verifyObjectArchitecture] before it is archived. cinterop embeds a
 *   wrong-architecture archive without complaint, and the consumer's final link then fails.
 */
open class CompileKiteRt(
    val konanTargetName: String,
    val sourceDir: File,
    val includeDir: File,
    val konanDataDir: File,
    val outputDir: File,
    val llvmPackageName: String = DEFAULT_LLVM_PACKAGE,
) {
    fun compile(log: (String) -> Unit = {}) {
        val target = konanTargetName
        val spec = specFor(target)
        val out = outputDir
        if (out.name != target) {
            throw BuildFailure(
                "The C archive output directory must be named after its konan target and shared " +
                    "with no other target: target '$target' was handed " +
                    "'${out.absolutePath}', whose name is '${out.name}'.",
            )
        }
        val dependencies = konanDataDir.resolve("dependencies")
        val llvmBin = resolveLlvmBinDir(dependencies, llvmPackageName, log)
        val clang = resolveTool(llvmBin, "clang") ?: throw BuildFailure(
            "Cannot compile the real-time audio ring for '$target': no clang (or clang.exe) under " +
                "${llvmBin.absolutePath}. It arrives with the Kotlin/Native distribution.",
        )
        val archiver = resolveTool(llvmBin, "llvm-ar") ?: throw BuildFailure(
            "Cannot compile the real-time audio ring for '$target': no llvm-ar (or llvm-ar.exe) " +
                "under ${llvmBin.absolutePath}.",
        )
        val sysrootArgs = when {
            spec.appleSdk != null -> listOf("-isysroot", xcrunSdkPath(spec.appleSdk))
            spec.konanSysroot != null -> {
                val sysroot = dependencies.resolve(spec.konanSysroot)
                if (!sysroot.isDirectory) {
                    throw BuildFailure(
                        "Cannot compile the real-time audio ring for '$target': no sysroot at " +
                            "${sysroot.absolutePath}. It is part of the konan dependency package " +
                            "'${spec.konanSysroot.substringBefore('/')}'.",
                    )
                }
                listOf("--sysroot=${sysroot.absolutePath}")
            }
            else -> emptyList()
        }
        val sources = sourceDir.listFiles().orEmpty()
            .filter { it.isFile && it.extension == "c" }
            .sortedBy { it.name }
        if (sources.isEmpty()) throw BuildFailure("No .c sources under ${sourceDir.absolutePath}.")

        // Objects of an earlier run must not reach the archive.
        val objectDir = out.resolve("obj")
        objectDir.deleteRecursively()
        objectDir.mkdirs()
        val archive = out.resolve(ARCHIVE_NAME)
        archive.delete()
        val includeArgs = listOf("-I${includeDir.absolutePath}")
        log("compiling ${sources.size} C source(s) for $target (${spec.triple}) with ${clang.absolutePath}")
        val objects = sources.map { source ->
            val objectFile = objectDir.resolve("${source.nameWithoutExtension}.o")
            runProcess(
                listOf(clang.absolutePath, "-target", spec.triple) + sysrootArgs + COMPILER_FLAGS + spec.extraFlags + includeArgs +
                    listOf("-c", source.absolutePath, "-o", objectFile.absolutePath),
            )
            verifyObjectArchitecture(target, objectFile, describeFile(objectFile))
            objectFile
        }
        runProcess(listOf(archiver.absolutePath, "crs", archive.absolutePath) + objects.map { it.absolutePath })
        if (!archive.isFile) throw BuildFailure("llvm-ar reported success but produced no ${archive.absolutePath}.")
        log("$target: ${archive.absolutePath} (${archive.length()} bytes, ${objects.size} object(s), ${describeFile(archive)})")
    }

    /** What `file -b` says about [file]. Tests override it. */
    protected open fun describeFile(file: File): String =
        runProcess(listOf(FILE_TOOL, "-b", file.absolutePath)).trim()

    /**
     * A target triple plus exactly one sysroot: an Apple SDK name or a path under konan's dependencies.
     * [extraFlags] go to the compiler for this target only.
     */
    data class CTargetSpec(
        val triple: String,
        val appleSdk: String? = null,
        val konanSysroot: String? = null,
        val extraFlags: List<String> = emptyList(),
    )

    companion object {
        const val ARCHIVE_NAME: String = "libkiteplayerrt.a"
        const val DEFAULT_LLVM_PACKAGE: String = "llvm-21-aarch64-macos-essentials-97"

        private val FILE_TOOL: String = if (File("/usr/bin/file").canExecute()) "/usr/bin/file" else "file"

        /** -Werror=vla: a variable length array on the real-time path is an allocation no symbol audit shows. */
        val COMPILER_FLAGS: List<String> = listOf(
            "-O2", "-std=c11",
            "-ffp-contract=off",
            "-fvisibility=hidden", "-fPIC",
            "-Wall", "-Wextra", "-Werror", "-Werror=vla",
        )

        /** The 17 konan targets that `kiteplayer-core` builds for. */
        val TARGETS: List<String> = listOf(
            "macos_arm64",
            "ios_arm64", "ios_simulator_arm64", "ios_x64",
            "tvos_arm64", "tvos_simulator_arm64",
            "watchos_arm32", "watchos_arm64", "watchos_device_arm64", "watchos_simulator_arm64",
            "android_arm32", "android_arm64", "android_x64", "android_x86",
            "linux_x64", "linux_arm64",
            "mingw_x64",
        )

        /** The Apple minimum versions are the Kotlin/Native 2.4.21 defaults, so the archive never asks for more than the klib. */
        fun specFor(konanTargetName: String): CTargetSpec = when (konanTargetName) {
            "macos_arm64" -> CTargetSpec("arm64-apple-macos12.0", appleSdk = "macosx")
            "macos_x64" -> CTargetSpec("x86_64-apple-macos12.0", appleSdk = "macosx")
            "ios_arm64" -> CTargetSpec("arm64-apple-ios15.0", appleSdk = "iphoneos")
            "ios_simulator_arm64" -> CTargetSpec("arm64-apple-ios15.0-simulator", appleSdk = "iphonesimulator")
            "ios_x64" -> CTargetSpec("x86_64-apple-ios15.0-simulator", appleSdk = "iphonesimulator")
            "tvos_arm64" -> CTargetSpec("arm64-apple-tvos15.0", appleSdk = "appletvos")
            "tvos_simulator_arm64" -> CTargetSpec("arm64-apple-tvos15.0-simulator", appleSdk = "appletvsimulator")
            "watchos_arm32" -> CTargetSpec("armv7k-apple-watchos8.0", appleSdk = "watchos")
            "watchos_arm64" -> CTargetSpec("arm64_32-apple-watchos8.0", appleSdk = "watchos")
            "watchos_device_arm64" -> CTargetSpec("arm64-apple-watchos8.0", appleSdk = "watchos")
            "watchos_simulator_arm64" -> CTargetSpec("arm64-apple-watchos8.0-simulator", appleSdk = "watchsimulator")
            "linux_x64" -> CTargetSpec(
                "x86_64-unknown-linux-gnu",
                konanSysroot = "x86_64-unknown-linux-gnu-gcc-8.3.0-glibc-2.19-kernel-4.9-2/x86_64-unknown-linux-gnu/sysroot",
            )
            "linux_arm64" -> CTargetSpec(
                "aarch64-unknown-linux-gnu",
                konanSysroot = "aarch64-unknown-linux-gnu-gcc-8.3.0-glibc-2.25-kernel-4.9-2/aarch64-unknown-linux-gnu/sysroot",
            )
            // Clang calls helpers such as __aarch64_ldadd8_relax for atomics on Android arm64 by default.
            // The Kotlin/Native linker for Android links no library that has them, so an executable fails to link.
            "android_arm64" -> CTargetSpec(
                "aarch64-unknown-linux-android24",
                konanSysroot = androidToolchainSysroot(),
                extraFlags = listOf("-mno-outline-atomics"),
            )
            "android_arm32" -> CTargetSpec("armv7a-unknown-linux-androideabi24", konanSysroot = androidToolchainSysroot())
            "android_x64" -> CTargetSpec("x86_64-unknown-linux-android24", konanSysroot = androidToolchainSysroot())
            "android_x86" -> CTargetSpec("i686-unknown-linux-android24", konanSysroot = androidToolchainSysroot())
            "mingw_x64" -> CTargetSpec("x86_64-pc-windows-gnu", konanSysroot = "msys2-mingw-w64-x86_64-2")
            else -> throw BuildFailure(
                "No C compilation triple is known for konan target '$konanTargetName'. Add one to " +
                    "CompileKiteRt.specFor together with the sysroot it needs.",
            )
        }

        /** True when this host can compile for [konanTargetName]: Apple targets need macOS with Xcode. */
        fun hostCanCompile(konanTargetName: String): Boolean = specFor(konanTargetName).appleSdk == null || hostIsMac

        /** The bare tool name, then the `.exe` name that a Windows konan package ships. */
        fun resolveTool(binDir: File, name: String): File? =
            listOf(binDir.resolve(name), binDir.resolve("$name.exe")).firstOrNull { it.canExecute() }

        /** The host infix of konan's package names: `osx`, `windows` or `linux`. */
        fun konanHostInfix(osName: String = System.getProperty("os.name").orEmpty()): String = when {
            osName.startsWith("Mac") || osName.startsWith("Darwin") -> "osx"
            osName.startsWith("Windows") -> "windows"
            else -> "linux"
        }

        fun androidToolchainSysroot(osName: String = System.getProperty("os.name").orEmpty()): String =
            "target-toolchain-2-${konanHostInfix(osName)}-android_ndk/sysroot"

        fun expectedObjectDescription(konanTargetName: String): String = acceptedObjectDescriptions(konanTargetName).first()

        /** `file -b` prefixes per target. Windows `file` versions spell COFF two ways. */
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
            else -> throw BuildFailure(
                "No expected object architecture is known for konan target '$konanTargetName'. Add " +
                    "one to CompileKiteRt.acceptedObjectDescriptions.",
            )
        }

        fun verifyObjectArchitecture(konanTargetName: String, objectFile: File, fileOutput: String) {
            val accepted = acceptedObjectDescriptions(konanTargetName)
            if (accepted.any(fileOutput::startsWith)) return
            throw BuildFailure(
                "Wrong object architecture for konan target '$konanTargetName': " +
                    "${objectFile.absolutePath} is '$fileOutput', expected '${accepted.first()}'.\n" +
                    "Archiving it would embed a wrong-architecture library in the klib, which " +
                    "cinterop accepts without complaint and which then fails at the consumer's " +
                    "final link with `ld: archive member '/' not a mach-o file`. " +
                    "Check the triple and the sysroot in CompileKiteRt.specFor.",
            )
        }

        /** The preferred LLVM package, else the newest one present, which [log] reports once. */
        fun resolveLlvmBinDir(dependenciesDir: File, preferredPackage: String, log: (String) -> Unit = {}): File {
            val preferred = dependenciesDir.resolve(preferredPackage)
            if (resolveTool(preferred.resolve("bin"), "clang") != null) return preferred.resolve("bin")
            val alternative = dependenciesDir.listFiles().orEmpty()
                .filter { it.isDirectory && it.name.startsWith("llvm-") }
                .filter { resolveTool(it.resolve("bin"), "clang") != null }
                .maxWithOrNull(LLVM_PACKAGE_ORDER)
                ?: throw BuildFailure(
                    "No LLVM package with a usable clang under ${dependenciesDir.absolutePath}. " +
                        "Expected '$preferredPackage'. It arrives with the Kotlin/Native distribution.",
                )
            log("the konan LLVM package '$preferredPackage' is not installed; using '${alternative.name}' instead.")
            return alternative.resolve("bin")
        }

        /** Numbers, not text: llvm-9 sorts below llvm-21, and essentials-97 above essentials-79. */
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

        /**
         * Makes sure the LLVM package and the sysroot of [konanTargetName] exist, and asks konan to
         * download them when they do not. Apple targets use Xcode's SDKs and need only the LLVM package.
         */
        fun ensureToolchain(konanTargetName: String, kotlinVersion: String, konanDataDir: File, log: (String) -> Unit) {
            val dependencies = konanDataDir.resolve("dependencies")
            val hasLlvm = dependencies.listFiles().orEmpty().any {
                it.name.startsWith("llvm-") && resolveTool(it.resolve("bin"), "clang") != null
            }
            val sysroot = specFor(konanTargetName).konanSysroot
            val hasSysroot = sysroot == null || dependencies.resolve(sysroot).isDirectory
            if (!hasLlvm || !hasSysroot) provisionKonanDependencies(konanTargetName, kotlinVersion, log)
        }
    }
}
