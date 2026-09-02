@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * The audio device's effect handle reaches the application.
 *
 * Android's audio session id is the number every `AudioEffect` attaches to, and until now it never
 * left the sink: an application wanting a loudness boost or a visualiser had to allocate a session
 * of its own, which is a session nothing plays on, so the effect attached and did nothing at all
 * without ever reporting a problem.
 *
 * The engine has no idea what a session id means, and that is the point: it copies whatever the
 * sink claims onto the snapshot every publish, so the field is live rather than cached, and a
 * platform with no such concept publishes null and costs nothing.
 */
class AudioSessionIdTest {

    @Test
    fun `the sink's session id reaches the snapshot`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 2_000_000), sinkSessionId = 4242)
        harness.openWithRenderer()
        harness.run(50.milliseconds)

        assertEquals(4242, harness.core.snapshots.value.audioSessionId)
        harness.close()
    }

    @Test
    fun `a platform with no session concept publishes null`() = runTest {
        // The default, and what CoreAudio, the desktop line and the web worklet all answer.
        val harness = CoreHarness(this, script = MediaScript(durationUs = 2_000_000))
        harness.openWithRenderer()
        harness.run(50.milliseconds)

        assertNull(harness.core.snapshots.value.audioSessionId)
        harness.close()
    }

    @Test
    fun `the session id is gone once the player is closed`() = runTest {
        // An effect attached to a released session is silently inert, so the field going back to
        // null is the signal an application needs to let its effect go.
        val harness = CoreHarness(this, script = MediaScript(durationUs = 2_000_000), sinkSessionId = 9)
        harness.openWithRenderer()
        harness.run(50.milliseconds)
        assertEquals(9, harness.core.snapshots.value.audioSessionId)

        harness.close()
        assertNull(
            harness.core.snapshots.value.audioSessionId,
            "a closed player still named the session its device used to hold",
        )
    }
}
