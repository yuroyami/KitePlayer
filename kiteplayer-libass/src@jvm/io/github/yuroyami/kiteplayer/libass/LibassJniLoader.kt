package io.github.yuroyami.kiteplayer.libass

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Finds and loads `kiteplayer_libass_jni` on a desktop JVM, the same three ways KiteFFmpeg's own
 * loader finds its adapter, in the same order:
 *
 * 1. `-Dkiteplayer.libass.jni.path=/abs/path` wins outright.
 * 2. `System.loadLibrary`, which reads `java.library.path`, for packagers that ship their own copy.
 * 3. The copy bundled in this jar under `kiteplayer-libass-native/<os>-<arch>/`, extracted to a
 *    temporary folder named for its contents and reused by every later launch (see
 *    [extractBundle]). This is what makes one dependency line enough on a desktop.
 *
 * The bundled library is self-contained: libass, HarfBuzz, FreeType and FriBidi are linked into it
 * statically, so one file is the whole adapter and the manifest names exactly one entry.
 */
internal actual fun loadLibassJni() {
    val override = System.getProperty("kiteplayer.libass.jni.path")
    if (!override.isNullOrBlank()) {
        System.load(override)
        return
    }
    if (runCatching { System.loadLibrary(LIBRARY_NAME) }.isSuccess) return

    val directoryResource = "/$RESOURCE_ROOT/$platformDirectory"
    val manifest = LibassTypesetter::class.java.getResourceAsStream("$directoryResource/$MANIFEST_NAME")
        ?.bufferedReader()?.use { it.readLines() }
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?: throw UnsatisfiedLinkError(
            "kiteplayer_libass_jni is neither on java.library.path nor bundled at $directoryResource. " +
                "This build of kiteplayer-libass carries no native library for $platformDirectory; " +
                "supply one with -Dkiteplayer.libass.jni.path or -Djava.library.path.",
        )
    val files = manifest.associateWith { name ->
        LibassTypesetter::class.java.getResourceAsStream("$directoryResource/$name")?.use { it.readBytes() }
            ?: throw UnsatisfiedLinkError("the bundle manifest names $name but $directoryResource/$name is not in the jar.")
    }
    if (fileName !in files) throw UnsatisfiedLinkError("the bundle at $directoryResource carries no $fileName.")
    val directory = extractBundle(File(System.getProperty("java.io.tmpdir")), files)
    System.load(File(directory, fileName).absolutePath)
}

/**
 * Writes [files] into a folder of [temp] named for their contents, and answers that folder (#651).
 *
 * A launch reuses the copy an earlier one wrote and writes only a file that is missing or differs,
 * so the folder holds one copy per build of the library rather than one per launch. Windows
 * cannot delete a library that is loaded, so a folder made fresh at every launch and left to
 * `deleteOnExit` stayed behind at every launch, 5 MB each time. The other folders of this prefix,
 * those of other builds and of the old one-per-launch loader, are deleted once they are an hour
 * old, so a process that is just starting keeps the copy it is about to load; a delete that fails,
 * because a running process holds the library, is left for a later launch.
 */
internal fun extractBundle(temp: File, files: Map<String, ByteArray>, nowMillis: Long = System.currentTimeMillis()): File {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    for ((name, bytes) in files.toSortedMap()) {
        digest.update(name.encodeToByteArray())
        digest.update(0)
        digest.update(bytes)
    }
    val tag = digest.digest().take(12).joinToString("") { ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1) }
    val directory = File(temp, "$EXTRACT_PREFIX-$tag")
    directory.mkdirs()
    // Fresh at every launch, so a launch of another build leaves a folder in use alone.
    directory.setLastModified(nowMillis)
    for ((name, bytes) in files) {
        val target = File(directory, name)
        if (target.isFile && target.length() == bytes.size.toLong() && target.readBytes().contentEquals(bytes)) continue
        // Written beside its place and moved there whole, so a launch at the same moment never
        // loads half a file.
        val partial = Files.createTempFile(directory.toPath(), "$name.", ".part")
        try {
            Files.write(partial, bytes)
            try {
                Files.move(partial, target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(partial, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (failure: java.io.IOException) {
            // A loaded library on Windows cannot be replaced; the same bytes already there serve.
            if (!(target.isFile && target.readBytes().contentEquals(bytes))) throw failure
        } finally {
            Files.deleteIfExists(partial)
        }
    }
    temp.listFiles()?.forEach { other ->
        if (other.isDirectory && other.name.startsWith(EXTRACT_PREFIX) && other != directory &&
            nowMillis - other.lastModified() > STALE_AFTER_MILLIS
        ) {
            runCatching { other.deleteRecursively() }
        }
    }
    return directory
}

/** The folder name of every copy this loader writes, and of the folders the earlier loader made. */
internal const val EXTRACT_PREFIX = "kiteplayer-libass-jni"

/** How old another copy's folder must be before a launch deletes it. */
private const val STALE_AFTER_MILLIS = 60 * 60 * 1000L

/** `macos-arm64`, `linux-x64`, `windows-x64`: the same names KiteFFmpeg's vendored trees use. */
private val platformDirectory: String by lazy {
    val name = System.getProperty("os.name").orEmpty().lowercase()
    val arch = System.getProperty("os.arch").orEmpty().lowercase()
    val os = when {
        "mac" in name || "darwin" in name -> "macos"
        "win" in name -> "windows"
        else -> "linux"
    }
    val cpu = if (arch in setOf("aarch64", "arm64")) "arm64" else "x64"
    "$os-$cpu"
}

private val fileName: String by lazy {
    when {
        platformDirectory.startsWith("macos") -> "lib$LIBRARY_NAME.dylib"
        platformDirectory.startsWith("windows") -> "$LIBRARY_NAME.dll"
        else -> "lib$LIBRARY_NAME.so"
    }
}

private const val LIBRARY_NAME = "kiteplayer_libass_jni"
internal const val RESOURCE_ROOT = "kiteplayer-libass-native"
private const val MANIFEST_NAME = "manifest.txt"

/** Linux desktops keep fonts in these; macOS and Windows never read the list. */
internal actual fun defaultFontDirectories(): List<String> {
    if (!platformNeedsSystemFontFiles) return emptyList()
    val home = System.getProperty("user.home").orEmpty()
    return listOf("/usr/share/fonts", "/usr/local/share/fonts", "$home/.fonts", "$home/.local/share/fonts")
}

/** Only Linux: the macOS and Windows chains carry CoreText and DirectWrite providers. */
internal actual val platformNeedsSystemFontFiles: Boolean = System.getProperty("os.name").orEmpty().lowercase().let {
    "mac" !in it && "darwin" !in it && "win" !in it
}
