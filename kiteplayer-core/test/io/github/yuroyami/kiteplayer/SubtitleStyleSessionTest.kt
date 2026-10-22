@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.CoreCommand
import io.github.yuroyami.kiteplayer.subtitle.StyledSpan
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import io.github.yuroyami.kiteplayer.subtitle.SubtitleStyleOverride
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The live half of the style override: a change re-rasterises the text that is already showing,
 * without waiting for a cue edge, and what reaches the rasteriser carries the override.
 */
class SubtitleStyleSessionTest {

    private val script = MediaScript(
        durationUs = 6_000_000,
        subtitleCues = listOf(
            SubtitleCue.Text(200_000, 5_500_000, listOf(StyledSpan("long line"))),
        ),
    )

    private suspend fun CoreHarness.setStyle(override: SubtitleStyleOverride?) {
        val reply = CompletableDeferred<Unit>()
        core.post(CoreCommand.SetSubtitleStyle(override, reply))
        run(100.milliseconds)
    }

    @Test
    fun `a live style change re-rasterises the showing cue with the override applied`() = runTest {
        val harness = CoreHarness(this, script = script)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(500.milliseconds)
        val rasterCallsBefore = harness.output.rasterizedCueStyles.size
        assertTrue(rasterCallsBefore > 0, "the scripted cue never reached the rasteriser at all")

        harness.setStyle(SubtitleStyleOverride(primaryColor = 0xFF123456.toInt()))
        harness.run(300.milliseconds)

        assertTrue(
            harness.output.rasterizedCueStyles.size > rasterCallsBefore,
            "the style change must re-rasterise without waiting for a cue edge",
        )
        val styles = harness.output.rasterizedCueStyles.last()
        assertTrue(styles.isNotEmpty() && styles.all { it.primaryColor == 0xFF123456.toInt() })
        assertEquals(
            SubtitleStyleOverride(primaryColor = 0xFF123456.toInt()),
            harness.core.snapshots.value.subtitleStyle,
            "the snapshot must carry the override so an application can show its settings screen",
        )

        harness.setStyle(null)
        harness.run(300.milliseconds)
        val cleared = harness.output.rasterizedCueStyles.last()
        assertTrue(
            cleared.none { it.primaryColor == 0xFF123456.toInt() },
            "clearing the override must put the authored style back on screen",
        )
        harness.close()
    }
}
