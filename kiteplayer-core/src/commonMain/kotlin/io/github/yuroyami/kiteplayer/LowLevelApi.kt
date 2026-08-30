package io.github.yuroyami.kiteplayer

/**
 * Marks API that hands FFmpeg's own strings straight through to a backend.
 *
 * The rest of KitePlayer is typed and backend-neutral by construction: a `VideoScale`, a
 * `HwdecPolicy`, a `TrackId`. Those describe what you want and every backend has to honour them or
 * refuse. The declarations behind this annotation do not: they carry text that only FFmpeg parses,
 * so they mean nothing to a WebCodecs or platform-decoder backend and cannot be checked here.
 *
 * What opting in means:
 *
 * - The string is validated by the BACKEND, at open, not by the compiler and not by this library.
 *   A typo is a refused open with FFmpeg's message, not a compile error.
 * - It ties that call to FFmpeg. A build that swaps the backend loses it silently or fails.
 * - There is no stability promise on the accepted syntax; it is whatever the linked FFmpeg parses.
 *
 * This mirrors the sibling library's own `KiteFFmpegLowLevelApi` seam, and for the same reason: an
 * escape hatch is worth having and worth being unable to reach by accident.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Raw FFmpeg syntax: parsed by the backend rather than by this library, and meaningless " +
        "to a non-FFmpeg backend. Opt in with @OptIn(KitePlayerLowLevelApi::class) when you have " +
        "decided to depend on FFmpeg specifically.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.TYPEALIAS)
public annotation class KitePlayerLowLevelApi
