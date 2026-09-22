package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kiteplayer.audioviz.viz.VizCatalog
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.SmokeRise
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmokeRiseTest {
    init { useSkiaGraphics() }

    @Test
    fun smokeOwnsTheFluidInteractionInOneCatalogEntry() {
        val names = VizCatalog.create().map { it.name }
        assertEquals(1, names.count { it == "Smoke Rise" })
        assertFalse("Stable Fluids" in names, "Smoke Rise consumes the separate fluid preset")
    }

    @Test
    fun anEmptyFluidHasNoDrawnBlackHoleObjects() {
        val smoke = SmokeRise()
        val image = ImageBitmap(320, 180)
        val background = Color(0xFF305040)
        val state = VizRenderState(SpectrumFrame.silent(40, 64).withPulseHeld(),
            0f, 0f, VizPalette.Prism)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(320f, 180f)) {
            drawRect(background)
            with(smoke) { drawFront(state) }
        }
        val pixels = image.toPixelMap()
        for (y in 0 until 180) for (x in 0 until 320) {
            assertEquals(background, pixels[x, y], "Only the surrounding fluid may reveal the moving horizons")
        }
    }

    @Test
    fun twoBlackHolesAreOptionalAndChangeTheSmoke() {
        val smoke = SmokeRise()
        val directory = File("build/smoke-rise-preview").apply { mkdirs() }
        val active = RenderHarness.render(smoke, 640, 360, 180, VizPalette.Prism)
        ImageIO.write(active, "png", File(directory, "active.png"))
        val count = smoke.params.find { it.name == "Black holes" }
        assertTrue(count != null, "Smoke Rise must expose the merged interaction")
        assertEquals(2f, count.default)
        // restart() restores settings, so apply the override before each frame is integrated.
        var changed = 0
        RenderHarness.forEachFrame(smoke, 640, 360, 180, VizPalette.Prism,
            RenderHarness.Song.Lively, beforeDraw = { count.value = 0f }) { image, frame ->
            if (frame == 179) {
                val plain = with(RenderHarness) { image.toBufferedImage() }
                ImageIO.write(plain, "png", File(directory, "plain.png"))
                for (y in 0 until 360) for (x in 0 until 640) {
                    if (active.getRGB(x, y) != plain.getRGB(x, y)) changed++
                }
            }
        }
        assertTrue(changed > 640 * 360 / 10, "The holes must interact with the smoke")
    }

    @Test
    fun portraitSmokeRetainsItsPlumesAndResetsCleanly() {
        val smoke = SmokeRise()
        val first = RenderHarness.render(smoke, 240, 420, 90, VizPalette.Prism)
        val second = RenderHarness.render(smoke, 240, 420, 90, VizPalette.Prism)
        var visible = 0
        for (y in 80 until 300) for (x in 0 until 240) {
            val pixel = first.getRGB(x, y)
            if (maxOf((pixel shr 16) and 255, (pixel shr 8) and 255, pixel and 255) > 40) visible++
            assertEquals(pixel, second.getRGB(x, y), "Reset must clear smoke and orbital state")
        }
        assertTrue(visible > 240 * 220 / 5, "The forces must preserve the rising smoke")
        val directory = File("build/smoke-rise-preview").apply { mkdirs() }
        ImageIO.write(first, "png", File(directory, "portrait.png"))
    }

    @Test
    fun captureMusicWhenRequested() {
        val path = System.getenv("SMOKE_RISE_PCM")
        assumeTrue("Optional 48 kHz mono f32le music capture", path != null)
        val buffer = ByteBuffer.wrap(File(checkNotNull(path)).readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val samples = FloatArray(buffer.remaining()).also { buffer.get(it) }
        val seconds = minOf(12, samples.size / 48_000 - 3)
        assertTrue(seconds > 3)
        val player = SongPlayer(samples, bandCount = 40)
        val directory = File("build/smoke-rise-preview/music").apply { mkdirs() }
        directory.listFiles()?.filter { it.extension == "png" }?.forEach { it.delete() }
        RenderHarness.forEachFrameOf(SmokeRise(), 640, 360, seconds * 60, VizPalette.Prism,
            { player.next(1f / 60f) }, { player.future }) { image, frame ->
            if (frame % 2 == 1) ImageIO.write(with(RenderHarness) { image.toBufferedImage() },
                "png", File(directory, "%04d.png".format(frame / 2)))
        }
    }
}
