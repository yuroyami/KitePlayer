package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaItem
import kotlinx.coroutines.runBlocking
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The FFmpeg source says when its length is FFmpeg's guess from the bit rate (#422), with real
 * FFmpeg on the JVM: an MP3 written without its Xing header states no length, and a WAV states one.
 */
class EstimatedDurationSourceTest {

    @Test
    fun anMp3WithoutItsXingHeaderHasAnEstimatedLength() = runBlocking {
        val bytes = Base64.getDecoder().decode(MP3_WITHOUT_XING.filterNot { it.isWhitespace() })
        KiteFFmpegMediaBackend().open(MediaItem("custom://noxing.mp3", formatHint = "mp3", io = { BytesIo(bytes) })).use { session ->
            assertNotNull(session.source.duration)
            assertTrue(session.source.durationIsEstimate, "an MP3 with no Xing header states no length")
        }
    }

    @Test
    fun aWavStatesItsLength() = runBlocking {
        KiteFFmpegMediaBackend().open(MediaItem("custom://one-second.wav", formatHint = "wav", io = { BytesIo(oneSecondWav()) })).use { session ->
            assertNotNull(session.source.duration)
            assertFalse(session.source.durationIsEstimate)
        }
    }

    /** A seekable reader over [bytes] that knows its size, as a file is: FFmpeg's estimate needs the size. */
    private class BytesIo(private val bytes: ByteArray) : MediaIo {
        private var at = 0
        override val size: Long get() = bytes.size.toLong()
        override val seekable: Boolean get() = true
        override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (at >= bytes.size) return -1
            val n = minOf(length, bytes.size - at)
            bytes.copyInto(into, offset, at, at + n)
            at += n
            return n
        }
        override suspend fun seek(position: Long) {
            at = position.toInt()
        }
        override fun close() = Unit
    }

    /** 16-bit mono 8 kHz, one second of silence, its length stated by the data chunk. */
    private fun oneSecondWav(): ByteArray {
        val data = 16_000
        val b = ByteArray(44 + data)
        fun ascii(at: Int, s: String) = s.encodeToByteArray().copyInto(b, at)
        fun le(at: Int, x: Int, n: Int) { for (i in 0 until n) b[at + i] = (x ushr (8 * i)).toByte() }
        ascii(0, "RIFF"); le(4, 36 + data, 4); ascii(8, "WAVEfmt "); le(16, 16, 4)
        le(20, 1, 2); le(22, 1, 2); le(24, 8_000, 4); le(28, 16_000, 4); le(32, 2, 2); le(34, 16, 2)
        ascii(36, "data"); le(40, data, 4)
        return b
    }

    private companion object {
        /** One second of a 440 Hz tone at 8 kHz, 8 kbps MP3, written by ffmpeg with `-write_xing 0`. */
        const val MP3_WITHOUT_XING: String = """
/+MYxAAK8AbVuUEAAv9HcNtgBg+D4PvUCAIHIPg+fxOD4PoggcRB8H34IOu/wxwG/SGOXfznT7ulJoc4WcQ3/GVEJRyhC3+D/+MY
xA8QmUKQAZRQAG8H6ge5ABlxEnAPiYDmR1gd6mHvgGgCQCorCKC6//IR6KpEPh8ab//5CgNCUJA1/4lOg0r//////////+MYxAcO
aKY8Ad4AAP/////40iEYA4ASSQXAKMCsF4wfwajCHFKMQulwxvhNjDbBaMD0D4FAXiEAMDAKoSW07dP//vgSpVAg/+MYxAgNqKYs
AFa8YCmJFGfQGycnl9GIANucd+h5p+CpmGqC8YJQC5QA8gRBoACAVL6x////3+U///9a/1//fGMDIYEAAdcF/+MYxAwO6KYsAKew
gIIBpyIHmpGIyKqc5FMpq8iHmHkCsYMoFpgYARG6RjOX8Urx///Z/v9P////////Rf//+TsVQOAwSYQN/+MYxAsNaKYwAAb+RJgK
ECJ0AhGBgSyEkYCKDdEgESDADgwAMAVCAAsiAEECa4b/////+j///1L/5/wCvIvqBQBjxhoi50XB/+MYxBAMOKY0AAa8RIbAc5vY
ucmiQFeYWoEQQD6GARoI0dlKWRYf////////1//n/DDK1AHHTkMA0BkwOgVjC1FfNpiqMzrR/+MYxBoMUKY0AAewSBUwnAVDA9An
MBwBYzGAw0wGH4/////61f9P/8JmmoLAYFMIFzCzYxaDMEsdozFOGjGvFyMDIG8CgUCM/+MYxCMMKKY0AD78YAHTPHgAUzma3///
//9S///4JW0gyW6MaENIFOqSMOYGU4ITiTSIBFBQwwsEgYC4AIVAFQ8WywrX/////+MYxC0MaKY0AAa8RPr///9d/+/8YZ2pYt4h
ACAIE5gTA3GEWNKapWTJl4ilmD0DMYF4FpgMALGYgOOlYxOz/////qX/T//A/+MYxDYMYKY0AAewSCYigGMEBMcXMqyNd3MIMbI0
5s/DK1FSMGYGMwFwHkQV7IUprMFtqvgeo4i6jcDgDhoEEICrMOYDg4Ly/+MYxD8KiKY0AC68YF40kALjDFAHMEoAQwHQCwKAOIgA
FlsI1////3ei///+shwYkEIAt7AwCgpAwLgUAwNAfA1xfZAybl/A/+MYxE8MMKY0AAH8QMbYcwMLYQQWA2GjgYCwChYeKO0kAj//
///cR43Xa/////3TYGmLCKN+vIzSacbjKTYXtipb8wEfA0AC/+MYxFkMOKZEAVYAAJX/W/gIsKIFIDkDFEr//8d5JDkJhdJc6bf/
/5ocRN1gQgf/+sCEAdAgcP//yoENB4caQ///96EGss09/+MYxGMXsZ6MKZtoAGmq1bbWVteaOjJ2y5cuucg1A6jJIknrRk8yYvMr
Vq13GlxoUNxBsQVwU7IbiL8FdCVMQU1FMy4xMDBV/+MYxD8NILaEQcwIAVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
"""
    }
}
