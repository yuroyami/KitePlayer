package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Choosing another variant of the media (#376): the item opens again on it at the position where
 * it was, and keeps playing, and the track table says which variant plays.
 */
class SelectVariantTest {

    private val variants = listOf(
        StreamVariant(index = 0, bitrate = 5_000_000, width = 1920, height = 1080),
        StreamVariant(index = 1, bitrate = 800_000, width = 640, height = 360),
    )

    @Test
    fun aVariantChangeReopensAtThePositionAndKeepsPlaying() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 30_000_000, variants = variants))
        harness.openWithRenderer()
        assertEquals(variants, harness.core.snapshots.value.tracks.variants)
        assertEquals(0, harness.core.snapshots.value.tracks.selectedVariant)
        harness.core.play()
        harness.run(3.seconds)
        val before = harness.core.position()
        val opensBefore = harness.backend.openCalls

        harness.core.selectVariant(1)
        harness.run(500.milliseconds)

        assertEquals(opensBefore + 1, harness.backend.openCalls, "the item opened again")
        assertEquals(1, harness.backend.lastOpenedItem?.demux?.variant, "on the chosen variant")
        assertEquals(1, harness.core.snapshots.value.tracks.selectedVariant)
        assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status)
        val after = harness.core.position()
        assertTrue(abs((after - before).inWholeMilliseconds) < 1_500, "it went on near $before, not from $after")
        harness.close()
    }

    @Test
    fun aStreamTooSlowForItsVariantStepsDownByItselfAndKeepsPlaying() = runTest {
        // The high variant reads far slower than real time, the low one keeps up.
        val script = MediaScript(durationUs = 60_000_000, variants = variants, readDelayUsByVariant = mapOf(0 to 200_000L))
        val harness = CoreHarness(this, script = script)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(15.seconds)

        val lowered = harness.core.warningHistory().map { it.warning }.filterIsInstance<PlaybackWarning.VariantLowered>()
        assertEquals(listOf(0 to 1), lowered.map { it.from to it.to }, "one step down: $lowered")
        assertEquals(1, harness.core.snapshots.value.tracks.selectedVariant)
        assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status)
        val before = harness.core.position()
        harness.run(2.seconds)
        assertTrue(harness.core.position() - before >= 1500.milliseconds, "the lower variant keeps up")
        harness.close()
    }

    @Test
    fun aVariantTheCallerChoseIsNotLowered() = runTest {
        val script = MediaScript(durationUs = 60_000_000, variants = variants, readDelayUsByVariant = mapOf(0 to 200_000L))
        val harness = CoreHarness(this, script = script)
        harness.openWithRenderer()
        harness.core.selectVariant(0)
        harness.core.play()
        harness.run(15.seconds)
        assertTrue(
            harness.core.warningHistory().none { it.warning is PlaybackWarning.VariantLowered },
            "a chosen variant stays",
        )
        assertEquals(0, harness.core.snapshots.value.tracks.selectedVariant)
        harness.close()
    }

    @Test
    fun aVariantTheMediaDoesNotHaveIsRefused() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 30_000_000, variants = variants))
        harness.openWithRenderer()
        assertFailsWith<IllegalArgumentException> { harness.core.selectVariant(7) }
        val plain = CoreHarness(this, script = MediaScript(durationUs = 30_000_000))
        plain.openWithRenderer()
        assertTrue(plain.core.snapshots.value.tracks.variants.isEmpty(), "media with one version lists no variants")
        assertFailsWith<IllegalArgumentException> { plain.core.selectVariant(0) }
        harness.close()
        plain.close()
    }
}
