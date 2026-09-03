package io.github.yuroyami.kiteplayer.ffmpeg

/**
 * The repository's testmedia tree, or wherever the test task points.
 *
 * The JVM ran no real media until phase W, because KiteFFmpeg's jvm variant was a placeholder. It
 * carries the JNI adapter now, so the desktop JVM runs the same 17.5 matrix the native targets do.
 */
internal actual fun formatMatrixMediaDir(): String? =
    System.getenv("KITEPLAYER_TESTMEDIA") ?: "testmedia"

internal actual fun writeConformanceReport(fileName: String, markdown: String): String? {
    val dir = java.io.File("build/reports/conformance")
    dir.mkdirs()
    val file = java.io.File(dir, fileName)
    file.writeText(markdown)
    return file.path
}

internal actual fun conformancePlatformName(): String {
    val os = System.getProperty("os.name").orEmpty().lowercase().replace(" ", "-")
    val arch = System.getProperty("os.arch").orEmpty().lowercase()
    return "jvm-$os-$arch"
}
