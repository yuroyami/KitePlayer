@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * "Tell me when we pass this position." Sync, ad cue points, lyric lines and skip-intro all want
 * it, and all of them want the same rule: a marker fires when playback CROSSES it, never because a
 * seek landed on the far side of it, and it fires again on the next pass.
 */
class MarkersTest {

    private fun harness(scope: TestScope) = CoreHarness(
        scope,
        script = MediaScript(durationUs = 10_000_000),
    )

    private fun CoreHarness.reached(): List<String> =
        events.filterIsInstance<PlayerEvent.MarkerReached>().map { it.marker.id }

    @Test
    fun `markers fire in order as playback crosses them`() = runTest {
        val harness = harness(this)
        harness.openWithRenderer()
        // Handed over out of order on purpose: the engine sorts them.
        harness.core.setMarkers(listOf(Marker(2.seconds, "two"), Marker(1.seconds, "one")))
        harness.run(50.milliseconds)
        assertEquals(
            listOf("one", "two"),
            harness.core.snapshots.value.markers.map { it.id },
            "the snapshot carries the markers sorted by position",
        )

        harness.core.play()
        harness.run(3.seconds)

        assertEquals(listOf("one", "two"), harness.reached(), "each marker once, in position order")
        harness.close()
    }

    @Test
    fun `a seek past a marker does not fire it and a seek back re-arms it`() = runTest {
        val harness = harness(this)
        harness.openWithRenderer()
        harness.core.setMarkers(listOf(Marker(1.seconds, "one"), Marker(2.seconds, "two")))
        harness.run(50.milliseconds)

        harness.core.seek(Pts(5_000_000), SeekMode.Precise)
        harness.run(200.milliseconds)
        harness.core.play()
        harness.run(1.seconds)
        assertEquals(emptyList(), harness.reached(), "landing past both markers must announce neither")

        harness.core.seek(Pts(0), SeekMode.Precise)
        harness.run(200.milliseconds)
        harness.core.play()
        harness.run(3.seconds)
        assertEquals(listOf("one", "two"), harness.reached(), "the pass after a backward seek crosses them again")
        harness.close()
    }

    @Test
    fun `an empty list clears the markers`() = runTest {
        val harness = harness(this)
        harness.openWithRenderer()
        harness.core.setMarkers(listOf(Marker(1.seconds, "one")))
        harness.run(50.milliseconds)
        harness.core.setMarkers(emptyList())
        harness.run(50.milliseconds)
        assertEquals(emptyList(), harness.core.snapshots.value.markers)

        harness.core.play()
        harness.run(3.seconds)
        assertEquals(emptyList(), harness.reached(), "a cleared marker must not fire")
        harness.close()
    }
}
