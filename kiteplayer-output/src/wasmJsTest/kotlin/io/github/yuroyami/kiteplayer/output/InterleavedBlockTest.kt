package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The block the web sink stages into.
 *
 * Worth its own file because it is the first web buffer that KEEPS what is written to it. Its
 * predecessor discarded every write, so its bounds checks were the only behaviour it had and
 * a wrong offset could not corrupt anything. Here it can, and it would be audible.
 */
class InterleavedBlockTest {

    private val stereo = AudioFormat(sampleRate = 48_000, channels = 2, sampleFormat = SampleFormat.F32)

    @Test
    fun interleavedWritesLandAtTheirFrameOffset() {
        val block = InterleavedBlock(stereo, capacityFrames = 4)
        block.writeInterleaved(floatArrayOf(1f, 2f, 3f, 4f), sourceOffset = 0, destinationFrameOffset = 2, frames = 2)
        assertEquals(listOf(0f, 0f, 0f, 0f, 1f, 2f, 3f, 4f), block.samples.toList())
    }

    /**
     * A ring read that wraps becomes two writes, and the second must land after the first. This is
     * why the destination offset is not optional in the contract.
     */
    @Test
    fun twoWritesFromAWrappedRingDoNotOverwriteEachOther() {
        val block = InterleavedBlock(stereo, capacityFrames = 3)
        block.writeInterleaved(floatArrayOf(1f, 1f), 0, destinationFrameOffset = 0, frames = 1)
        block.writeInterleaved(floatArrayOf(2f, 2f, 3f, 3f), 0, destinationFrameOffset = 1, frames = 2)
        assertEquals(listOf(1f, 1f, 2f, 2f, 3f, 3f), block.samples.toList())
    }

    @Test
    fun aSourceOffsetReadsFromTheMiddleOfItsSource() {
        val block = InterleavedBlock(stereo, capacityFrames = 1)
        block.writeInterleaved(floatArrayOf(9f, 9f, 5f, 6f), sourceOffset = 2, destinationFrameOffset = 0, frames = 1)
        assertEquals(listOf(5f, 6f), block.samples.toList())
    }

    /** Planar writes stride by the channel count, which is the whole difference from interleaved. */
    @Test
    fun planeWritesStrideByTheChannelCount() {
        val block = InterleavedBlock(stereo, capacityFrames = 3)
        block.writePlane(channel = 1, source = floatArrayOf(7f, 8f, 9f), sourceOffset = 0, destinationFrameOffset = 0, frames = 3)
        assertEquals(listOf(0f, 7f, 0f, 8f, 0f, 9f), block.samples.toList())
    }

    @Test
    fun silenceClearsOnlyTheFramesItWasGiven() {
        val block = InterleavedBlock(stereo, capacityFrames = 3)
        block.writeInterleaved(FloatArray(6) { 1f }, 0, 0, 3)
        block.writeSilence(frameOffset = 1, frames = 2)
        assertEquals(listOf(1f, 1f, 0f, 0f, 0f, 0f), block.samples.toList())
    }

    /** The render callback is engine code, so a bad offset is a defect worth catching at the write. */
    @Test
    fun writesPastTheEndAreRefusedRatherThanTruncated() {
        val block = InterleavedBlock(stereo, capacityFrames = 2)
        assertFailsWith<IllegalStateException> {
            block.writeInterleaved(FloatArray(6), 0, destinationFrameOffset = 1, frames = 2)
        }
        assertFailsWith<IllegalStateException> {
            block.writePlane(channel = 0, source = FloatArray(4), sourceOffset = 0, destinationFrameOffset = 0, frames = 3)
        }
        assertFailsWith<IllegalStateException> {
            block.writePlane(channel = 2, source = FloatArray(2), sourceOffset = 0, destinationFrameOffset = 0, frames = 2)
        }
        assertFailsWith<IllegalStateException> { block.writeSilence(frameOffset = 1, frames = 2) }
    }

    @Test
    fun readingPastTheEndOfTheSourceIsRefused() {
        val block = InterleavedBlock(stereo, capacityFrames = 4)
        assertFailsWith<IllegalStateException> {
            block.writeInterleaved(FloatArray(2), sourceOffset = 0, destinationFrameOffset = 0, frames = 2)
        }
    }
}
