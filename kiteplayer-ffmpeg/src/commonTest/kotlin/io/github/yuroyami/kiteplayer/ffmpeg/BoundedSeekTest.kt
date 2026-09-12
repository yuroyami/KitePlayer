package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteffmpeg.FFmpegError
import io.github.yuroyami.kiteffmpeg.FFmpegException
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class BoundedSeekTest {
    @Test
    fun firstAttemptBoundsPrerollToTwoSeconds() {
        val attempts = mutableListOf<Pair<Long, Long?>>()
        seekBackward(10_000_000) { target, floor -> attempts += target to floor }
        assertEquals(listOf<Pair<Long, Long?>>(10_000_000L to 8_000_000L), attempts)
        attempts.clear()
        seekBackward(500_000) { target, floor -> attempts += target to floor }
        assertEquals(listOf<Pair<Long, Long?>>(500_000L to 0L), attempts)
    }

    @Test
    fun refusedWindowRetriesExactlyOnceWithoutFloor() {
        val attempts = mutableListOf<Pair<Long, Long?>>()
        seekBackward(10_000_000) { target, floor ->
            attempts += target to floor
            if (floor != null) throw FFmpegException(FFmpegError.InvalidArgument(-22, "no keyframe in window"))
        }
        assertEquals(listOf(10_000_000L to 8_000_000L, 10_000_000L to null), attempts)
    }

    @Test
    fun failureOfBothAttemptsIsReported() {
        var attempts = 0
        val failure = FFmpegException(FFmpegError.AvError(-1, "seek refused"))
        assertSame(failure, assertFailsWith<FFmpegException> {
            seekBackward(10_000_000) { _, _ -> attempts++; throw failure }
        })
        assertEquals(2, attempts)
    }

    @Test
    fun cancellationAndInterruptedSourceNeverRetry() {
        for (failure in listOf(CancellationException("cancelled"), FFmpegException(FFmpegError.Interrupted(-1, "closed")))) {
            var attempts = 0
            assertSame(failure, assertFailsWith<RuntimeException> {
                seekBackward(10_000_000) { _, _ -> attempts++; throw failure }
            })
            assertEquals(1, attempts)
        }
    }
}
