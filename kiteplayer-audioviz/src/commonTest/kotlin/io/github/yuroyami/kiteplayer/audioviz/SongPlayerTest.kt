package io.github.yuroyami.kiteplayer.audioviz

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SongPlayerTest {
    @Test
    fun renderingFixturesUseTheAudibleClockAndDeliverAllHitsWithRealLookahead() {
        val rate = 8_000
        val samples = FloatArray(rate * 2)
        val attacks = listOf(400_000L, 700_000L, 760_000L, 1_200_000L)
        for (time in attacks) samples[(time * rate / 1_000_000L).toInt()] = 0.8f
        val player = SongPlayer(samples, sampleRate = rate, warmupSeconds = 0f)
        val delivered = mutableListOf<AudioEvent>()
        val anticipated = mutableSetOf<Long>()
        var doubleHit = false
        repeat(30) { index ->
            val frame = player.next(0.07f)
            if (frame.hasTimestamp) {
                val expected = ((index + 1) * 0.07 * 1_000_000).toLong()
                if (expected <= 1_800_000L) assertTrue(abs(frame.ptsMicros - expected) <= 2L,
                    "render at the audible clock, not the newest analysis: ${frame.ptsMicros} vs $expected")
                val batch = assertNotNull(frame.events)
                var onsets = 0
                for (eventIndex in 0 until batch.size) {
                    val event = batch[eventIndex].event
                    if (event.detection.kind == AudioEventKind.Onset) {
                        delivered += event
                        onsets++
                    }
                }
                if (onsets == 2) doubleHit = true
            }
            player.future.nextEvent(AudioEventKind.Onset)?.let { next ->
                assertTrue(next.secondsUntil in 0f..0.1f)
                anticipated += next.event.sequence
            }
        }
        assertEquals(4, delivered.size)
        assertEquals(4, delivered.map { it.sequence }.distinct().size)
        assertTrue(doubleHit, "two hits inside one display interval must survive the render harness")
        assertTrue(anticipated.isNotEmpty())
        assertTrue(anticipated.all { sequence -> delivered.any { it.sequence == sequence } })
        for (index in attacks.indices) assertTrue(abs(delivered[index].detection.ptsMicros - attacks[index]) <= 6_000L)
    }

    @Test
    fun theFixtureEndsWithoutReplayingTheStartOrHoldingStaleAudio() {
        val samples = FloatArray(8_000)
        samples[1_600] = 0.8f
        val player = SongPlayer(samples, sampleRate = 8_000, warmupSeconds = 0f)
        var hits = 0
        var last: SpectrumFrame? = null
        repeat(40) {
            last = player.next(0.05f)
            last?.events?.let { batch ->
                for (index in 0 until batch.size) if (batch[index].event.detection.kind == AudioEventKind.Onset) hits++
            }
        }
        assertEquals(1, hits)
        assertTrue(last?.hasTimestamp == false, "after the source ends, old features expire")
        assertTrue(player.latest.ptsMicros < 1_000_000L, "the finite source must not be decoded repeatedly")
        assertEquals(-1f, player.future.nextOnsetSeconds)
    }
}
