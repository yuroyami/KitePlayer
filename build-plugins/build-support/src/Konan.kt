/* Copyright 2026 yuroyami. Apache License, Version 2.0 (see LICENSE). */

package io.github.yuroyami.kiteplayer.build

import java.io.File

/**
 * The Kotlin/Native data directory: `KONAN_DATA_DIR`, or `~/.konan`.
 * Its `dependencies` folder holds the LLVM package and the sysroots that konan downloads.
 */
fun konanDataDir(): File {
    val fromEnv = System.getenv("KONAN_DATA_DIR")
    return if (fromEnv.isNullOrBlank()) File(System.getProperty("user.home"), ".konan") else File(fromEnv)
}

/** The cache folder of the Kotlin Toolchain on this host, where it unpacks Kotlin/Native. */
fun toolchainCacheDir(): File {
    val home = System.getProperty("user.home")
    return when {
        hostIsMac -> File(home, "Library/Caches/JetBrains/Kotlin")
        hostIsWindows -> File(System.getenv("LOCALAPPDATA") ?: "$home/AppData/Local", "JetBrains/Kotlin")
        else -> File(System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.cache", "JetBrains/Kotlin")
    }
}

/**
 * A JDK home with the JNI headers (`include/jni.h`). The toolchain runs plugins on a JRE without
 * them, so this tries `JAVA_HOME`, then the running Java, then the JDKs that the toolchain unpacked.
 */
fun findJniJavaHome(): File? {
    val candidates = buildList {
        System.getenv("JAVA_HOME")?.takeIf { it.isNotBlank() }?.let { add(File(it)) }
        add(File(System.getProperty("java.home")))
        toolchainCacheDir().resolve("extract.cache").listFiles().orEmpty()
            .filter { it.isDirectory && it.name.contains("jdk", ignoreCase = true) }
            .sortedByDescending { it.name }
            .forEach { unpacked ->
                // A JDK archive unpacks into one folder; on macOS its home is Contents/Home.
                unpacked.listFiles().orEmpty().filter { it.isDirectory }.forEach { top ->
                    add(top.resolve("Contents/Home"))
                    add(top)
                }
            }
    }
    return candidates.firstOrNull { it.resolve("include/jni.h").isFile }
}

/**
 * The `konanc` launcher of Kotlin/Native [kotlinVersion]: the copy that the toolchain unpacked,
 * or else one in the konan data directory.
 */
fun findKonanc(kotlinVersion: String): File? {
    val launcher = if (hostIsWindows) "bin/konanc.bat" else "bin/konanc"
    val unpacked = toolchainCacheDir().resolve("extract.cache").listFiles().orEmpty()
        .filter { it.isDirectory && "kotlin-native-prebuilt-$kotlinVersion-" in it.name }
        .flatMap { dir -> listOf(dir) + dir.listFiles().orEmpty().filter(File::isDirectory) }
    val konanHomes = konanDataDir().listFiles().orEmpty()
        .filter { it.isDirectory && it.name.startsWith("kotlin-native-prebuilt-") && it.name.endsWith("-$kotlinVersion") }
    return (unpacked + konanHomes).map { it.resolve(launcher) }.firstOrNull { it.isFile }
}

/**
 * Makes konan download the LLVM package and the sysroot of [konanTarget] when they are missing.
 * Every Kotlin/Native compile for that target needs them anyway, so this only moves the download earlier.
 */
fun provisionKonanDependencies(konanTarget: String, kotlinVersion: String, log: (String) -> Unit) {
    val konanc = findKonanc(kotlinVersion) ?: downloadKotlinNative(kotlinVersion, log)
    log("provisioning the konan dependencies of $konanTarget with ${konanc.absolutePath}")
    runProcess(
        listOf(konanc.absolutePath, "-Xcheck-dependencies", "-target", konanTarget),
        environment = mapOf("JAVA_HOME" to System.getProperty("java.home")),
    )
}

/** The host part of the kotlin-native-prebuilt artifact name, such as `macos-aarch64`. */
fun kotlinNativeHost(): String {
    val arch = if (System.getProperty("os.arch").orEmpty() in setOf("aarch64", "arm64")) "aarch64" else "x86_64"
    val os = when {
        hostIsMac -> "macos"
        hostIsWindows -> "windows"
        else -> "linux"
    }
    return "$os-$arch"
}

/**
 * Downloads Kotlin/Native [kotlinVersion] from Maven Central into the konan data directory and
 * returns its `konanc`. A fresh machine needs it when a C task here runs before the Kotlin
 * Toolchain has unpacked its own copy. The archive must match the SHA-1 that Central publishes.
 */
fun downloadKotlinNative(kotlinVersion: String, log: (String) -> Unit): File {
    val host = kotlinNativeHost()
    val extension = if (hostIsWindows) "zip" else "tar.gz"
    val name = "kotlin-native-prebuilt-$kotlinVersion-$host.$extension"
    val url = "https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-native-prebuilt/$kotlinVersion/$name"
    val dataDir = konanDataDir().also { it.mkdirs() }
    log("no Kotlin/Native $kotlinVersion on this machine; downloading $url")
    val bytes = download(url, log)
    val expected = String(download("$url.sha1", log)).trim().substringBefore(' ').lowercase()
    val actual = java.security.MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
    if (actual != expected) throw BuildFailure("$name has SHA-1 $actual, but Maven Central lists $expected.")
    val archive = File(dataDir, name)
    archive.writeBytes(bytes)
    try {
        if (hostIsWindows) {
            java.util.zip.ZipInputStream(archive.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val target = dataDir.resolve(entry.name).normalize()
                    if (!target.startsWith(dataDir)) throw BuildFailure("$name carries an entry outside its root: ${entry.name}")
                    if (entry.isDirectory) target.mkdirs() else {
                        target.parentFile.mkdirs()
                        target.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
        } else {
            runProcess(listOf("tar", "-xzf", archive.absolutePath, "-C", dataDir.absolutePath))
        }
    } finally {
        archive.delete()
    }
    return findKonanc(kotlinVersion)
        ?: throw BuildFailure("$name unpacked into $dataDir, but no konanc launcher was found in it.")
}

