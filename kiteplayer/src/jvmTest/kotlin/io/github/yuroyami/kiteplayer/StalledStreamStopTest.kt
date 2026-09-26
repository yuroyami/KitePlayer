package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.io.ofStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.AudioSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

/**
 * A stream that stops sending bytes without ending must not hold stop and close for ever (#276).
 * The stream serves the start of a real file, then blocks like a pipe whose writer stays open.
 */
class StalledStreamStopTest {

    private val media: File? = sequenceOf(
        System.getenv("KITEPLAYER_TESTMEDIA")?.let { File(it, MEDIA) },
        File("testmedia/$MEDIA"),
        File("../testmedia/$MEDIA"),
    ).filterNotNull().firstOrNull { it.isFile }

    private class StallingStream(private val prefix: ByteArray) : InputStream() {
        private var position = 0
        private val closedSignal = CountDownLatch(1)
        val closes = AtomicInteger()

        @Volatile
        var blocked = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position < prefix.size) {
                val count = minOf(len, prefix.size - position)
                prefix.copyInto(b, off, position, position + count)
                position += count
                return count
            }
            blocked = true
            closedSignal.await()
            throw IOException("Stream closed")
        }

        override fun close() {
            closes.incrementAndGet()
            closedSignal.countDown()
        }
    }

    @Test
    fun stopReturnsWhileAStreamReadWaitsForBytes() = runBlocking {
        val file = media ?: return@runBlocking println("SKIP: no $MEDIA to play")
        if (AudioSystem.getMixerInfo().isEmpty()) return@runBlocking println("SKIP: no audio mixer")
        val stream = StallingStream(file.readBytes().copyOf(PREFIX_BYTES))
        val player = assertNotNull(KitePlayerPlatform.createOrNull(), "no default desktop player")
        try {
            player.open(MediaItem.from(MediaIo.ofStream { stream }, "stalled.mkv"))
            player.play()
            withTimeout(20.seconds) { while (!stream.blocked) delay(20) }
            withTimeout(10.seconds) { player.stop() }
            assertEquals(PlaybackStatus.Idle, player.state.value.status)
            assertEquals(1, stream.closes.get(), "the stream is closed once")
        } finally {
            withTimeout(15.seconds) { player.closeAndAwait() }
        }
        assertEquals(1, stream.closes.get(), "close does not close the stream again")
    }

    private companion object {
        const val MEDIA = "baseline.mkv"
        const val PREFIX_BYTES = 2_000_000
    }
}
