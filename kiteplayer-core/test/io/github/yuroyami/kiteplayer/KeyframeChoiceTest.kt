package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.SeekResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A keyframe seek lands on the keyframe its [KeyframeChoice] names (#496): in a file with a
 * keyframe every 10 s, a seek from 12 s to 17 s lands at 10 s before the target, and at 20 s after
 * it, nearest to it, or in the seek's direction.
 */
class KeyframeChoiceTest {

    private val script = MediaScript(durationUs = 40_000_000, keyframeIntervalUs = 10_000_000)

    private suspend fun TestScope.openAt(atUs: Long, choice: KeyframeChoice = KeyframeChoice.Before): CoreHarness {
        val harness = CoreHarness(this, script = script, config = PlayerConfig(keyframeChoice = choice))
        harness.openWithRenderer()
        assertIs<SeekResult.Applied>(harness.core.seek(Pts(atUs), SeekMode.Precise))
        assertEquals(atUs, harness.core.position().inWholeMicroseconds, "the precise seek to the start point")
        return harness
    }

    private suspend fun CoreHarness.keyframeSeek(toUs: Long): Long {
        val result = core.seek(Pts(toUs), SeekMode.Keyframe)
        return assertIs<SeekResult.Applied>(result).landedAt.micros
    }

    @Test
    fun beforeIsTheDefaultAndLandsOnTheKeyframeBeforeTheTarget() = runTest {
        val harness = CoreHarness(this, script = script)
        harness.openWithRenderer()
        harness.core.seek(Pts(12_000_000), SeekMode.Precise)
        assertEquals(10_000_000, harness.keyframeSeek(17_000_000))
        assertEquals(KeyframeChoice.Before, harness.source.seekChoices.last())
        harness.close()
    }

    @Test
    fun afterLandsOnTheKeyframeAfterTheTarget() = runTest {
        val harness = openAt(12_000_000, KeyframeChoice.After)
        assertEquals(20_000_000, harness.keyframeSeek(17_000_000))
        assertEquals(20_000_000, harness.core.position().inWholeMicroseconds)
        harness.close()
    }

    @Test
    fun closestLandsOnTheNearerKeyframe() = runTest {
        val harness = openAt(12_000_000, KeyframeChoice.Closest)
        assertEquals(20_000_000, harness.keyframeSeek(17_000_000))
        assertEquals(10_000_000, harness.keyframeSeek(13_000_000))
        harness.close()
    }

    @Test
    fun theSeekDirectionRuleGoesForwardFromBehindAndBackwardFromAhead() = runTest {
        val harness = openAt(12_000_000, KeyframeChoice.InSeekDirection)
        assertEquals(20_000_000, harness.keyframeSeek(17_000_000))
        assertEquals(KeyframeChoice.After, harness.source.seekChoices.last())

        assertIs<SeekResult.Applied>(harness.core.seek(Pts(30_000_000), SeekMode.Precise))
        assertEquals(10_000_000, harness.keyframeSeek(17_000_000))
        assertEquals(KeyframeChoice.Before, harness.source.seekChoices.last())
        harness.close()
    }

    @Test
    fun withNoKeyframeAfterTheTargetTheSeekTakesTheOneBefore() = runTest {
        val harness = openAt(12_000_000, KeyframeChoice.After)
        assertEquals(30_000_000, harness.keyframeSeek(35_000_000))
        harness.close()
    }

    @Test
    fun theChoiceChangesLiveForTheSeeksAskedForAfterIt() = runTest {
        val harness = openAt(12_000_000)
        assertEquals(10_000_000, harness.keyframeSeek(17_000_000))
        harness.core.setKeyframeChoice(KeyframeChoice.After)
        assertEquals(KeyframeChoice.After, harness.core.currentKeyframeChoice)
        assertEquals(20_000_000, harness.keyframeSeek(17_000_000))
        harness.close()
    }

    @Test
    fun thePreciseModesKeepTheKeyframeBeforeTheTarget() = runTest {
        val harness = openAt(12_000_000, KeyframeChoice.After)
        val seeksBefore = harness.source.seekChoices.size
        assertIs<SeekResult.Applied>(harness.core.seek(Pts(17_000_000), SeekMode.KeyframeThenRefine))
        assertEquals(17_000_000, harness.core.position().inWholeMicroseconds)
        assertEquals(
            List(harness.source.seekChoices.size - seeksBefore) { KeyframeChoice.Before },
            harness.source.seekChoices.drop(seeksBefore),
        )
        harness.close()
    }
}
