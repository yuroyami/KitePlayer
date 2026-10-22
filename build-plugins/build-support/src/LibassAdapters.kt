/* Copyright 2026 yuroyami. Apache License, Version 2.0 (see LICENSE). */

package io.github.yuroyami.kiteplayer.build

import java.io.File

/**
 * Links the three libass adapters that are not cinterop: the desktop JVM JNI library per host, the
 * Android JNI library per ABI and the web module.
 */
object LibassAdapters {
    /** The jar resource folder of the desktop JNI libraries: `kiteplayer-libass-native/<host>/`. */
    const val RESOURCE_ROOT: String = "kiteplayer-libass-native"

    /** Desktop host to its chain folder. The sibling builds no macos-x64 chain, so Intel Macs use the Kotlin tier. */
    val CHAIN_DIR_FOR_HOST: Map<String, String> = mapOf(
        "macos-arm64" to "macos-arm64",
        "linux-x64" to "linux-x64",
        "linux-arm64" to "linux-arm64",
        "windows-x64" to "mingw-x64",
    )

    /** Desktop host to the konan target whose sysroot links it. macOS links with Xcode's clang. */
    val KONAN_TARGET_FOR_HOST: Map<String, String?> = mapOf(
        "macos-arm64" to null,
        "linux-x64" to "linux_x64",
        "linux-arm64" to "linux_arm64",
        "windows-x64" to "mingw_x64",
    )

    /**
     * Links the desktop JNI library of [hostTriple] into `<outputDir>/kiteplayer-libass-native/<host>/`
     * with a `manifest.txt` that names it. [mergedArchive] is the chain merged for the matching konan target.
     */
    fun buildHostJni(
        hostTriple: String,
        sourceFile: File,
        driverDir: File,
        chainIncludeDir: File,
        mergedArchive: File,
        javaHome: File,
        konanDataDir: File,
        outputDir: File,
        scratchDir: File,
        log: (String) -> Unit,
    ) {
        val stage = outputDir.resolve("$RESOURCE_ROOT/$hostTriple").also { it.deleteRecursively(); it.mkdirs() }
        val jniInclude = javaHome.resolve("include")
        if (!jniInclude.resolve("jni.h").isFile) throw BuildFailure("No jni.h under $jniInclude.")
        val libraryName = when {
            hostTriple.startsWith("macos") -> "libkiteplayer_libass_jni.dylib"
            hostTriple.startsWith("windows") -> "kiteplayer_libass_jni.dll"
            else -> "libkiteplayer_libass_jni.so"
        }
        val destination = stage.resolve(libraryName)
        val common = listOf(
            "-O2", "-fvisibility=hidden", "-std=c11",
            "-I${jniInclude.absolutePath}",
            "-I${driverDir.absolutePath}",
            "-I${chainIncludeDir.absolutePath}",
            "-x", "c", sourceFile.absolutePath, "-x", "none",
            mergedArchive.absolutePath,
            "-o", destination.absolutePath,
        )
        val command: List<String> = when (hostTriple) {
            "macos-arm64" -> listOf(
                "/usr/bin/clang", "-dynamiclib", "-arch", "arm64",
                "-isysroot", xcrunSdkPath("macosx"), "-mmacosx-version-min=12.0",
                "-install_name", "@rpath/$libraryName",
                "-I${jniInclude.resolve("darwin").absolutePath}",
            ) + common + listOf(
                "-lz", "-liconv", "-lc++",
                "-framework", "CoreText", "-framework", "CoreFoundation", "-framework", "CoreGraphics",
            )
            "linux-x64", "linux-arm64" -> {
                val konan = konanTarget(if (hostTriple == "linux-x64") "linux_x64" else "linux_arm64", konanDataDir, log)
                val jniMd = writeJniMd(scratchDir, "linux", "__attribute__((visibility(\"default\")))", "")
                listOf(
                    konan.clang, "-target", konan.triple, "--sysroot=${konan.sysroot}",
                    "-fuse-ld=lld", "-B${konan.llvmBin}", "-shared", "-fPIC",
                    "-I${jniMd.absolutePath}",
                ) + konan.libraryDirs.flatMap { listOf("-B$it", "-L$it") } + common + listOf(
                    "-lz", "-lstdc++", "-lm",
                    "-Wl,--no-undefined",
                )
            }
            "windows-x64" -> {
                val konan = konanTarget("mingw_x64", konanDataDir, log)
                val jniMd = writeJniMd(scratchDir, "win32", "__declspec(dllexport)", "__stdcall")
                listOf(
                    konan.clang, "-target", konan.triple, "--sysroot=${konan.sysroot}",
                    "-fuse-ld=lld", "-B${konan.llvmBin}", "-shared",
                    "-I${jniMd.absolutePath}",
                ) + konan.libraryDirs.map { "-L$it" } + common + listOf(
                    "-static-libstdc++", "-static-libgcc",
                    "-Wl,-Bstatic", "-liconv", "-Wl,-Bdynamic",
                    "-lgdi32", "-ldwrite", "-lole32", "-luuid", "-luser32",
                )
            }
            else -> throw BuildFailure("No desktop JNI build is known for host '$hostTriple'.")
        }
        runProcess(command)
        if (!destination.isFile) throw BuildFailure("The link reported success but produced no $destination.")
        stage.resolve("manifest.txt").writeText(libraryName + "\n")
        log("$hostTriple: $libraryName (${destination.length()} bytes) staged for the jvm jar")
    }

