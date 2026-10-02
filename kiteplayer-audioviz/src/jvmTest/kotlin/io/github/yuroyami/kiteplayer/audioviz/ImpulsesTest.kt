package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Impulses
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImpulsesTest {

    @Test
    fun theNewestImpulseIsFirstAndAgesByHeardTime() {
        val impulses = Impulses(capacity = 4)
        impulses.add(Impulses.LOW, 0.8f, -0.5f, 0.25f, 0.1f)
        impulses.advance(0.5f)
        impulses.add(Impulses.HIGH, 0.3f, 1.0f, -1.0f, 0.9f)
        impulses.advance(0f)
        assertEquals(2, impulses.count)
        assertEquals(Impulses.HIGH, impulses.kindOf(0))
        assertEquals(0f, impulses.ageOf(0))
        assertEquals(0.5f, impulses.ageOf(1), 1e-6f)
        assertEquals(-0.5f, impulses.xOf(1))
    }

    @Test
    fun theOldestFallsOutWhenFull() {
        val impulses = Impulses(capacity = 2)
        impulses.add(Impulses.LOW, 1f, 0f, 0f, 0f)
        impulses.add(Impulses.BODY, 1f, 0f, 0f, 0f)
        impulses.add(Impulses.HIGH, 1f, 0f, 0f, 0f)
        assertEquals(2, impulses.count)
        assertEquals(Impulses.HIGH, impulses.kindOf(0))
        assertEquals(Impulses.BODY, impulses.kindOf(1))
    }

    @Test
    fun theTexturePacksPositionStrengthAgeAndKind() {
        val impulses = Impulses(capacity = 4)
        impulses.add(Impulses.BODY, 0.5f, 2f, -2f, 0.25f)
        impulses.advance(4f)
        val a = impulses.rowA(0)
        val b = impulses.rowB(0)
        assertEquals(255, a shr 16 and 0xFF, "x of 2 packs to the right edge")
        assertEquals(0, a shr 8 and 0xFF, "y of -2 packs to the bottom")
        assertEquals(128, a and 0xFF, "strength half")
        assertEquals(128, b shr 16 and 0xFF, "age four of eight")
        assertEquals(85, b shr 8 and 0xFF, "body is kind one of three, a third of 255")
        assertTrue(impulses.rowB(1) and 0xFFFFFF == 0, "an empty slot is zero")
    }
}
