package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/** Actual picker composition, saved for bounded phone and desktop layout inspection. */
class FlatBrowserTest {
    init { useSkiaGraphics() }

    @Test fun flatPickerComposesAtPhoneAndDesktopSizes() {
        val directory = File("build/flat-browser").apply { mkdirs() }
        for ((width, height) in listOf(360 to 800, 960 to 720)) {
            val state = AudioVizState(clock = { VizClockReading(0L) })
            state.framesPerSecond = 60
            val scene = ImageComposeScene(width, height, Density(1f), content = {
                AudioVizBrowser(state, Modifier.fillMaxSize())
            })
            try {
                scene.render(0L).close()
                scene.render(100_000_000L).use { image ->
                    val pixels = with(RenderHarness) { image.toComposeImageBitmap().toBufferedImage() }
                    ImageIO.write(pixels, "png", File(directory, "picker-${width}x$height.png"))
                }
            } finally {
                scene.close()
            }
        }
    }
}
