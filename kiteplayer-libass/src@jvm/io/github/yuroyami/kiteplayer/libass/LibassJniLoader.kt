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
 * 3. The copy bundled in this jar under `kiteplayer-libass-native/<os>-<arch>/`, extracted once
 *    to a temporary directory. This is what makes one dependency line enough on a desktop.
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
    val directory = Files.createTempDirectory("kiteplayer-libass-jni").toFile().apply { deleteOnExit() }
    var library: File? = null
    for (name in manifest) {
        val stream = LibassTypesetter::class.java.getResourceAsStream("$directoryResource/$name")
            ?: throw UnsatisfiedLinkError("the bundle manifest names $name but $directoryResource/$name is not in the jar.")
        val target = File(directory, name)
        stream.use { Files.copy(it, target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        target.deleteOnExit()
        if (name == fileName) library = target
    }
    System.load((library ?: throw UnsatisfiedLinkError("the bundle at $directoryResource carries no $fileName.")).absolutePath)
}

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
