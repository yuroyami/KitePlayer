package io.github.yuroyami.kiteplayer.compose

/**
 * A converter was handed a frame from a backend it cannot read.
 *
 * Thrown instead of letting an implicit cast fail, for two reasons. A `ClassCastException`'s
 * message is a compiler implementation detail that reads differently on Kotlin/Native and the JVM,
 * so nothing downstream can branch on it and no two bug reports agree. And carrying both type names
 * is what lets the renderer say which pairing is wrong rather than that something went wrong.
 */
internal class UnsupportedFrameType(
    val actual: String,
    val expected: String,
) : IllegalArgumentException(
    "this renderer draws $expected frames and the backend produced $actual; " +
        "pair the Compose video surface with the FFmpeg backend, or attach a renderer that reads $actual",
)
