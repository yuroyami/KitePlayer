package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.TrimStage
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The pipeline's per-channel pre-gain.
 *
 * It exists for two knobs that are not the volume. ReplayGain levels one track against another and
 * is a property of the material, so it belongs on the way IN, before the ring, applied once per
 * track and never touched again. The user's volume is the opposite: it belongs on the way OUT, so a
 * change is heard within one device period instead of one ring depth, and it lives in the ring for
 * exactly that reason.
 *
 * Per channel rather than one scalar because balance will use the same stage, and because a matrix
 * that can only scale every channel equally cannot express one.
 *
 * Bit-exact when it has nothing to do: a stage that copied its buffer anyway would put a copy in
 * the path of every ordinary file to serve a feature almost nobody turns on.
 */
class TrimStageTest {

    @Test
    fun `a stage at unity does not touch the buffer`() {
        val stage = TrimStage(channels = 2)
        val samples = floatArrayOf(0.1f, -0.2f, 0.3f, -0.4f)
        val original = samples.copyOf()
        stage.apply(samples, frames = 2)
        assertContentEquals(original, samples, "a unity stage rewrote its input")
        assertEquals(true, stage.isIdentity)
    }

    @Test
    fun `one gain scales every channel`() {
        val stage = TrimStage(channels = 2)
        stage.setAll(0.5f)
        val samples = floatArrayOf(1f, 1f, 0.4f, -0.4f)
        stage.apply(samples, frames = 2)
        assertContentEquals(floatArrayOf(0.5f, 0.5f, 0.2f, -0.2f), samples)
        assertEquals(false, stage.isIdentity)
    }

    @Test
    fun `channels can differ which is what balance will need`() {
        val stage = TrimStage(channels = 2)
        stage.set(floatArrayOf(1f, 0f))
        val samples = floatArrayOf(0.5f, 0.5f, -0.25f, -0.25f)
        stage.apply(samples, frames = 2)
        assertContentEquals(floatArrayOf(0.5f, 0f, -0.25f, -0f), samples)
    }

    @Test
    fun `only the frames asked for are touched`() {
        // The pipeline hands a buffer whose tail belongs to an earlier, longer call. Scaling past
        // the frame count would corrupt audio that has already been converted.
        val stage = TrimStage(channels = 2)
        stage.setAll(0f)
        val samples = floatArrayOf(1f, 1f, 9f, 9f)
        stage.apply(samples, frames = 1)
        assertContentEquals(floatArrayOf(0f, 0f, 9f, 9f), samples)
    }

    @Test
    fun `six channels are scaled independently`() {
        val stage = TrimStage(channels = 6)
        stage.set(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f))
        val samples = FloatArray(6) { 1f }
        stage.apply(samples, frames = 1)
        assertContentEquals(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f), samples)
    }

    @Test
    fun `a wrong-sized or impossible gain is refused`() {
        val stage = TrimStage(channels = 2)
        assertFailsWith<IllegalArgumentException> { stage.set(floatArrayOf(1f)) }
        assertFailsWith<IllegalArgumentException> { stage.set(floatArrayOf(1f, Float.NaN)) }
        assertFailsWith<IllegalArgumentException> { stage.set(floatArrayOf(1f, -1f)) }
        assertFailsWith<IllegalArgumentException> { stage.setAll(Float.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { TrimStage(channels = 0) }
    }

    @Test
    fun `returning to unity makes it bit-exact again`() {
        // A track with ReplayGain followed by one without must leave the second untouched.
        val stage = TrimStage(channels = 2)
        stage.setAll(0.5f)
        stage.setAll(1f)
        val samples = floatArrayOf(0.1f, -0.2f)
        val original = samples.copyOf()
        stage.apply(samples, frames = 1)
        assertContentEquals(original, samples)
        assertEquals(true, stage.isIdentity)
    }
}
