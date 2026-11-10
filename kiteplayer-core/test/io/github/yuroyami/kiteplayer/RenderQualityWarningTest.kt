package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.CoreCommand
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * A render quality the attached renderer cannot apply is warned about, once, rather than dropped
 * with nothing said (#769). The snapshot reported it while the picture never changed.
 */
class RenderQualityWarningTest {

    private fun CoreHarness.warned() =
        core.warningHistory().map { it.warning }.filterIsInstance<PlaybackWarning.RenderQualityUnavailable>()

    @Test
    fun aRendererWithNoShaderIsWarnedAboutOnce() = runTest {
        val harness = CoreHarness(this)
        harness.renderer!!.appliesRenderQualityOverride = false
        harness.openWithRenderer()
        assertTrue(harness.warned().isEmpty(), "the neutral quality was warned about")

        harness.core.post(CoreCommand.SetRenderQuality(RenderQuality.Standard, CompletableDeferred()))
        harness.run(50.milliseconds)
        harness.core.post(CoreCommand.SetRenderQuality(RenderQuality(dither = true), CompletableDeferred()))
        harness.run(50.milliseconds)
        assertEquals(1, harness.warned().size, "the warning came ${harness.warned().size} times")
        assertEquals(RenderQuality(dither = true), harness.core.snapshots.value.renderQuality)
        harness.close()
    }

    @Test
    fun aRendererThatAppliesTheQualityIsNotWarnedAbout() = runTest {
        val harness = CoreHarness(this)
        harness.openWithRenderer()
        harness.core.post(CoreCommand.SetRenderQuality(RenderQuality.Standard, CompletableDeferred()))
        harness.run(50.milliseconds)
        assertTrue(harness.warned().isEmpty())
        harness.close()
    }
}
