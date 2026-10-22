package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.KotlinAudioRing
import io.github.yuroyami.kiteplayer.internal.LIMIT_MAX_LOOKAHEAD
import io.github.yuroyami.kiteplayer.internal.limitLookaheadFrames
import io.github.yuroyami.kiteplayer.internal.limitReleaseFrames
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioSinkBuffer
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ring's peak limiter (#504): a frame past full scale is turned down smoothly instead of being
 * clamped by the device, and every other frame goes through untouched. The C ring's half is
 * `kiteplayer-rt/native/tests/test_ring_limit.c`, with the same cases, and the differential oracle
 * compares the two sample for sample.
 */
class AudioLimiterTest {

    private val format = AudioFormat(sampleRate = RATE, channels = 2, sampleFormat = SampleFormat.F32)
    private val lookahead = limitLookaheadFrames(RATE)
    private val release = limitReleaseFrames(RATE)

    /** A device buffer that keeps what it is handed at the frame it was asked for. */
    private class Capture(override val format: AudioFormat, val samples: FloatArray, var at: Int) : AudioSinkBuffer {
        override fun writeInterleaved(source: FloatArray, sourceOffset: Int, destinationFrameOffset: Int, frames: Int) {
            source.copyInto(
                destination = samples,
                destinationOffset = (at + destinationFrameOffset) * format.channels,
                startIndex = sourceOffset,
                endIndex = sourceOffset + frames * format.channels,
            )
        }

        override fun writeSilence(frameOffset: Int, frames: Int) {
            samples.fill(0f, (at + frameOffset) * format.channels, (at + frameOffset + frames) * format.channels)
        }
    }

    /** A sine at 0.5 with a stretch at [level] times full scale, opposite on the right channel. */
    private fun burst(level: Double): FloatArray = FloatArray(TOTAL * 2) { index ->
        val frame = index / 2
        val amplitude = if (frame >= BURST_START && frame < BURST_START + BURST_FRAMES) level else 0.5
        val v = (amplitude * sin(2.0 * PI * TONE_HZ * frame / RATE)).toFloat()
        if (index % 2 == 0) v else -v
    }

    /** Feeds all of [input] at once and renders it in device-sized requests. */
    private fun playAll(ring: KotlinAudioRing, input: FloatArray): FloatArray {
        val frames = input.size / 2
        val output = FloatArray(input.size)
        assertEquals(frames, ring.write(input, 0, frames, Pts(0)))
        val capture = Capture(format, output, 0)
        while (capture.at < frames) {
            val want = minOf(REQUEST, frames - capture.at)
            assertEquals(want, ring.render(capture, want, deadlineNanos = 1_000L + capture.at))
            capture.at += want
        }
        return output
    }

    /** The power at [hz] over frames [from] until [to] of the left channel, by Goertzel. */
    private fun powerAt(samples: FloatArray, from: Int, to: Int, hz: Double): Double {
        val coefficient = 2.0 * cos(2.0 * PI * hz / RATE)
        var s1 = 0.0
        var s2 = 0.0
        for (i in from until to) {
            val s0 = samples[i * 2] + coefficient * s1 - s2
            s2 = s1
            s1 = s0
        }
        return s1 * s1 + s2 * s2 - coefficient * s1 * s2
    }

    private fun FloatArray.slice(fromFrame: Int, toFrame: Int): FloatArray = copyOfRange(fromFrame * 2, toFrame * 2)

    @Test
    fun theLookaheadAndTheReleaseFollowTheirLaws() {
        assertEquals(240, lookahead)
        assertEquals(4_800, release)
        assertEquals(40, limitLookaheadFrames(8_000))
        assertEquals(LIMIT_MAX_LOOKAHEAD, limitLookaheadFrames(768_000))
        assertEquals(1, limitLookaheadFrames(100))
    }

    @Test
    fun contentWithinFullScaleGoesThroughBitForBit() {
        var state = 12_345
        val input = FloatArray(TOTAL * 2) {
            state = state * 1_664_525 + 1_013_904_223
            ((state ushr 8).toDouble() / (1 shl 23).toDouble() - 1.0).toFloat()
        }
        input[100] = 1f
        input[101] = -1f
        val ring = KotlinAudioRing(format, CAPACITY)
        val output = playAll(ring, input)
        assertContentEquals(input, output)
        assertEquals(0L, ring.limitedFrames)
    }

