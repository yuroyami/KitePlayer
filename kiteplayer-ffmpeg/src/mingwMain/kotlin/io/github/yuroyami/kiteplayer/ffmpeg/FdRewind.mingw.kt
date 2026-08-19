package io.github.yuroyami.kiteplayer.ffmpeg

/**
 * Windows: the documented no-op.
 *
 * The `fd` pre-open option carries an Android `content://` descriptor, which cannot arrive here,
 * and mingw's `off_t` is 32 bit while every other native target's is 64, so the POSIX call cannot
 * live in a source set shared with them anyway. The expect's own contract already says a platform
 * with no way to seek a raw integer descriptor answers with a no-op.
 */
internal actual fun rewindFdOption(options: Map<String, String>) {
    // Nothing to rewind: no caller on this platform supplies `fd`.
}
