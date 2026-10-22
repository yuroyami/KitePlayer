package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.KotlinAudioRing
import io.github.yuroyami.kiteplayer.internal.framesToMicros
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioSinkBuffer
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The feeder and the device callback on two real threads, with every write opening a segment, so the
 * four segment slots are rewritten as fast as the reader resolves them (#417).
 *
 * Each chunk of [CHUNK] frames carries a timestamp ten seconds past the last one, so the media time of
 * every frame is known exactly, and a segment read torn, with one write's start frame and another's
 * timestamp, dates the anchor at a time no frame has. Every anchor the callback publishes without
 * giving up on a slot must therefore be the exact time of the frame it played up to.
 */
class AudioRingTearTest {

    private val format = AudioFormat(RATE, 1, SampleFormat.F32)

    private class NullBuffer(override val format: AudioFormat) : AudioSinkBuffer {
        override fun writeInterleaved(source: FloatArray, sourceOffset: Int, destinationFrameOffset: Int, frames: Int) = Unit
        override fun writeSilence(frameOffset: Int, frames: Int) = Unit
    }

    @Test
    fun noAnchorIsEverDatedFromATornSegment() {
        val ring = KotlinAudioRing(format, CAPACITY)
        val failure = AtomicReference<Throwable?>(null)
        val deadline = System.nanoTime() + 30_000_000_000L

        val feeder = thread(name = "ring-feeder") {
            val samples = FloatArray(CHUNK)
            try {
                for (chunk in 0 until CHUNKS) {
                    var done = 0
                    while (done < CHUNK) {
                        // The timestamp rides the chunk's first frame only; a remainder continues it.
                        val pts = if (done == 0) Pts(chunk * JUMP_US) else null
                        val wrote = ring.write(samples, 0, CHUNK - done, pts)
                        if (wrote == 0) {
                            if (System.nanoTime() > deadline) error("the feeder stalled at chunk $chunk")
                            Thread.onSpinWait()
                        }
                        done += wrote
                    }
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }

        var played = 0L
        var checked = 0
        var gaveUp = 0L
        val buffer = NullBuffer(format)
        val total = CHUNKS.toLong() * CHUNK
        while (played < total && failure.get() == null) {
            if (System.nanoTime() > deadline) error("the callback stalled at frame $played of $total")
            val before = ring.segmentGiveups
            val got = ring.render(buffer, REQUEST, 0L)
            if (got == 0) {
                Thread.onSpinWait()
                continue
            }
            played += got
            if (ring.segmentGiveups != before) {
                gaveUp++
                continue
            }
            val anchor = ring.anchor() ?: error("no anchor after $played frames")
            val last = played - 1
            val segment = last / CHUNK
            val expected = segment * JUMP_US + framesToMicros(last + 1 - segment * CHUNK, RATE)
            assertEquals(expected, anchor.pts.micros, "the anchor after frame $last, segment $segment")
            checked++
        }
        feeder.join()
        assertNull(failure.get())
        // Not vacuous: most renders resolve a slot cleanly, and every one of those was checked.
        assertTrue(checked > CHUNKS / 4, "only $checked anchors were checked, $gaveUp renders gave up")
    }

    private companion object {
        const val RATE = 48_000
        const val CHUNK = 48
        const val CHUNKS = 200_000
        const val REQUEST = 37
        const val CAPACITY = 480
        const val JUMP_US = 10_000_000L
    }
}