    @Test
    fun aBurstPastFullScaleIsTurnedDownSmoothlyAndNothingElseMoves() {
        val input = burst(1.4)
        val ring = KotlinAudioRing(format, CAPACITY)
        val output = playAll(ring, input)
        val burstEnd = BURST_START + BURST_FRAMES
        val settled = burstEnd + 2 * lookahead + release

        val largest = output.maxOf { abs(it) }
        assertTrue(largest <= 1f, "a sample left at $largest")

        assertContentEquals(input.slice(0, BURST_START - lookahead), output.slice(0, BURST_START - lookahead))
        assertContentEquals(input.slice(settled, TOTAL), output.slice(settled, TOTAL))

        // A clamp would step the gain from 1 to 1/1.4 within a frame.
        var previous = -1f
        var largestStep = 0f
        for (i in BURST_START - 2 * lookahead until settled) {
            val sample = input[i * 2]
            if (abs(sample) < 0.1f) continue
            val ratio = output[i * 2] / sample
            if (previous >= 0f) largestStep = maxOf(largestStep, abs(ratio - previous))
            previous = ratio
        }
        assertTrue(largestStep < 0.02f, "the gain stepped by $largestStep")

        val fundamental = powerAt(output, BURST_START, burstEnd, TONE_HZ)
        val third = powerAt(output, BURST_START, burstEnd, 3 * TONE_HZ)
        val clamped = FloatArray(input.size) { input[it].coerceIn(-1f, 1f) }
        val clampedThird = powerAt(clamped, BURST_START, burstEnd, 3 * TONE_HZ)
        assertTrue(
            third * 1_000.0 < clampedThird,
            "the third harmonic sits ${10 * log10(fundamental / third)} dB under the tone, " +
                "a clamp's ${10 * log10(fundamental / clampedThird)} dB",
        )

        assertTrue(ring.limitedFrames >= BURST_FRAMES, "limited ${ring.limitedFrames}")
        assertTrue(ring.limitedFrames <= settled - (BURST_START - lookahead), "limited ${ring.limitedFrames}")
    }

    @Test
    fun aLoudFrameTheRingDidNotHoldYetStillNeverLeavesAboveFullScale() {
        val input = burst(1.4)
        val output = FloatArray(input.size)
        val ring = KotlinAudioRing(format, CAPACITY)
        val capture = Capture(format, output, 0)
        // Each render sees exactly the frames it plays and nothing after them.
        while (capture.at < TOTAL) {
            val want = minOf(REQUEST, TOTAL - capture.at)
            assertEquals(want, ring.write(input, capture.at * 2, want, null))
            assertEquals(want, ring.render(capture, want, deadlineNanos = 1_000L + capture.at))
            capture.at += want
        }
        val largest = output.maxOf { abs(it) }
        assertTrue(largest <= 1f, "a sample left at $largest")
        assertTrue(ring.limitedFrames > 0)
    }

    @Test
    fun halfVolumeTakesTheBurstUnderFullScaleAndTheLimiterStaysOut() {
        val input = burst(1.4)
        val ring = KotlinAudioRing(format, CAPACITY)
        ring.setGain(0.5f)
        val output = playAll(ring, input)
        assertContentEquals(FloatArray(input.size) { input[it] * 0.5f }, output)
        assertEquals(0L, ring.limitedFrames)
    }

    @Test
    fun aSteadyBoostIsLeftToTheFold() {
        val ring = KotlinAudioRing(format, CAPACITY)
        ring.setGain(2f)
        playAll(ring, burst(1.4))
        assertEquals(0L, ring.limitedFrames)
    }

    @Test
    fun aFlushInTheMiddleOfAReductionStartsTheLimiterOver() {
        val input = burst(1.4)
        val ring = KotlinAudioRing(format, CAPACITY)
        val scratch = Capture(format, FloatArray(REQUEST * 2), 0)
        assertEquals(BURST_START + 1_000, ring.write(input, 0, BURST_START + 1_000, Pts(0)))
        var rendered = 0
        while (rendered < BURST_START + 512) {
            assertEquals(REQUEST, ring.render(scratch, REQUEST, deadlineNanos = 1_000L + rendered))
            rendered += REQUEST
        }
        val limited = ring.limitedFrames
        assertTrue(limited > 0)
        ring.flush()

        val after = FloatArray(4_096 * 2) { (0.5 * sin(2.0 * PI * TONE_HZ * it / RATE)).toFloat() }
        val output = FloatArray(after.size)
        assertEquals(4_096, ring.write(after, 0, 4_096, Pts(1_000_000)))
        val capture = Capture(format, output, 0)
        while (capture.at < 4_096) {
            assertEquals(REQUEST, ring.render(capture, REQUEST, deadlineNanos = 50_000L + capture.at))
            capture.at += REQUEST
        }
        assertContentEquals(after, output)
        assertEquals(limited, ring.limitedFrames)
    }

    private companion object {
        const val RATE = 48_000
        const val CAPACITY = 32_768
        const val REQUEST = 512
        const val TOTAL = 24_000
        const val BURST_START = 9_600
        const val BURST_FRAMES = 4_800
        const val TONE_HZ = 1_000.0
    }
}