    /** True when this machine can link the desktop adapter of [hostTriple] without a download. */
    fun hostToolchainReady(hostTriple: String, konanDataDir: File): Boolean = when (hostTriple) {
        "macos-arm64" -> hostIsMac && File("/usr/bin/clang").canExecute()
        else -> {
            val target = KONAN_TARGET_FOR_HOST[hostTriple] ?: return false
            val sysroot = CompileKiteRt.specFor(target).konanSysroot ?: return false
            konanDataDir.resolve("dependencies").resolve(sysroot).isDirectory
        }
    }

    private class KonanTarget(val clang: String, val llvmBin: String, val triple: String, val sysroot: String, val libraryDirs: List<String>)

    private fun konanTarget(konanTargetName: String, konanDataDir: File, log: (String) -> Unit): KonanTarget {
        val dependencies = konanDataDir.resolve("dependencies")
        val llvmBin = CompileKiteRt.resolveLlvmBinDir(dependencies, CompileKiteRt.DEFAULT_LLVM_PACKAGE, log)
        val clang = CompileKiteRt.resolveTool(llvmBin, "clang") ?: throw BuildFailure("No clang under $llvmBin.")
        val spec = CompileKiteRt.specFor(konanTargetName)
        val sysrootRelative = spec.konanSysroot ?: throw BuildFailure("$konanTargetName has no konan sysroot")
        val sysroot = dependencies.resolve(sysrootRelative)
        if (!sysroot.isDirectory) throw BuildFailure("No konan sysroot at $sysroot for $konanTargetName.")
        val packageRoot = dependencies.resolve(sysrootRelative.substringBefore('/'))
        // The gcc runtime and libstdc++ sit beside the sysroot in the same konan package.
        val libraryDirs = packageRoot.walkTopDown().maxDepth(6)
            .filter { it.isFile && (it.name == "libstdc++.a" || it.name == "libgcc.a" || it.name == "libstdc++.so") }
            .map { it.parentFile.absolutePath }
            .distinct()
            .toList()
        return KonanTarget(clang.absolutePath, llvmBin.absolutePath, spec.triple, sysroot.absolutePath, libraryDirs)
    }

    /** A `jni_md.h` for a foreign host: the host JDK ships only its own. */
    private fun writeJniMd(scratchDir: File, platform: String, export: String, call: String): File {
        val dir = scratchDir.resolve("jni-md/$platform").also { it.mkdirs() }
        dir.resolve("jni_md.h").writeText(
            """
            #ifndef _JAVASOFT_JNI_MD_H_
            #define _JAVASOFT_JNI_MD_H_
            #define JNIEXPORT $export
            #define JNIIMPORT
            #define JNICALL $call
            typedef int jint;
            typedef long long jlong;
            typedef signed char jbyte;
            #endif
            """.trimIndent() + "\n",
        )
        return dir
    }

    /** One Android ABI: its folder name, its NDK triple, its chain folder and whether it needs 16 KB page alignment. */
    data class AndroidAbi(val abiDirName: String, val ndkTarget: String, val depsDirName: String, val sixteenKibPages: Boolean)

    /** Every ABI a chain exists for. KiteFFmpeg cross-builds no x86 chain. */
    val ANDROID_ABIS: List<AndroidAbi> = listOf(
        AndroidAbi("arm64-v8a", "aarch64-linux-android24", "android-arm64", sixteenKibPages = true),
        AndroidAbi("armeabi-v7a", "armv7a-linux-androideabi24", "android-arm32", sixteenKibPages = false),
        AndroidAbi("x86_64", "x86_64-linux-android24", "android-x64", sixteenKibPages = true),
    )

