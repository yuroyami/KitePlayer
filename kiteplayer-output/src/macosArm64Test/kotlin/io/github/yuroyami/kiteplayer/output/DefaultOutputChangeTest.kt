package io.github.yuroyami.kiteplayer.output

import kotlin.test.Test
import kotlin.test.assertEquals

/** What an unbound sink tells the application when the system default output changes. */
class DefaultOutputChangeTest {

    @Test
    fun aNewDefaultIsNamedAndFollowed() {
        assertEquals(
            "the default output changed to Speakers, and playback follows it",
            defaultOutputChange(42u) { "Speakers" },
        )
        assertEquals(
            "the default output changed to device 42, and playback follows it",
            defaultOutputChange(42u) { null },
        )
    }

    @Test
    fun noDefaultLeftIsNotCalledADeviceToFollow() {
        // Device 0 is CoreAudio's unknown object. The old text read "changed to device 0".
        assertEquals(
            "the system has no default output now, so playback is silent until one appears",
            defaultOutputChange(0u) { "never asked" },
        )
    }
}
