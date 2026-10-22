package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kiteplayer.audioviz.viz.*
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.NeonLoFi
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import org.junit.Assume.assumeTrue
import kotlin.test.*

/** 48 kHz f32 PCM, real analyzer and delivered records. No guessed beats or genre labels. */
class NeonLoFiCaptureTest {
    init { useSkiaGraphics() }
    @Test fun captureRequestedRealMusicWithTravelStoppedAndMoving() {
        val path = System.getenv("NEON_PCM_DIRECTORY")
        assumeTrue("Optional real song capture", path != null)
        val songs = (System.getenv("NEON_SONGS") ?: "bad-cat,helios,hurry-up-2021").split(',')
        for (song in songs) {
            val buffer = ByteBuffer.wrap(File(checkNotNull(path), "$song.f32").readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val samples = FloatArray(buffer.remaining()).also { buffer.get(it) }
            assertTrue(samples.size >= 16 * 48000)
            for (moving in listOf(false, true)) {
                val directory = File("build/neonlofi-music/$song-${if (moving) "drive" else "fixed"}").apply { mkdirs() }
                val player = SongPlayer(samples)
                val viz = NeonLoFi().apply { params[6].value = if (moving) 1f else 0f; params[0].value = 1f }
                assertTrue(viz.runs, viz.compileError)
                val image = ImageBitmap(320, 568)
                val scope = CanvasDrawScope()
                File(directory, "features.csv").bufferedWriter().use { log ->
                    log.appendLine("frame,mediaMicros,energy,density,bass,mid,high,events,accepted,totalCompounds,travel,laneMin,laneMax,historyRows")
                    for (frame in 0 until 180) {
                        val audio = player.next(1f / 15)
                        val state = VizRenderState(audio, (frame + 1f) / 15, 1f / 15, VizPalette.Sunset, future = player.future)
                        if (!moving) state.motionScale = 0f
                        scope.draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(320f, 568f)) {
                            drawComposedFrame(viz, state, null)
                        }
                        ImageIO.write(with(RenderHarness) { image.toBufferedImage() }, "png", File(directory, "%04d.png".format(frame)))
                        log.appendLine("$frame,${audio.ptsMicros},${audio.energy},${audio.density},${audio.bass},${audio.mid},${audio.treble},${audio.events?.size ?: 0},${viz.world.eventCount},${viz.world.compoundCount},${viz.world.flight.travel},${viz.world.lanes.min()},${viz.world.lanes.max()},${viz.world.history.count}")
                    }
                }
                if (!moving) assertEquals(0.0, viz.world.flight.travel)
                assertTrue(viz.world.history.count > 35, "Capture must have presented timestamped music")
                assertTrue(viz.world.lanes.max() > 0.05f)
            }
        }
    }
}
