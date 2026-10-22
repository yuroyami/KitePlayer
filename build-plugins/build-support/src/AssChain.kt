/* Copyright 2026 yuroyami. Apache License, Version 2.0 (see LICENSE). */

package io.github.yuroyami.kiteplayer.build

import java.io.File
import java.util.zip.ZipInputStream

/**
 * The libass chain of one target: the static archives of libass, HarfBuzz, FreeType and FriBidi plus
 * their headers. KiteFFmpeg cross-builds it.
 *
 * A build finds a chain in three places, in this order:
 * 1. `KITEPLAYER_LIBASS_ROOT`, a KiteFFmpeg `native-libs/deps` folder.
 * 2. `../KiteFFmpeg/native-libs/deps` next to this repository.
 * 3. The pinned release asset `ass-chain-<target>.zip` of KiteFFmpeg, downloaded into [cacheDir].
 */
object AssChain {
    const val RELEASE_TAG: String = "ass-chain-r2"
    const val RELEASE_BASE_URL: String = "https://github.com/yuroyami/KiteFFmpeg/releases/download"
    const val ARCHIVE_NAME: String = "libkiteass.a"
    val MEMBERS: List<String> = listOf("libass", "libharfbuzz", "libfreetype", "libfribidi")

    /** The chain folder name of each Kotlin/Native target that `kiteplayer-libass` builds for. */
    val NATIVE_CHAIN_DIRS: Map<String, String> = mapOf(
        "macos_arm64" to "macos-arm64",
        "ios_arm64" to "ios-arm64",
        "ios_simulator_arm64" to "ios-simulator-arm64",
        "linux_x64" to "linux-x64",
        "linux_arm64" to "linux-arm64",
        "mingw_x64" to "mingw-x64",
    )

    /** The pins of `ass-chain.sha256`: chain folder name to the SHA-256 of its release asset. */
    fun readPins(pinFile: File): Map<String, String> = pinFile.takeIf { it.isFile }?.readLines().orEmpty()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .associate { line ->
            val (sha, asset) = line.split(Regex("\\s+"), limit = 2)
            asset.removePrefix("ass-chain-").removeSuffix(".zip") to sha
        }

    /** A chain folder holds `lib/` and `include/`. */
    class Chain(val dirName: String, val root: File) {
        val includeDir: File get() = root.resolve("include")
        val libDir: File get() = root.resolve("lib")
    }

    /** The local chain roots, in lookup order. [repoRoot] is the KitePlayer checkout. */
    fun localRoots(repoRoot: File): List<File> = listOfNotNull(
        System.getenv("KITEPLAYER_LIBASS_ROOT")?.takeIf { it.isNotBlank() }?.let { File(it).absoluteFile.normalize() },
        repoRoot.resolve("../KiteFFmpeg/native-libs/deps").normalize().takeIf { it.isDirectory },
    )

    /**
     * The chain of [dirName], local or downloaded into [cacheDir]. Returns null when no chain exists
     * locally and [pins] has no line for it.
     */
    fun find(dirName: String, repoRoot: File, pins: Map<String, String>, cacheDir: File, log: (String) -> Unit): Chain? {
        localRoots(repoRoot).map { it.resolve("$dirName/ass-chain") }
            .firstOrNull { it.resolve("lib/libass.a").isFile }
            ?.let { return Chain(dirName, it) }
        val pin = pins[dirName] ?: return null
        val fetched = cacheDir.resolve(dirName)
        val marker = fetched.resolve(".sha256")
        if (!marker.isFile || marker.readText().trim() != pin || !fetched.resolve("lib/libass.a").isFile) {
            fetch(dirName, pin, fetched, log)
            marker.writeText(pin + "\n")
        }
        return Chain(dirName, fetched)
    }

    /** Downloads one chain asset, checks its pin and unpacks it into [outputDir]. */
    fun fetch(dirName: String, expectedSha256: String, outputDir: File, log: (String) -> Unit) {
        val asset = "ass-chain-$dirName.zip"
        val url = "$RELEASE_BASE_URL/$RELEASE_TAG/$asset"
        outputDir.deleteRecursively()
        outputDir.mkdirs()
        log("fetching $url")
        val bytes = download(url, log)
        val digest = sha256(bytes)
        if (digest != expectedSha256.lowercase()) {
            throw BuildFailure(
                "$asset from $url has SHA-256 $digest, expected $expectedSha256. " +
                    "The pin lives in kiteplayer-libass/ass-chain.sha256; a new chain build means a new pin.",
            )
        }
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = outputDir.resolve(entry.name).normalize()
                if (!target.startsWith(outputDir)) throw BuildFailure("$asset carries an entry outside its root: ${entry.name}")
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                }
                zip.closeEntry()
            }
        }
        if (!outputDir.resolve("lib/libass.a").isFile || !outputDir.resolve("include/ass/ass.h").isFile) {
            throw BuildFailure("$asset unpacked without lib/libass.a or include/ass/ass.h; not an ass chain.")
        }
    }

    /**
     * Merges the four chain archives into one `libkiteass.a` for one Kotlin/Native target.
     * One archive, because a consumer's final link sees cinterop's static libraries in any order. Each
     * object gets its library's prefix, so two libraries with a `utils.o` cannot shadow each other.
     */
    fun merge(chain: Chain, konanTargetName: String, konanDataDir: File, outputDir: File, log: (String) -> Unit): File {
        outputDir.deleteRecursively()
        outputDir.mkdirs()
        val llvmBin = CompileKiteRt.resolveLlvmBinDir(konanDataDir.resolve("dependencies"), CompileKiteRt.DEFAULT_LLVM_PACKAGE, log)
        val archiver = CompileKiteRt.resolveTool(llvmBin, "llvm-ar")
            ?: throw BuildFailure("No llvm-ar in the konan LLVM package; a Kotlin/Native compile provisions it.")
        val objects = mutableListOf<File>()
        MEMBERS.forEach { member ->
            val archive = chain.libDir.resolve("$member.a")
            if (!archive.isFile) throw BuildFailure("The ass chain at ${chain.libDir} has no $member.a.")
            val extractDir = outputDir.resolve("objects/$member").also { it.mkdirs() }
            runProcess(listOf(archiver.absolutePath, "x", archive.absolutePath), workingDir = extractDir)
            extractDir.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }.forEach { file ->
                val renamed = extractDir.resolve("${member}__${file.name}")
                check(file.renameTo(renamed)) { "could not rename ${file.absolutePath}" }
                objects += renamed
            }
        }
        if (objects.isEmpty()) throw BuildFailure("The ass chain at ${chain.libDir} produced no objects.")
        val merged = outputDir.resolve(ARCHIVE_NAME)
        val format = if (konanTargetName.startsWith("macos") || konanTargetName.startsWith("ios")) "darwin" else "gnu"
        runProcess(listOf(archiver.absolutePath, "crs", "--format=$format", merged.absolutePath) + objects.map { it.absolutePath })
        if (!merged.isFile) throw BuildFailure("llvm-ar reported success but produced no $merged.")
        outputDir.resolve("objects").deleteRecursively()
        log("$konanTargetName: ${merged.name} from ${objects.size} objects (${merged.length()} bytes)")
        return merged
    }
}

/** Lower-case hex SHA-256 of a file. */
fun sha256(file: File): String = sha256(file.readBytes())