    /**
     * Links `lib/<abi>/libkiteplayer_libass_jni.so` under [outputDir] with the NDK's clang++, the toolchain
     * that targets Android's own loader. clang++ carries HarfBuzz's C++ runtime, kept inside by -static-libstdc++.
     */
    fun buildAndroidJni(abi: AndroidAbi, chain: AssChain.Chain, sourceFile: File, ndk: File, outputDir: File, log: (String) -> Unit) {
        val ndkBin = ndk.resolve("toolchains/llvm/prebuilt").listFiles()?.firstOrNull { it.isDirectory }?.resolve("bin")
            ?: throw BuildFailure("No prebuilt NDK toolchain under $ndk")
        val chainLib = chain.libDir
        if (!chainLib.resolve("libass.a").isFile) throw BuildFailure("No ass chain for ${abi.depsDirName}: $chainLib/libass.a is missing.")
        val clang = listOf(ndkBin.resolve("${abi.ndkTarget}-clang++"), ndkBin.resolve("${abi.ndkTarget}-clang++.cmd"))
            .firstOrNull { it.isFile } ?: throw BuildFailure("No NDK compiler for ${abi.ndkTarget} in $ndkBin")
        val destination = outputDir.resolve("lib/${abi.abiDirName}/libkiteplayer_libass_jni.so")
        destination.parentFile.mkdirs()
        val command = listOf(
            clang.absolutePath,
            "-shared", "-fPIC", "-O2",
            "-fvisibility=hidden", "-DJNIEXPORT=__attribute__((visibility(\"default\")))",
            "-o", destination.absolutePath,
            // The driver is clang++ for the link, but the source is C.
            "-x", "c", sourceFile.absolutePath, "-x", "none",
            "-I", sourceFile.parentFile.absolutePath,
            "-I", chain.includeDir.absolutePath,
            // HarfBuzz and FreeType reference each other, so the chain links as a group.
            "-Wl,--start-group",
            chainLib.resolve("libass.a").absolutePath,
            chainLib.resolve("libharfbuzz.a").absolutePath,
            chainLib.resolve("libfreetype.a").absolutePath,
            chainLib.resolve("libfribidi.a").absolutePath,
            "-Wl,--end-group",
            "-static-libstdc++",
            "-lz", "-lm",
            // A missing symbol must fail here, not as a dlopen failure on a device.
            "-Wl,--no-undefined",
        ) + if (abi.sixteenKibPages) listOf("-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=16384") else emptyList()
        runProcess(command)
        log("${abi.abiDirName}/${destination.name} built (${destination.length()} bytes)")
    }

    const val WASM_MODULE_NAME: String = "kiteass.mjs"
    const val WASM_NAME: String = "kiteass.wasm"
    val WASM_EXPORTS: List<String> = listOf(
        "_kass_library_version", "_kass_open", "_kass_close", "_kass_alloc", "_kass_free",
        "_kass_open_track", "_kass_open_document", "_kass_add_event", "_kass_clear_events",
        "_kass_add_font", "_kass_set_frame", "_kass_set_video_color", "_kass_render", "_kass_packed_ptr", "_kass_packed_size",
        "_malloc", "_free",
    )

    /** The `emcc` on PATH, or null. */
    fun findEmcc(): String? = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
        .map { File(it, if (hostIsWindows) "emcc.bat" else "emcc") }
        .firstOrNull { it.canExecute() }?.absolutePath

    /** Links `kiteass.mjs` and `kiteass.wasm` into [outputDir] with emscripten. */
    fun buildWasmModule(emcc: String, sourceFile: File, driverDir: File, chain: AssChain.Chain, outputDir: File, log: (String) -> Unit) {
        outputDir.deleteRecursively()
        outputDir.mkdirs()
        AssChain.MEMBERS.forEach { member ->
            if (!chain.libDir.resolve("$member.a").isFile) throw BuildFailure("The wasm32 chain at ${chain.root} has no lib/$member.a.")
        }
        val command = listOf(
            emcc, "-O2",
            "-I${driverDir.absolutePath}",
            "-I${chain.includeDir.absolutePath}",
            "-x", "c", sourceFile.absolutePath, "-x", "none",
        ) + AssChain.MEMBERS.map { chain.libDir.resolve("$it.a").absolutePath } + listOf(
            "-sMODULARIZE=1", "-sEXPORT_ES6=1", "-sENVIRONMENT=web,worker,node",
            "-sALLOW_MEMORY_GROWTH=1", "-sSTACK_SIZE=1048576",
            "-sEXPORTED_FUNCTIONS=${WASM_EXPORTS.joinToString(",")}",
            "-sEXPORTED_RUNTIME_METHODS=HEAPU8",
            "-o", outputDir.resolve(WASM_MODULE_NAME).absolutePath,
        )
        runProcess(command)
        val wasm = outputDir.resolve(WASM_NAME)
        if (!outputDir.resolve(WASM_MODULE_NAME).isFile || !wasm.isFile) {
            throw BuildFailure("emcc reported success but produced no $WASM_MODULE_NAME and $WASM_NAME in $outputDir.")
        }
        log("web: $WASM_MODULE_NAME + $WASM_NAME (${wasm.length()} bytes)")
    }
}
