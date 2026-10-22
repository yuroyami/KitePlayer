package io.github.yuroyami.kiteplayer.ffmpeg

/**
 * There is no media directory on the web, because there is no filesystem to hold one.
 *
 * The browser half runs the matrix anyway, from clips the page fetches into memory: see
 * [WebFormatMatrixTest]. The node half has neither a page nor a directory, so it runs no matrix.
 */
internal actual fun formatMatrixMediaDir(): String? = null

/** No filesystem to write a report to. [WebFormatMatrixTest] prints the report instead. */
internal actual fun writeConformanceReport(fileName: String, markdown: String): String? = null

internal actual fun conformancePlatformName(): String = "wasm-js"
