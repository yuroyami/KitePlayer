/* Copyright 2026 yuroyami. Apache License, Version 2.0 (see LICENSE). */

package io.github.yuroyami.kiteplayer.build.web

import io.github.yuroyami.kiteplayer.build.AssChain
import io.github.yuroyami.kiteplayer.build.BuildFailure
import io.github.yuroyami.kiteplayer.build.LibassAdapters
import io.github.yuroyami.kiteplayer.build.download
import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipInputStream

private val log: (String) -> Unit = { println("[web-assets] $it") }

private const val CENTRAL = "https://repo1.maven.org/maven2"
/** Where KiteFFmpeg publishes the files of its web build. Releases after 0.5.1 put them at the root of a jar. */
private fun codecArchives(version: String): List<String> = listOf(
    "io/github/yuroyami/kiteffmpeg-web/$version/kiteffmpeg-web-$version.jar",
    "io/github/yuroyami/kiteffmpeg-wasm-js/$version/kiteffmpeg-wasm-js-$version-web.zip",
)

/**
 * Writes the files of [settings] into `<moduleDir>/<directory>`, which the toolchain copies beside the
 * page (`resources/`) or the tests (`testResources@wasmJs/`). [marker] is an empty source folder: it
 * only makes the compile wait for this task. The task always runs and rewrites only what changed.
 */
@TaskAction(ExecutionAvoidance.Disabled)
fun prepareWebAssets(
    settings: WebAssetsSettings,
    forTests: Boolean,
    directory: String,
    repoRoot: String,
    moduleDir: String,
    @Output cacheDir: Path,
    @Output marker: Path,
) {
    marker.toFile().mkdirs()
    val set = if (forTests) settings.tests else settings.page
    if (!set.codec && !set.libass && !set.clip) return
    val root = File(repoRoot)
    val target = File(moduleDir).resolve(directory).also { it.mkdirs() }
    val cache = cacheDir.toFile().also { it.mkdirs() }
    if (set.codec) unpackCodec(root, cache, target)
    if (set.libass) linkLibass(root, cache, target)
    if (set.clip) copyClip(root, target)
}

/** The KiteFFmpeg version of `libs.versions.toml`, the one the modules compile against. */
private fun kiteffmpegVersion(root: File): String {
    val catalog = root.resolve("libs.versions.toml")
    return catalog.readLines().firstNotNullOfOrNull { line ->
        Regex("""^\s*kiteffmpeg\s*=\s*"([^"]+)"""").find(line)?.groupValues?.get(1)
    } ?: throw BuildFailure("No kiteffmpeg version in $catalog.")
}

/**
 * The KiteFFmpeg web files from Maven Local when they are there, else from Maven Central, checked against
 * their SHA-1. The jar of a newer release comes first. The web zip of 0.5.1 and earlier is the fallback.
 */
private fun unpackCodec(root: File, cache: File, target: File) {
    val version = kiteffmpegVersion(root)
    val paths = codecArchives(version)
    val mavenLocal = File(System.getProperty("user.home"), ".m2/repository")
    val archive = paths.firstNotNullOfOrNull { path -> mavenLocal.resolve(path).takeIf { it.isFile } }
        ?: paths.firstNotNullOfOrNull { path -> cache.resolve(path.substringAfterLast('/')).takeIf { it.isFile } }
        ?: fetchCodec(version, paths, cache)
    ZipInputStream(archive.inputStream().buffered()).use { stream ->
        while (true) {
            val entry = stream.nextEntry ?: break
            // A jar also carries its manifest, which is not a web file.
            if (entry.name.startsWith("META-INF/")) continue
            val file = target.resolve(entry.name).normalize()
            if (!file.startsWith(target)) throw BuildFailure("$archive carries an entry outside its root: ${entry.name}")
            if (entry.isDirectory) file.mkdirs() else writeIfDifferent(file, stream.readBytes())
        }
    }
}

/** Downloads the first of [paths] that Maven Central has, checks its SHA-1 and keeps only that archive in [cache]. */
private fun fetchCodec(version: String, paths: List<String>, cache: File): File {
    for (path in paths) {
        val url = "$CENTRAL/$path"
        log("fetching $url")
        val bytes = try {
            download(url, log)
        } catch (failure: BuildFailure) {
            if (failure.message.orEmpty().contains("HTTP 404")) continue
            throw failure
        }
        val expected = String(download("$url.sha1", log)).trim().take(40).lowercase()
        val actual = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
        if (actual != expected) throw BuildFailure("$url has SHA-1 $actual, Maven Central says $expected.")
        cache.listFiles().orEmpty()
            .filter { it.isFile && (it.name.startsWith("kiteffmpeg-web-") || it.name.startsWith("kiteffmpeg-wasm-js-")) }
            .forEach { it.delete() }
        return cache.resolve(path.substringAfterLast('/')).also { it.writeBytes(bytes) }
    }
    throw BuildFailure("Maven Central has no web files for KiteFFmpeg $version. Tried: ${paths.joinToString()}.")
}

/** Links the libass web module once per chain, then copies it. Needs `emcc` on PATH. */
private fun linkLibass(root: File, cache: File, target: File) {
    val emcc = LibassAdapters.findEmcc()
        ?: throw BuildFailure("No emcc on PATH, so there is no libass web module. Install emscripten (brew install emscripten).")
    val pins = AssChain.readPins(root.resolve("kiteplayer-libass/ass-chain.sha256"))
    val chain = AssChain.find("wasm32", root, pins, cache.resolve("ass-chain"), log)
        ?: throw BuildFailure("No wasm32 ass chain: no local KiteFFmpeg chain and no pin in kiteplayer-libass/ass-chain.sha256.")
    val source = root.resolve("kiteplayer-libass/native/src/kite_ass_wasm.c")
    val driverDir = root.resolve("kiteplayer-libass/native/src")
    val built = cache.resolve("kiteass")
    val stamp = built.resolve(".inputs")
    val inputs = listOf(chain.root.absolutePath, source.readText().hashCode().toString(), driverDir.listFiles().orEmpty()
        .filter { it.isFile }.sortedBy { it.name }.joinToString(",") { "${it.name}:${it.length()}:${it.lastModified()}" })
        .joinToString("\n")
    if (!stamp.isFile || stamp.readText() != inputs || !built.resolve(LibassAdapters.WASM_NAME).isFile) {
        LibassAdapters.buildWasmModule(emcc, source, driverDir, chain, built, log)
        stamp.writeText(inputs)
    }
    listOf(LibassAdapters.WASM_MODULE_NAME, LibassAdapters.WASM_NAME).forEach { name ->
        writeIfDifferent(target.resolve(name), built.resolve(name).readBytes())
    }
}

private fun copyClip(root: File, target: File) {
    val clip = root.resolve("testmedia/sync1080p30.mp4")
    if (!clip.isFile) throw BuildFailure("No $clip. Run ./scripts/testmedia.sh once to make the test clips.")
    val copy = target.resolve("clip.mp4")
    if (copy.length() != clip.length() || copy.lastModified() < clip.lastModified()) clip.copyTo(copy, overwrite = true)
}

/** Keeps the file date when the bytes are the same, so the toolchain sees no change. */
private fun writeIfDifferent(file: File, bytes: ByteArray) {
    if (file.isFile && file.length() == bytes.size.toLong() && file.readBytes().contentEquals(bytes)) return
    file.parentFile.mkdirs()
    file.writeBytes(bytes)
}
