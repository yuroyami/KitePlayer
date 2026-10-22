package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import io.github.yuroyami.kiteplayer.audioviz.viz.DirectedVisualizerSurface
import io.github.yuroyami.kiteplayer.audioviz.viz.VisualizerSurface
import io.github.yuroyami.kiteplayer.audioviz.viz.Visualization
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDirector
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A surface's own clock, driven the way a fast screen drives it: a display frame every 8.3 ms, with
 * the analysis changing on every one of them and the surface capped at 30 frames a second.
 */
class SurfaceClockTest {
    init { useSkiaGraphics() }

    /** Notes what every draw was given. */
    private class Recorder(override val name: String, private val echo: Float = 0f) : Visualization {
        override val trail: Float get() = echo
        var steps = 0
        var passed = 0f
        var musicTime = 0f

        override fun DrawScope.draw(state: VizRenderState) {
            if (state.deltaSeconds > 0f) steps++
            passed += state.deltaSeconds
            musicTime = state.musicTime
        }
    }

    /** A scene rendered at 120 display frames a second. */
    private class Screen(content: @androidx.compose.runtime.Composable () -> Unit) {
        private val scene = ImageComposeScene(64, 40, Density(1f), content = content)
        private var nanos = 0L

        fun play(seconds: Float, beforeEach: () -> Unit = {}) {
            repeat((seconds * DISPLAY_RATE).toInt()) {
                beforeEach()
                nanos += 1_000_000_000L / DISPLAY_RATE
                scene.render(nanos).close()
            }
        }

        fun close() = scene.close()
    }

    @Test
    fun aCappedSurfaceStepsAtItsCapWhileTheAnalysisChangesEveryDisplayFrame() {
        val wrong = ArrayList<String>()
        for (directed in listOf(false, true)) for (post in listOf(false, true)) for (echo in listOf(0f, 0.5f)) {
            val player = SongPlayer(SyntheticSong.drumLoop(8f))
            val frame = mutableStateOf(player.latest)
            val drawings = listOf(Recorder("one", echo), Recorder("two", echo))
            // A long hold, so the director keeps its first drawing for the whole second.
            val director = VizDirector(drawings, seed = 3L, minimumHoldSeconds = 1_000f)
            val screen = Screen {
                if (directed) {
                    DirectedVisualizerSurface(director, { frame.value }, VizPalette.Prism, Modifier.fillMaxSize(), post = post, framesPerSecond = 30)
                } else {
                    VisualizerSurface(drawings[0], { frame.value }, VizPalette.Prism, Modifier.fillMaxSize(), post = post, framesPerSecond = 30)
                }
            }
            try {
                screen.play(1f) { frame.value = player.next(1f / DISPLAY_RATE) }
            } finally {
                screen.close()
            }
            val route = "${if (directed) "directed" else "plain"}, finishing pass ${if (post) "on" else "off"}, trail $echo"
            val steps = drawings.sumOf { it.steps }
            val passed = drawings.sumOf { it.passed.toDouble() }
            // The first display frame starts the clock, so a second of them holds 29 or 30 steps.
            if (steps !in 28..31 || passed !in 0.9..1.02) wrong += "$route: $steps steps and $passed seconds"
        }
        assertTrue(wrong.isEmpty(), "in one second capped at 30 frames: ${wrong.joinToString("; ")}")
    }

    @Test
    fun aReplacedDirectorIsTheOneThatMovesOn() {
        val first = changingDirector("a")
        val second = changingDirector("b")
        val director = mutableStateOf(first)
        val silence = SpectrumFrame.silent(48, 64)
        val screen = Screen {
            DirectedVisualizerSurface(director.value, { silence }, VizPalette.Prism, Modifier.fillMaxSize(), post = false, framesPerSecond = 30)
        }
        try {
            screen.play(0.25f)
            val firstShowing = first.current
            val secondShowing = second.current
            director.value = second
            screen.play(1f)
            // Half a second with no boundary starts a change, so only the director being moved on starts one.
            assertFalse(first.changing, "the replaced director still moved on")
            assertSame(firstShowing, first.current, "the replaced director changed its drawing")
            assertTrue(second.changing || second.current !== secondShowing, "the new director never moved on")
        } finally {
            screen.close()
        }
    }

    @Test
    fun aDirectorReplacedDuringAChangeStopsWhereItWas() {
        val first = changingDirector("a")
        val second = changingDirector("b")
        val director = mutableStateOf(first)
        val silence = SpectrumFrame.silent(48, 64)
        val screen = Screen {
            DirectedVisualizerSurface(director.value, { silence }, VizPalette.Prism, Modifier.fillMaxSize(), post = true, framesPerSecond = 30)
        }
        try {
            screen.play(0.75f)
            assertTrue(first.changing, "the first director should be part way through a change")
            val progress = first.progress
            val secondShowing = second.current
            director.value = second
            screen.play(1f)
            assertEquals(progress, first.progress, "the replaced director's change carried on")
            assertTrue(second.changing || second.current !== secondShowing, "the new director never moved on")
        } finally {
            screen.close()
        }
    }

    @Test
    fun aReplacedAnalysisSourceDrivesTheMusicClock() {
        for (directed in listOf(false, true)) {
            val silence = SpectrumFrame.silent(48, 64)
            val player = SongPlayer(SyntheticSong.drumLoop(8f))
            var loud = player.latest
            val source = mutableStateOf<() -> SpectrumFrame>({ silence })
            val drawings = listOf(Recorder("one"), Recorder("two"))
            val director = VizDirector(drawings, seed = 3L, minimumHoldSeconds = 1_000f)
            val screen = Screen {
                if (directed) {
                    DirectedVisualizerSurface(director, source.value, VizPalette.Prism, Modifier.fillMaxSize(), post = false, framesPerSecond = 30)
                } else {
                    VisualizerSurface(drawings[0], source.value, VizPalette.Prism, Modifier.fillMaxSize(), post = false, framesPerSecond = 30)
                }
            }
            val route = if (directed) "directed" else "plain"
            try {
                screen.play(0.5f)
                assertEquals(0f, drawings.maxOf { it.musicTime }, "$route: music time moved in silence")
                source.value = { loud }
                screen.play(0.5f) { loud = player.next(1f / DISPLAY_RATE) }
                val music = drawings.maxOf { it.musicTime }
                assertTrue(music > 0.01f, "$route: music time stayed at $music after the source changed to music")
            } finally {
                screen.close()
            }
        }
    }

    /** A director that starts a change after half a second, with no boundary needed. */
    private fun changingDirector(prefix: String): VizDirector =
        VizDirector(listOf(Recorder("$prefix one"), Recorder("$prefix two")), seed = 5L, minimumHoldSeconds = 0f)
            .apply { maximumHoldSeconds = 0.5f }

    private companion object {
        const val DISPLAY_RATE = 120
    }
}
