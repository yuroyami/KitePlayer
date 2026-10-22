@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.spi.StreamDivergence
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Telling a viewer when a file's own header contradicts its pictures.
 *
 * The media library compared the two on every open and the player read none of it. That
 * disagreement is not an abstraction: it is what somebody meets as a wrong-sized picture, or as an
 * audio device opened for a rate nothing feeds, with nothing anywhere to say why.
 */
class ContainerDivergenceTest {

    private fun script(vararg divergences: StreamDivergence) = MediaScript(
        durationUs = 5_000_000,
        streamDivergences = divergences.toList(),
    )

    @Test
    fun `a container that declares one size and decodes another says so`() = runTest {
        val harness = CoreHarness(
            this,
            script = script(StreamDivergence(0, "Width", "1920", "1440")),
        )
        harness.openWithRenderer()
        harness.run(100.milliseconds)

        val told = harness.core.warningHistory()
            .map { it.warning }
            .filterIsInstance<PlaybackWarning.ContainerDeclarationDiverged>()
        assertEquals(1, told.size, "expected exactly one report, got ${harness.core.warningHistory()}")
        assertEquals("Width", told.first().field)
        assertEquals("1920", told.first().declared)
        assertEquals("1440", told.first().decoded)
        harness.close()
    }

    @Test
    fun `every disagreeing field is named`() = runTest {
        val harness = CoreHarness(
            this,
            script = script(
                StreamDivergence(0, "Width", "1920", "1440"),
                StreamDivergence(1, "SampleRate", "48000", "44100"),
            ),
        )
        harness.openWithRenderer()
        harness.run(100.milliseconds)

        val fields = harness.core.warningHistory()
            .map { it.warning }
            .filterIsInstance<PlaybackWarning.ContainerDeclarationDiverged>()
            .map { it.field }
        assertEquals(listOf("Width", "SampleRate"), fields)
        harness.close()
    }

    @Test
    fun `a file whose header agrees with its pictures says nothing`() = runTest {
        val harness = CoreHarness(this, script = script())
        harness.openWithRenderer()
        harness.run(100.milliseconds)
        assertTrue(
            harness.core.warningHistory()
                .none { it.warning is PlaybackWarning.ContainerDeclarationDiverged },
            "an ordinary file was reported as disagreeing with itself",
        )
        harness.close()
    }

    @Test
    fun `the same disagreement is told once and not again on every seek`() = runTest {
        val harness = CoreHarness(
            this,
            script = script(StreamDivergence(0, "Width", "1920", "1440")),
        )
        harness.openWithRenderer()
        harness.run(100.milliseconds)
        harness.core.seek(Pts(2_000_000), SeekMode.Precise)
        harness.run(200.milliseconds)

        val told = harness.core.warningHistory()
            .map { it.warning }
            .filterIsInstance<PlaybackWarning.ContainerDeclarationDiverged>()
        assertEquals(1, told.size, "a seek repeated the report: $told")
        harness.close()
    }
}
