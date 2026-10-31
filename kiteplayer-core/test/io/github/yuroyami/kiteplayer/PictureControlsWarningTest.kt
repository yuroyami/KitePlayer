package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.CoreCommand
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Picture controls the attached renderer does not draw are warned about, once, rather than
 * dropped with nothing said (#610). The snapshot reported them while the picture never changed.
 */
class PictureControlsWarningTest {

    private fun CoreHarness.warned() =
        core.warningHistory().map { it.warning }.filterIsInstance<PlaybackWarning.PictureControlsUnavailable>()

    @Test
    fun aRendererThatDoesNotDrawTheControlsIsWarnedAboutOnce() = runTest {
        val harness = CoreHarness(this)
        harness.renderer!!.appliesAdjustmentsOverride = false
        harness.openWithRenderer()
        assertTrue(harness.warned().isEmpty(), "neutral controls were warned about")

        harness.core.post(CoreCommand.SetVideoAdjustments(VideoAdjustments(brightness = 0.3f), CompletableDeferred()))
        harness.run(50.milliseconds)
        harness.core.post(CoreCommand.SetVideoAdjustments(VideoAdjustments(brightness = 0.5f), CompletableDeferred()))
        harness.run(50.milliseconds)
        assertEquals(1, harness.warned().size, "the warning came ${harness.warned().size} times")
        assertEquals(VideoAdjustments(brightness = 0.5f), harness.core.snapshots.value.videoAdjustments)
        harness.close()
    }

    @Test
    fun aRendererThatDrawsTheControlsIsNotWarnedAbout() = runTest {
        val harness = CoreHarness(this)
        harness.openWithRenderer()
        harness.core.post(CoreCommand.SetVideoAdjustments(VideoAdjustments(contrast = 1.5f), CompletableDeferred()))
        harness.run(50.milliseconds)
        assertTrue(harness.warned().isEmpty())
        harness.close()
    }
}
