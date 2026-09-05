package io.github.yuroyami.kiteplayer.buildtools

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import javax.inject.Inject

/**
 * Merges the four ass-chain archives (libass, HarfBuzz, FreeType, FriBidi) into ONE static
 * library, `libkiteass.a`, for one Kotlin/Native target.
 *
 * One archive because cinterop embeds `staticLibraries` into the klib and a consumer's final link
 * sees them as opaque inputs, in whatever order the compiler hands them over. Four archives with
 * references between them are then at the mercy of that order; one archive is resolved by the
 * linker's own member scan and has no order. The objects are extracted and renamed with their
 * library's prefix first, so two libraries shipping a `utils.o` cannot shadow each other.
 */
abstract class MergeAssChainTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {

    /** The chain's `lib` directory, holding the four `.a` files. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val chainLibDir: DirectoryProperty

    @get:Input
    abstract val konanTargetName: Property<String>

    @get:Internal
    abstract val konanDataDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    init {
        group = "kiteplayer"
        description = "Merge the libass chain into one static archive for one Kotlin/Native target."
    }

    @TaskAction
    fun merge() {
        val lib = chainLibDir.get().asFile
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val archiver = CompileKiteRtTask.resolveTool(
            CompileKiteRtTask.resolveLlvmBinDir(konanDataDir.get().asFile.resolve("dependencies"), CompileKiteRtTask.DEFAULT_LLVM_PACKAGE) {
                logger.lifecycle("[kiteplayer-libass] $it")
            },
            "llvm-ar",
        ) ?: throw GradleException("No llvm-ar in the konan LLVM package; a Kotlin/Native compile provisions it.")
        val objects = mutableListOf<File>()
        MEMBERS.forEach { member ->
            val archive = lib.resolve("$member.a")
            if (!archive.isFile) throw GradleException("The ass chain at $lib has no $member.a.")
            val extractDir = out.resolve("objects/$member").also { it.mkdirs() }
            execOperations.exec {
                workingDir = extractDir
                commandLine(archiver.absolutePath, "x", archive.absolutePath)
            }
            extractDir.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }.forEach { file ->
                val renamed = extractDir.resolve("${member}__${file.name}")
                check(file.renameTo(renamed)) { "could not rename ${file.absolutePath}" }
                objects += renamed
            }
        }
        if (objects.isEmpty()) throw GradleException("The ass chain at $lib produced no objects.")
        val merged = out.resolve(ARCHIVE_NAME)
        // The GNU symbol index is what lld consults for ELF and PE; llvm-ar on an arm64 Mac would
        // otherwise write an ARM64EC index for mingw, which lld ignores and every symbol goes
        // missing while no library is reported absent. Apple's ld wants the Darwin format.
        val format = if (konanTargetName.get().startsWith("macos") || konanTargetName.get().startsWith("ios")) "darwin" else "gnu"
        execOperations.exec {
            commandLine(listOf(archiver.absolutePath, "crs", "--format=$format", merged.absolutePath) + objects.map { it.absolutePath })
        }
        if (!merged.isFile) throw GradleException("llvm-ar reported success but produced no $merged.")
        out.resolve("objects").deleteRecursively()
        logger.lifecycle("[kiteplayer-libass] ${konanTargetName.get()}: ${merged.name} from ${objects.size} objects (${merged.length()} bytes)")
    }

    companion object {
        const val ARCHIVE_NAME: String = "libkiteass.a"
        val MEMBERS: List<String> = listOf("libass", "libharfbuzz", "libfreetype", "libfribidi")
    }
}

/**
 * Downloads one target's ass chain from a KiteFFmpeg GitHub release and unpacks it.
 *
 * The chain is cross-built in the sibling repository and too large for either git history, so it
 * travels the way FFmpeg's own prebuilts do: as a release asset, pinned here by SHA-256. A build on
 * the maintainer's machine never runs this because the sibling checkout is beside it; a fresh clone
 * and CI do. A mismatched digest fails the build rather than trusting the bytes.
 */
abstract class FetchAssChainTask : DefaultTask() {

