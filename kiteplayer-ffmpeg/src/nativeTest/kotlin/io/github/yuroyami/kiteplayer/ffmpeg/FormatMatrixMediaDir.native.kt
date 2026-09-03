@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import kotlinx.cinterop.toKString

/** Set by the Gradle test task (SIMCTL_CHILD_-forwarded on a simulator). */
internal actual fun formatMatrixMediaDir(): String? =
    platform.posix.getenv("KITEPLAYER_TESTMEDIA")?.toKString() ?: "testmedia"

/** The reports directory the CI jobs upload, beside the module's other build output. */
private const val REPORT_DIR = "build/reports/conformance"

internal actual fun writeConformanceReport(fileName: String, markdown: String): String? {
    platform.posix.system("mkdir -p $REPORT_DIR")
    val path = "$REPORT_DIR/$fileName"
    val file = platform.posix.fopen(path, "w") ?: return null
    return try {
        platform.posix.fputs(markdown, file)
        path
    } finally {
        platform.posix.fclose(file)
    }
}

@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
internal actual fun conformancePlatformName(): String = kotlin.native.Platform.osFamily.name.lowercase() +
    "-" + kotlin.native.Platform.cpuArchitecture.name.lowercase()
