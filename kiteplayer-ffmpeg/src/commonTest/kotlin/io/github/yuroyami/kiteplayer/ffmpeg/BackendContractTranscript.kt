package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.spi.PlayerPacket
import io.github.yuroyami.kiteplayer.spi.VideoDecoder
import io.github.yuroyami.kiteffmpeg.FFmpeg

/** Produces the same sorted, path-free backend contract on JVM and macOS arm64. */
internal suspend fun backendContractTranscript(mediaDirectory: String): String {
    val identity = FFmpeg.identity
    val lines = mutableListOf(
        "identity.acceptable=${identity.isAcceptable}",
        "identity.cAbi=${identity.cAbiVersion}",
        "identity.configurationsAgree=${identity.configurationsAgree}",
        "identity.ref=${identity.buildFFmpegRef}",
    )
    identity.libraries.forEach { library ->
        lines += "identity.${library.name}=${library.headerVersion}/${library.runtimeVersion}/${library.verdict}"
    }

    val source = KiteFFmpegSourceFactory()
        .open(MediaItem("${mediaDirectory.trimEnd('/')}/sync1080p30.mp4")) as KiteFFmpegSource
    try {
        val stream = requireNotNull(source.firstVideo) { "sync fixture has no video stream" }
        val extradata = requireNotNull(stream.codecExtradata) { "sync fixture has no codec extradata" }
        check(extradata.isNotEmpty() && extradata[0].toInt() and 0xff == 1) {
            "sync fixture does not expose an avcC version-one record"
        }
        source.selectStreams(setOf(stream.index))
        val decoder = requireNotNull(
            source.videoDecoderFactories().first().create(stream, HwdecPolicy.Off),
        ) { "software decoder refused sync fixture" }
        try {
            val first = requireNotNull(nextContractFrame(source, stream.index, decoder)) {
                "sync fixture produced no first frame"
            }
            try {
                val bytes = first.frame.copyPlanesToByteArray()
                val rgba = tightlyPackedToRgba(
                    bytes = bytes,
                    width = first.size.width,
                    height = first.size.height,
                    pixelFormat = first.pixelFormat,
                    colorSpace = first.colorSpace,
                )
                lines += "media.first.pts=${first.pts.micros}"
                lines += "media.first.rgbaHash=${rgba.fnv1a64()}"
                lines += "media.first.size=${first.size.width}x${first.size.height}"
            } finally {
                first.close()
            }

            source.seekToKeyframe(Pts(1_000_000L))
            decoder.flush(Generation.Initial.next())
            val landed = requireNotNull(nextContractFrame(source, stream.index, decoder)) {
                "sync fixture produced no frame after seek"
            }
            try {
                lines += "media.seek.landing=${landed.pts.micros}"
            } finally {
                landed.close()
            }
        } finally {
            decoder.close()
        }
    } finally {
        source.close()
    }
    return lines.sorted().joinToString(separator = "\n", postfix = "\n")
}

/** Exercises the PlayerPacket boundary against a real KiteFFmpeg-owned packet. */
internal suspend fun assertOwnedPacketPayload(mediaDirectory: String) {
    val source = KiteFFmpegSourceFactory()
        .open(MediaItem("${mediaDirectory.trimEnd('/')}/sync1080p30.mp4")) as KiteFFmpegSource
    try {
        val stream = requireNotNull(source.firstVideo) { "sync fixture has no video stream" }
        source.selectStreams(setOf(stream.index))
        val packet = requireNotNull(nextContractPacket(source, stream.index)) {
            "sync fixture has no video packet"
        }
        val expected: ByteArray
        val snapshot: ByteArray
        try {
            val metadataSize = packet.sizeBytes
            expected = packet.copyBytes()
            snapshot = expected.copyOf()
            val independent = packet.copyBytes()
            check(expected.size == metadataSize) {
                "packet payload size ${expected.size} differs from metadata $metadataSize"
            }
            check(expected !== independent && expected.contentEquals(independent)) {
                "copyBytes must return equal, independently owned arrays"
            }
            if (expected.isNotEmpty()) {
                independent[0] = (independent[0].toInt() xor 0xff).toByte()
                check(!expected.contentEquals(independent)) {
                    "mutating one packet payload copy changed another copy"
                }
            }
        } finally {
            packet.close()
        }
        check(expected.contentEquals(snapshot)) {
            "owned payload changed after its source packet closed"
        }
    } finally {
        source.close()
    }
}

private suspend fun nextContractFrame(
    source: KiteFFmpegSource,
    streamIndex: Int,
    decoder: VideoDecoder,
): KiteFFmpegVideoFrame? {
    val pendingFrames = ArrayDeque<KiteFFmpegVideoFrame>()
    try {
        while (true) {
            val packet = nextContractPacket(source, streamIndex)
            if (packet == null) {
                while (!decoder.send(null)) {
                    val pending = decoder.receive() ?: error("decoder refused drain with no pending output")
                    pendingFrames.addLast(pending as KiteFFmpegVideoFrame)
                }
                return pendingFrames.removeFirstOrNull() ?: decoder.receive() as KiteFFmpegVideoFrame?
            }
            try {
                while (!decoder.send(packet)) {
                    val pending = decoder.receive() ?: error("decoder refused a packet with no pending output")
                    pendingFrames.addLast(pending as KiteFFmpegVideoFrame)
                }
            } finally {
                packet.close()
            }
            pendingFrames.removeFirstOrNull()?.let { return it }
            (decoder.receive() as KiteFFmpegVideoFrame?)?.let { return it }
        }
    } finally {
        pendingFrames.forEach { it.close() }
    }
}

private suspend fun nextContractPacket(source: KiteFFmpegSource, streamIndex: Int): PlayerPacket? {
    while (true) {
        val packet = source.readPacket() ?: return null
        if (packet.streamIndex == streamIndex) return packet
        packet.close()
    }
}

private fun ByteArray.fnv1a64(): String {
    var hash = 0xcbf29ce484222325UL
    for (byte in this) {
        hash = (hash xor byte.toUByte().toULong()) * 0x100000001b3UL
    }
    return hash.toString(16).padStart(16, '0')
}