    /** `macos-arm64`, `android-arm64`, ...: the chain directory name in the sibling's deps tree. */
    @get:Input
    abstract val targetDirName: Property<String>

    /** The release tag the asset hangs off, for example `ass-chain-r1`. */
    @get:Input
    abstract val releaseTag: Property<String>

    /** The asset's expected SHA-256, lowercase hex. */
    @get:Input
    abstract val expectedSha256: Property<String>

    @get:Input
    abstract val baseUrl: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    init {
        group = "kiteplayer"
        description = "Download one target's libass chain from the KiteFFmpeg release that carries it."
        baseUrl.convention("https://github.com/yuroyami/KiteFFmpeg/releases/download")
    }

    @TaskAction
    fun fetch() {
        val asset = "ass-chain-${targetDirName.get()}.zip"
        val url = "${baseUrl.get()}/${releaseTag.get()}/$asset"
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        logger.lifecycle("[kiteplayer-libass] fetching $url")
        val bytes = download(url)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (digest != expectedSha256.get().lowercase()) {
            throw GradleException(
                "$asset from $url has SHA-256 $digest, expected ${expectedSha256.get()}. " +
                    "The pin lives in kiteplayer-libass/ass-chain.sha256; a new chain build means a new pin.",
            )
        }
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = out.resolve(entry.name).normalize()
                if (!target.startsWith(out)) throw GradleException("$asset carries an entry outside its root: ${entry.name}")
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                }
                zip.closeEntry()
            }
        }
        if (!out.resolve("lib/libass.a").isFile || !out.resolve("include/ass/ass.h").isFile) {
            throw GradleException("$asset unpacked without lib/libass.a or include/ass/ass.h; not an ass chain.")
        }
    }

    private fun download(url: String): ByteArray {
        var current = url
        repeat(6) {
            val connection = URI(current).toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            connection.setRequestProperty("User-Agent", "KitePlayer-build")
            val code = connection.responseCode
            if (code in 300..399) {
                current = connection.getHeaderField("Location") ?: throw GradleException("$url redirected nowhere")
                connection.disconnect()
                return@repeat
            }
            if (code != 200) {
                throw GradleException(
                    "GET $url answered HTTP $code. The chain is published as a release asset of the " +
                        "KiteFFmpeg repository; see kiteplayer-libass/build.gradle.kts for the three ways a build finds it.",
                )
            }
            return connection.inputStream.use { it.readBytes() }
        }
        throw GradleException("$url redirected more than six times")
    }
}

/**
 * Links the desktop JVM adapter, `kiteplayer_libass_jni`, for one host triple, and stages it into
 * the resource layout `LibassJniLoader` reads: `kiteplayer-libass-native/<os>-<arch>/`.
 *
 * The chain is linked in statically, so the one file is the whole adapter. The macOS build uses
 * the host clang and SDK; Linux and Windows are cross-linked with the same konan clang, sysroots
 * and lld that Kotlin/Native itself uses for those targets, so the adapter runs against the libc
 * its Kotlin/Native neighbours were built against.
 *
 * jni.h is platform independent and taken from the running JDK; jni_md.h is not, and the two
 * cross targets get a generated one, because it holds three typedefs and three macros and the
 * alternative is a Docker daemon for a JDK image.
 */
abstract class BuildLibassHostJniTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {

    /** `macos-arm64`, `linux-x64`, `linux-arm64` or `windows-x64`. */
    @get:Input
    abstract val hostTriple: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFile: RegularFileProperty

