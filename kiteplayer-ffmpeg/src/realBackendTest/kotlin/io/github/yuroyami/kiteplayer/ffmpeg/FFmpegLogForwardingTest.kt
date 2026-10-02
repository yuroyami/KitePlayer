package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.KiteLog
import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.ofBytes
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/** FFmpeg's own log lines reach [KiteLog] with the tag `ffmpeg`, and nothing else prints them (#235). */
class FFmpegLogForwardingTest {

    @Test
    fun ffmpegLinesReachKiteLogUnderTheirOwnTag() = runBlocking {
        val lines = atomic(listOf<Pair<String, String>>())
        KiteLog.install { tag, message -> lines.value = lines.value + (tag to message) }
        try {
            // An MP4 with a file type box and a media box but no movie box: the mov demuxer logs
            // "moov atom not found" as it fails.
            runCatching { openSource(MediaItem("memory://no-moov.mp4", io = MediaIo.ofBytes(noMoovMp4()))).close() }
            val ffmpeg = lines.value.filter { it.first == FFmpegLogForwarding.TAG }
            assertTrue(
                ffmpeg.any { it.second.contains("moov atom not found") },
                "FFmpeg's error did not reach KiteLog; it saw $ffmpeg",
            )
        } finally {
            KiteLog.install(null)
        }
        Unit
    }

    private fun noMoovMp4(): ByteArray {
        fun box(type: String, payload: ByteArray): ByteArray {
            val size = 8 + payload.size
            val header = byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte())
            return header + type.encodeToByteArray() + payload
        }
        val ftyp = box("ftyp", "isom".encodeToByteArray() + byteArrayOf(0, 0, 2, 0) + "isomiso2mp41".encodeToByteArray())
        val mdat = box("mdat", ByteArray(4_096) { (it % 251).toByte() })
        return ftyp + mdat
    }
}
