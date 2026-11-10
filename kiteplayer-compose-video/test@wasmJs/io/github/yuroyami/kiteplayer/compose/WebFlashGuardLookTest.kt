package io.github.yuroyami.kiteplayer.compose

import io.github.yuroyami.kiteplayer.FlashGuard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Turning the flash guard off redraws a paused picture held dim at full light (#737). */
class WebFlashGuardLookTest {

    @Test
    fun theLookCarriesTheGuardAndAChangeRedraws() {
        val renderer = WebKiteVideoRenderer(WebFramePaint(paint = { _, _ -> false }))
        try {
            assertFalse(renderer.look.guarded, "a page has no system setting to follow, so the guard starts off")
            val before = renderer.changes.value
            renderer.setFlashGuard(FlashGuard.On)
            assertTrue(renderer.look.guarded)
            assertTrue(renderer.changes.value > before, "turning it on draws again")

            val on = renderer.changes.value
            renderer.setFlashGuard(FlashGuard.Off)
            assertFalse(renderer.look.guarded, "a held picture's factor is no longer used")
            assertTrue(renderer.changes.value > on, "and the paused picture is drawn again at once")

            val off = renderer.changes.value
            renderer.setFlashGuard(FlashGuard.Off)
            assertEquals(off, renderer.changes.value, "the same mode again changes nothing")
        } finally {
            renderer.close()
        }
    }
}