    /** `native/src`, for kite_ass.h and libass_pack_limits.h. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val driverDir: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val chainIncludeDir: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val mergedArchive: RegularFileProperty

    @get:Input
    abstract val javaHome: Property<String>

    @get:Internal
    abstract val konanDataDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    init {
        group = "kiteplayer"
        description = "Link the desktop JVM libass adapter for one host and stage it as a jar resource."
    }

    @TaskAction
    fun build() {
        val triple = hostTriple.get()
        val out = outputDir.get().asFile
        out.deleteRecursively()
        val stage = out.resolve("$RESOURCE_ROOT/$triple").also { it.mkdirs() }
        val jniInclude = File(javaHome.get()).resolve("include")
        if (!jniInclude.resolve("jni.h").isFile) throw GradleException("No jni.h under $jniInclude.")
        val libraryName = when {
            triple.startsWith("macos") -> "libkiteplayer_libass_jni.dylib"
            triple.startsWith("windows") -> "kiteplayer_libass_jni.dll"
            else -> "libkiteplayer_libass_jni.so"
        }
        val destination = stage.resolve(libraryName)
        val common = listOf(
            "-O2", "-fvisibility=hidden", "-std=c11",
            "-I${jniInclude.absolutePath}",
            "-I${driverDir.get().asFile.absolutePath}",
            "-I${chainIncludeDir.get().asFile.absolutePath}",
            "-x", "c", sourceFile.get().asFile.absolutePath, "-x", "none",
            mergedArchive.get().asFile.absolutePath,
            "-o", destination.absolutePath,
        )
        val command: List<String> = when (triple) {
            "macos-arm64", "macos-x64" -> {
                val arch = if (triple == "macos-arm64") "arm64" else "x86_64"
                listOf(
                    "/usr/bin/clang", "-dynamiclib", "-arch", arch,
                    "-isysroot", xcrunSdkPath("macosx"), "-mmacosx-version-min=11.0",
                    "-install_name", "@rpath/$libraryName",
                    "-I${jniInclude.resolve("darwin").absolutePath}",
                ) + common + listOf(
                    "-lz", "-liconv", "-lc++",
                    "-framework", "CoreText", "-framework", "CoreFoundation", "-framework", "CoreGraphics",
                )
            }
            "linux-x64", "linux-arm64" -> {
                val konan = konanTarget(if (triple == "linux-x64") "linux_x64" else "linux_arm64")
                val jniMd = writeJniMd(out, "linux", "__attribute__((visibility(\"default\")))", "", "long long")
                // -B for the gcc runtime directory as well as -L: crtbeginS.o and crtendS.o are
                // found through the -B search, and lld refuses a shared object without them.
                listOf(
                    konan.clang, "-target", konan.triple, "--sysroot=${konan.sysroot}",
                    "-fuse-ld=lld", "-B${konan.llvmBin}", "-shared", "-fPIC",
                    "-I${jniMd.absolutePath}",
                ) + konan.libraryDirs.flatMap { listOf("-B$it", "-L$it") } + common + listOf(
                    "-lz", "-lstdc++", "-lm",
                    // Nothing may be left dangling: a missing symbol in a .so surfaces as a dlopen
                    // failure on a user's machine rather than as a link error here.
                    "-Wl,--no-undefined",
                )
            }
            "windows-x64" -> {
                val konan = konanTarget("mingw_x64")
                val jniMd = writeJniMd(out, "win32", "__declspec(dllexport)", "__stdcall", "long long")
                listOf(
                    konan.clang, "-target", konan.triple, "--sysroot=${konan.sysroot}",
                    "-fuse-ld=lld", "-B${konan.llvmBin}", "-shared",
                    "-I${jniMd.absolutePath}",
                ) + konan.libraryDirs.map { "-L$it" } + common + listOf(
                    // libass' font provider is GDI plus DirectWrite here, and those are OS
                    // libraries; the C++ runtime is linked in so the DLL depends on no MinGW one.
                    "-static-libstdc++", "-static-libgcc",
                    "-lstdc++", "-lgdi32", "-ldwrite", "-lole32", "-luuid", "-luser32", "-liconv",
                )
            }
            else -> throw GradleException("BuildLibassHostJniTask knows no host triple '$triple'.")
        }
        logger.info("[kiteplayer-libass] " + command.joinToString(" "))
        execOperations.exec { commandLine(command) }
        if (!destination.isFile) throw GradleException("The link reported success but produced no $destination.")
        stage.resolve("manifest.txt").writeText(libraryName + "\n")
        logger.lifecycle("[kiteplayer-libass] $triple: $libraryName (${destination.length()} bytes) staged for the jvm jar")
    }

    private class KonanTarget(
        val clang: String,
        val llvmBin: String,
        val triple: String,
        val sysroot: String,
        val libraryDirs: List<String>,
    )

    private fun konanTarget(konanTargetName: String): KonanTarget {
        val dependencies = konanDataDir.get().asFile.resolve("dependencies")
        val llvmBin = CompileKiteRtTask.resolveLlvmBinDir(dependencies, CompileKiteRtTask.DEFAULT_LLVM_PACKAGE) {
            logger.lifecycle("[kiteplayer-libass] $it")
        }
        val clang = CompileKiteRtTask.resolveTool(llvmBin, "clang")
            ?: throw GradleException("No clang under $llvmBin.")
        val spec = CompileKiteRtTask.specFor(konanTargetName)
        val sysrootRelative = spec.konanSysroot ?: throw GradleException("$konanTargetName has no konan sysroot")
        val sysroot = dependencies.resolve(sysrootRelative)
        if (!sysroot.isDirectory) {
            throw GradleException(
                "No konan sysroot at $sysroot for $konanTargetName. Compiling any Kotlin/Native code " +
                    "for that target provisions it; see hostJniAvailable in kiteplayer-libass/build.gradle.kts.",
            )
        }
        val packageRoot = dependencies.resolve(sysrootRelative.substringBefore('/'))
        // Where the GNU toolchains keep libstdc++ and libgcc: beside the sysroot, not inside it.
        val libraryDirs = packageRoot.walkTopDown().maxDepth(6)
            .filter { it.isFile && (it.name == "libstdc++.a" || it.name == "libgcc.a" || it.name == "libstdc++.so") }
            .map { it.parentFile.absolutePath }
            .distinct()
            .toList()
        return KonanTarget(clang.absolutePath, llvmBin.absolutePath, spec.triple, sysroot.absolutePath, libraryDirs)
    }

    private fun writeJniMd(out: File, platform: String, export: String, call: String, jlong: String): File {
        val dir = out.resolve("jni-md/$platform").also { it.mkdirs() }
        dir.resolve("jni_md.h").writeText(
            """
            /* Generated by BuildLibassHostJniTask: the platform half of jni.h for a cross link. */
            #ifndef _JAVASOFT_JNI_MD_H_
            #define _JAVASOFT_JNI_MD_H_
            #define JNIEXPORT $export
            #define JNIIMPORT
            #define JNICALL $call
            typedef int jint;
            typedef $jlong jlong;
            typedef signed char jbyte;
            #endif
            """.trimIndent() + "\n",
        )
        return dir
    }

    private fun xcrunSdkPath(sdkName: String): String {
        val stdout = java.io.ByteArrayOutputStream()
        execOperations.exec {
            commandLine("xcrun", "--sdk", sdkName, "--show-sdk-path")
            standardOutput = stdout
        }
        return stdout.toString().trim().also {
            if (it.isEmpty() || !File(it).isDirectory) throw GradleException("xcrun --sdk $sdkName gave '$it'.")
        }
    }

    companion object {
        const val RESOURCE_ROOT: String = "kiteplayer-libass-native"

        /** The chain directory name each host triple's adapter is linked from. */
        val CHAIN_DIR_FOR_HOST: Map<String, String> = mapOf(
            "macos-arm64" to "macos-arm64",
            "macos-x64" to "macos-x64",
            "linux-x64" to "linux-x64",
            "linux-arm64" to "linux-arm64",
            "windows-x64" to "mingw-x64",
        )

        /** The konan target whose sysroot a cross link needs, or null for a native host link. */
        val KONAN_TARGET_FOR_HOST: Map<String, String?> = mapOf(
            "macos-arm64" to null,
            "macos-x64" to null,
            "linux-x64" to "linux_x64",
            "linux-arm64" to "linux_arm64",
            "windows-x64" to "mingw_x64",
        )
    }
}

/**
 * Links the web module, `kiteass.mjs` plus `kiteass.wasm`, from the shared C driver's export table
 * and the wasm32 chain, with emscripten.
 *
 * The module is what a page hosts beside the codec module; the Kotlin/Wasm binding imports it at
 * run time and never bundles it. Single-threaded, no SIMD, no cross-origin isolation requirement,
 * exactly the codec's default artifact policy: a module that hangs on an embedder's site is worse
 * than a slower one. Every 64-bit quantity crosses as a double, so the big-integer flag is not
 * needed and cannot be forgotten.
 */
abstract class BuildLibassWasmModuleTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {

    /** `native/src/kite_ass_wasm.c`. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFile: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val driverDir: DirectoryProperty

    /** The wasm32 chain's `ass-chain` directory, holding `include` and `lib`. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val chainDir: DirectoryProperty

    @get:Input
    abstract val emcc: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    init {
        group = "kiteplayer"
        description = "Link the libass web module (kiteass.mjs and kiteass.wasm) with emscripten."
    }

    @TaskAction
    fun link() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val chain = chainDir.get().asFile
        val lib = chain.resolve("lib")
        MergeAssChainTask.MEMBERS.forEach { member ->
            if (!lib.resolve("$member.a").isFile) throw GradleException("The wasm32 chain at $chain has no lib/$member.a.")
        }
        val command = listOf(
            emcc.get(), "-O2",
            "-I${driverDir.get().asFile.absolutePath}",
            "-I${chain.resolve("include").absolutePath}",
            "-x", "c", sourceFile.get().asFile.absolutePath, "-x", "none",
        ) + MergeAssChainTask.MEMBERS.map { lib.resolve("$it.a").absolutePath } + listOf(
            "-sMODULARIZE=1", "-sEXPORT_ES6=1", "-sENVIRONMENT=web,worker,node",
            "-sALLOW_MEMORY_GROWTH=1", "-sSTACK_SIZE=1048576",
            "-sEXPORTED_FUNCTIONS=${EXPORTS.joinToString(",")}",
            "-sEXPORTED_RUNTIME_METHODS=HEAPU8",
            "-o", out.resolve(MODULE_NAME).absolutePath,
        )
        logger.info("[kiteplayer-libass] " + command.joinToString(" "))
        execOperations.exec { commandLine(command) }
        val wasm = out.resolve(WASM_NAME)
        if (!out.resolve(MODULE_NAME).isFile || !wasm.isFile) {
            throw GradleException("emcc reported success but produced no $MODULE_NAME and $WASM_NAME in $out.")
        }
        logger.lifecycle("[kiteplayer-libass] web: $MODULE_NAME + $WASM_NAME (${wasm.length()} bytes)")
    }

    companion object {
        const val MODULE_NAME: String = "kiteass.mjs"
        const val WASM_NAME: String = "kiteass.wasm"

        /** Every export the Kotlin/Wasm binding calls, plus the allocator it fills buffers with. */
        val EXPORTS: List<String> = listOf(
            "_kass_library_version", "_kass_open", "_kass_close", "_kass_alloc", "_kass_free",
            "_kass_open_track", "_kass_open_document", "_kass_add_event", "_kass_clear_events",
            "_kass_add_font", "_kass_set_frame", "_kass_render", "_kass_packed_ptr", "_kass_packed_size",
            "_malloc", "_free",
        )
    }
}
