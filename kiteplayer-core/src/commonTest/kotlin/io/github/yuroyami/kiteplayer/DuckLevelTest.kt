package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.CoreCommand
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** A duck multiplies what is heard and leaves the published volume alone (#280). */
class DuckLevelTest {

    private suspend fun peakHeard(harness: CoreHarness): Float {
        harness.sink.audibleValues.clear()
        harness.run(400.milliseconds)
        return harness.sink.audibleValues.maxOfOrNull { abs(it) } ?: 0f
    }

    @Test
    fun aDuckLowersWhatIsHeardAndNotTheVolume() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 4_000_000))
        harness.openWithRenderer()
        harness.core.post(CoreCommand.SetVolume(0.5f, CompletableDeferred()))
        harness.core.play()
        harness.run(300.milliseconds)
        val undecked = peakHeard(harness)
        assertTrue(undecked > 0f, "nothing was heard, so this proves nothing")

        harness.core.post(CoreCommand.SetDuckLevel(0.2f, CompletableDeferred()))
        harness.run(300.milliseconds)
        val ducked = peakHeard(harness)
        assertEquals(0.2f, ducked / undecked, 0.02f, "the duck is a factor on the heard level")
        assertEquals(0.5f, harness.core.snapshots.value.volume, "the duck wrote the published volume")

        harness.core.post(CoreCommand.SetDuckLevel(1f, CompletableDeferred()))
        harness.run(300.milliseconds)
        assertEquals(1f, peakHeard(harness) / undecked, 0.02f, "lifting the duck brought the level back")
        harness.close()
    }
}
