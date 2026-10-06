package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.MonotonicClock
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioSinkEvent
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A device that refuses float output plays 16-bit PCM with dither instead of failing the open (#445). */
class AudioTrackFloatFallbackTest {

    private class Driver(override val encoding: DriverEncoding) : AudioTrackDriver {
        override val bufferSizeInFrames: Int = 2048
        override val sessionId: Int = 1
        override fun onWriterThreadStart() = Unit
        override fun play() = Unit
        override fun pause() = Unit
        override fun stop() = Unit
        override fun flush() = Unit
        override fun release() = Unit
        override fun write(source: FloatArray, offsetFloats: Int, sizeFloats: Int): Int {
            Thread.sleep(1)
            return sizeFloats
        }
        override fun timestamp(): DriverTimestamp? = null
        override fun playbackHeadPosition(): Int = 0
    }

    private object Clock : MonotonicClock {
        override fun nanos(): Long = System.nanoTime()
    }

    @Test
    fun aRefusedFloatOpensSixteenBit() {
        val asked = mutableListOf<DriverEncoding>()
        val driver = openWithFloatFallback { encoding ->
            asked += encoding
            if (encoding == DriverEncoding.Float) throw IllegalArgumentException("getMinBufferSize refused float")
            Driver(encoding)
        }
        assertEquals(DriverEncoding.Pcm16, driver.encoding)
        assertEquals(listOf(DriverEncoding.Float, DriverEncoding.Pcm16), asked)
    }

    @Test
    fun aDeviceThatTakesFloatIsAskedNothingElse() {
        val asked = mutableListOf<DriverEncoding>()
        val driver = openWithFloatFallback { encoding -> asked += encoding; Driver(encoding) }
        assertEquals(DriverEncoding.Float, driver.encoding)
        assertEquals(listOf(DriverEncoding.Float), asked)
    }

    @Test
    fun aDeviceThatRefusesBothFailsWithBothReasons() {
        val failure = assertFailsWith<IllegalStateException> {
            openWithFloatFallback { encoding ->
                if (encoding == DriverEncoding.Float) throw IllegalArgumentException("no float")
                throw IllegalStateException("no 16-bit either")
            }
        }
        assertEquals("no 16-bit either", failure.message)
        assertEquals("no float", failure.suppressed.single().message)
    }

    @Test
    fun sixteenBitSamplesMatchTheFloatsWithinOneStep() {
        val source = FloatArray(4096) { i -> kotlin.math.sin(i * 0.01).toFloat() * 0.9f }.also {
            it[0] = 1f
            it[1] = -1f
            it[2] = 1.5f
            it[3] = -1.5f
        }
        val out = ShortArray(source.size)
        floatsToPcm16(source, 0, source.size, out, Pcm16Dither())
        for (i in source.indices) {
            val wanted = source[i].coerceIn(-1f, 1f) * 32767f
            assertTrue(abs(out[i] - wanted) <= 1.5f, "sample $i: ${source[i]} became ${out[i]}")
        }
        assertEquals(Short.MAX_VALUE, out[2], "over full scale clips at the top")
        assertTrue(out[3] <= -32767, "under full scale clips at the bottom")
    }

    @Test
    fun ditherSpreadsAConstantAcrossNeighbouringSteps() {
        // A level halfway between two steps lands on both, which is what dither is for.
        val source = FloatArray(2000) { 100.5f / 32767f }
        val out = ShortArray(source.size)
        floatsToPcm16(source, 0, source.size, out, Pcm16Dither())
        val seen = out.toSet()
        assertTrue(100.toShort() in seen && 101.toShort() in seen, "dither left $seen")
        assertTrue(seen.all { it in 99..102 }, "dither spread past one step: $seen")
    }

    @Test
    fun theSinkSaysOnceThatItPlaysSixteenBit() = runBlocking {
        val sink = AudioTrackSink({ _, _ -> Driver(DriverEncoding.Pcm16) }, Clock)
        val seenEvents = java.util.Collections.synchronizedList(mutableListOf<AudioSinkEvent>())
        val events = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(1500) { sink.events.collect { seenEvents += it } }
            seenEvents.toList()
        }
        sink.open(AudioFormat(48_000, 2, SampleFormat.F32)) { _, frames, _ -> frames }
        sink.start()
        Thread.sleep(200)
        sink.stop()
        sink.start()
        Thread.sleep(200)
        sink.stop()
        val seen = withTimeout(5000) { events.await() }
        sink.close()
        assertEquals(1, seen.size, "the fallback was reported ${seen.size} times: $seen")
        val changed = seen.single() as AudioSinkEvent.DeviceChanged
        assertTrue("16-bit" in changed.detail, changed.detail)
    }

    @Test
    fun aFloatSinkSaysNothing() = runBlocking {
        val sink = AudioTrackSink({ _, _ -> Driver(DriverEncoding.Float) }, Clock)
        val seenEvents = java.util.Collections.synchronizedList(mutableListOf<AudioSinkEvent>())
        val events = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(500) { sink.events.collect { seenEvents += it } }
            seenEvents.toList()
        }
        sink.open(AudioFormat(48_000, 2, SampleFormat.F32)) { _, frames, _ -> frames }
        sink.start()
        Thread.sleep(200)
        sink.stop()
        val seen = events.await()
        sink.close()
        assertEquals(emptyList(), seen)
    }
}
