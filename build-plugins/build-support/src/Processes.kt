/* Copyright 2026 yuroyami. Apache License, Version 2.0 (see LICENSE). */

package io.github.yuroyami.kiteplayer.build

import java.io.File

/**
 * Runs [command] and returns its standard output. Standard error goes to the build log.
 * A non-zero exit fails with the command line and everything it printed.
 */
fun runProcess(
    command: List<String>,
    workingDir: File? = null,
    environment: Map<String, String> = emptyMap(),
): String {
    val builder = ProcessBuilder(command).redirectErrorStream(true)
    workingDir?.let(builder::directory)
    builder.environment().putAll(environment)
    val process = builder.start()
    val output = process.inputStream.bufferedReader().readText()
    val code = process.waitFor()
    if (code != 0) {
        throw BuildFailure("Command exited with $code:\n${command.joinToString(" ")}\n$output")
    }
    return output
}

/** True when this JVM runs on macOS. */
val hostIsMac: Boolean
    get() = System.getProperty("os.name").orEmpty().lowercase().let { "mac" in it || "darwin" in it }

/** True when this JVM runs on Windows. */
val hostIsWindows: Boolean
    get() = System.getProperty("os.name").orEmpty().lowercase().startsWith("windows")

/** The SDK path that `xcrun --sdk <sdkName> --show-sdk-path` prints. */
fun xcrunSdkPath(sdkName: String): String {
    val path = runProcess(listOf("xcrun", "--sdk", sdkName, "--show-sdk-path")).trim()
    if (path.isEmpty() || !File(path).isDirectory) {
        throw BuildFailure("xcrun --sdk $sdkName --show-sdk-path returned '$path', which is not a directory.")
    }
    return path
}
