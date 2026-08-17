package io.github.yuroyami.kiteplayer.ffmpeg

/**
 * The web has one thread, so the "parallel" slices are one slice (17.14 X-09).
 *
 * Not a stub and not a regression to hide: the threaded web artifact needs `SharedArrayBuffer`,
 * which needs COOP and COEP headers on whoever embeds the player, and the S6 spike proved the
 * module HANGS rather than erroring without them. A default artifact that cannot be embedded is
 * worse than one that converts serially.
 *
 * The measured consequence, so nobody has to rediscover it: X-01 timed this loop at 50 to 87 ms per
 * 1080p frame here against about 2.1 ms on four desktop cores. That is why the web draw path does
 * the conversion in C beside the decoder instead (X-11), and why this exists mainly so the module
 * COMPILES for wasm rather than to be the path a web player takes.
 */
internal actual inline fun parallelRowSlices(
    width: Int,
    height: Int,
    crossinline body: (startRow: Int, endRowExclusive: Int) -> Unit,
) {
    body(0, height)
}
