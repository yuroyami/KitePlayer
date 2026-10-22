package io.github.yuroyami.kiteplayer.view

import io.github.yuroyami.kiteplayer.PlaybackStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * When fresh picture-in-picture parameters are worth pushing.
 *
 * Auto-enter is the reason this is not simply "push every time". The OS reads the flag from the
 * parameters it was last given, so a value built while the player was paused disables auto-enter
 * for the rest of the session. The status becoming Playing has to push, and a status change that
 * nothing can see must not.
 */
class PipUpdatePumpTest {

    @Test
    fun `an open with no size yet still pushes a first value`() {
        val pump = PipUpdatePump(autoEnterWhilePlaying = true)
        val first = pump.next(0, 0, 0, PlaybackStatus.Opening)
        assertEquals(PipUpdate(PipAspect(16, 9), autoEnter = false), first)
    }

    @Test
    fun `the size arriving pushes the picture's own aspect`() {
        val pump = PipUpdatePump(autoEnterWhilePlaying = true)
        pump.next(0, 0, 0, PlaybackStatus.Opening)
        assertEquals(
            PipUpdate(PipAspect(4, 3), autoEnter = false),
            pump.next(640, 480, 0, PlaybackStatus.Paused),
        )
    }

    @Test
    fun `playing arms auto enter`() {
        val pump = PipUpdatePump(autoEnterWhilePlaying = true)
        pump.next(1920, 1080, 0, PlaybackStatus.Paused)
        assertEquals(
            PipUpdate(PipAspect(16, 9), autoEnter = true),
            pump.next(1920, 1080, 0, PlaybackStatus.Playing),
        )
    }

    @Test
    fun `a status change nothing can see pushes nothing`() {
        val pump = PipUpdatePump(autoEnterWhilePlaying = true)
        pump.next(1920, 1080, 0, PlaybackStatus.Paused)
        assertNull(pump.next(1920, 1080, 0, PlaybackStatus.Buffering))
        assertNull(pump.next(1920, 1080, 0, PlaybackStatus.Opening))
    }

    @Test
    fun `the same values twice push once`() {
        val pump = PipUpdatePump(autoEnterWhilePlaying = true)
        pump.next(1920, 1080, 0, PlaybackStatus.Playing)
        assertNull(pump.next(1920, 1080, 0, PlaybackStatus.Playing))
    }

    @Test
    fun `a turned picture pushes a turned aspect`() {
        val pump = PipUpdatePump(autoEnterWhilePlaying = true)
        pump.next(1920, 1080, 0, PlaybackStatus.Playing)
        assertEquals(
            PipUpdate(PipAspect(9, 16), autoEnter = true),
            pump.next(1920, 1080, 90, PlaybackStatus.Playing),
        )
    }

    @Test
    fun `turning auto enter off never arms it`() {
        val pump = PipUpdatePump(autoEnterWhilePlaying = false)
        assertEquals(
            PipUpdate(PipAspect(16, 9), autoEnter = false),
            pump.next(1920, 1080, 0, PlaybackStatus.Playing),
        )
        assertNull(pump.next(1920, 1080, 0, PlaybackStatus.Paused))
    }

    @Test
    fun `leaving playing disarms auto enter again`() {
        val pump = PipUpdatePump(autoEnterWhilePlaying = true)
        pump.next(1920, 1080, 0, PlaybackStatus.Playing)
        assertEquals(
            PipUpdate(PipAspect(16, 9), autoEnter = false),
            pump.next(1920, 1080, 0, PlaybackStatus.Paused),
        )
    }
}
