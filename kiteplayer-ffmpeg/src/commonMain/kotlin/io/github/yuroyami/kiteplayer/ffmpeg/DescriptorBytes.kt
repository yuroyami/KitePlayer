package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteffmpeg.MediaByteSource

/**
 * Reads [descriptor] by position, or answers null to leave it to FFmpeg's `fd` protocol.
 *
 * The caller keeps [descriptor], and every duplicate of it shares one file offset with it. FFmpeg's
 * `fd` protocol reads and seeks a duplicate, so each open of the item moved the offset of the
 * caller. A positional read moves no offset. The reader owns a duplicate of its own, so it also
 * keeps reading after the caller closes [descriptor].
 *
 * Only a regular file is read by position. Anything else, such as a pipe, is a stream: it has no
 * offset to keep, and FFmpeg reads it as one. A platform that cannot read a raw descriptor by
 * position answers null for every descriptor.
 */
internal expect fun descriptorByteSource(descriptor: Int): MediaByteSource?
