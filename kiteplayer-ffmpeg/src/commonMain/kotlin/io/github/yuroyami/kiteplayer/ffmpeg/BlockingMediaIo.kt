package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kitecodec.MediaByteSource

/**
 * Adapts the engine's suspending [MediaIo] onto KiteCodec's blocking [MediaByteSource] (M1).
 *
 * Every target that HAS a blocking primitive bridges with `runBlocking`, which is real and
 * deliberate: FFmpeg's demuxer pulls bytes synchronously on the demux worker, the one thread
 * [MediaIo]'s contract already confines it to, so parking that worker parks nothing else.
 *
 * The web has no such primitive, which is why this is expect/actual rather than one shared file
 * (17.14 X-09). Blocking is forbidden on the browser's main thread, so the wasmJs actual stages
 * the bytes instead and says so when it cannot.
 */
internal expect class BlockingMediaIo(io: MediaIo) : MediaByteSource
