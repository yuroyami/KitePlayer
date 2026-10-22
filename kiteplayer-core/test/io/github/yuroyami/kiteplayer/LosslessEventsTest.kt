package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A collector of the lossless feed gets every event however far a collector of the bounded one has
 * fallen behind (#414), which is what the Java listener rides.
 */
class LosslessEventsTest {

    @Test
    fun aStalledCollectorOfEventsCostsTheLosslessCollectorNothing() = runTest {
        val harness = CoreHarness(this)
        val player = KitePlayer(harness.core)
        val lossless = mutableListOf<String>()
        val bounded = mutableListOf<String>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            player.losslessEvents.collect { lossless += (it as PlayerEvent.Warning).detail() }
        }
        // Stalls on its first event and holds the shared buffer from then on.
        val release = CompletableDeferred<Unit>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            player.events.collect {
                bounded += (it as PlayerEvent.Warning).detail()
                if (bounded.size == 1) release.await()
            }
        }

        for (i in 0..80) {
            harness.core.reportWarning(PlaybackWarning.AudioDeviceChanged("$i"))
            runCurrent()
        }
        assertEquals((0..80).map { "$it" }, lossless, "the lossless collector missed events")

        // The bounded feed did drop, which is what makes this a test of the lossless one.
        release.complete(Unit)
        runCurrent()
        assertTrue(bounded.size < 81, "the stalled collector of events lost nothing, so nothing was tested")
        harness.close()
    }

    @Test
    fun aCollectorThatStopsIsForgotten() = runTest {
        val harness = CoreHarness(this)
        val player = KitePlayer(harness.core)
        val seen = mutableListOf<String>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            player.losslessEvents.collect { seen += (it as PlayerEvent.Warning).detail() }
        }
        harness.core.reportWarning(PlaybackWarning.AudioDeviceChanged("before"))
        runCurrent()
        job.cancel()
        runCurrent()
        harness.core.reportWarning(PlaybackWarning.AudioDeviceChanged("after"))
        runCurrent()
        assertEquals(listOf("before"), seen)
        harness.close()
    }

    private fun PlayerEvent.Warning.detail(): String = (warning as PlaybackWarning.AudioDeviceChanged).detail
}
