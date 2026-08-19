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
 *
 * The members are declared here as well as in each actual. An `expect class` that names a
 * supertype must still carry that supertype's abstract members, and leaving them out compiles
 * for every real target while failing only `compileCommonMainKotlinMetadata`, which is the klib
 * a publication is built from. That is why this was red from the day the expect/actual split
 * landed until the first publish after it.
 */
internal expect class BlockingMediaIo(io: MediaIo) : MediaByteSource {
    override val size: Long?
    override val seekable: Boolean
    override fun read(into: ByteArray, offset: Int, length: Int): Int
    override fun seek(position: Long)
    override fun close()
}
