@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What a renderer swap does when the video scheduler will not park.
 *
 * Swapping a renderer means handing the scheduler a different surface to draw into. Doing that
 * while the scheduler is still inside `present` on the old one is a use-after-free waiting to
 * happen, so the swap first parks the scheduler and refuses if it cannot. A display stuck in
 * `present` for longer than the deadline is exactly that case, and it is not hypothetical: a
 * surface being torn down by the window system is where it comes from.
 *
 * The refusal has to do four things at once, and each is easy to lose on its own: refuse loudly,
 * leave the picture on the renderer that still works, never touch the one it refused, and close
 * every frame in flight while it happens.
 */
class RendererSwapRefusalTest {

    @Test
    fun `a scheduler that will not park refuses the swap and keeps the old renderer drawing`() = runTest {
        // Six seconds per frame, against a two second quiesce deadline. Under the test clock the
        // wait is virtual, so this costs nothing and is exact rather than racy.
        val attached = RecordingRenderer(presentDuration = 6.seconds)
        val harness = CoreHarness(
            this,
            script = MediaScript(durationUs = 30_000_000),
            renderer = attached,
        )
        harness.openWithRenderer()
        harness.core.play()
        harness.run(300.milliseconds)

        val replacement = RecordingRenderer()
        val refusal = runCatching { harness.core.attachRenderer(replacement) }.exceptionOrNull()

        assertTrue(
            refusal is IllegalStateException,
            "a swap the scheduler cannot park must fail explicitly, got $refusal",
        )
        assertTrue(
            refusal.message?.contains("quiesce") == true,
            "the refusal must say why, said: ${refusal.message}",
        )
        // Thrown AND warned, because the facade has a fire-and-forget attach that discards the
        // reply. Without the warning a refused swap is a permanently black surface with no trace.
        assertTrue(
            harness.core.warningHistory().any {
                val warning = it.warning
                warning is PlaybackWarning.CommandRefused && warning.member == "attachRenderer"
            },
            "a refused swap must reach the warning history, history: " +
                harness.core.warningHistory().map { it.warning::class.simpleName }.toString(),
        )

        val drawnBefore = attached.count
        harness.run(20.seconds)

        assertEquals(
            0,
            replacement.count,
            "the refused renderer must never be drawn into: it was never attached, and the engine " +
                "handing it frames anyway is the use-after-free the parking exists to prevent",
        )
        // The fallback, stated as the thing a viewer would notice: the picture keeps coming. A
        // refusal that left no renderer attached would be a black screen and would also pass every
        // assertion above.
        assertTrue(
            attached.count > drawnBefore,
            "the renderer that was already attached must keep drawing after the refusal, " +
                "stuck at $drawnBefore",
        )

        harness.close()
        assertEquals(0, harness.ledger.liveCount, "the refusal must not strand a frame or packet")
        assertEquals(0, harness.ledger.doubleCloseCount, "and must not close one twice")
    }
}
