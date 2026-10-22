package io.github.yuroyami.kiteplayer.output

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuroyami.kiteplayer.subtitle.CueAlignment
import io.github.yuroyami.kiteplayer.subtitle.CueDisplayAlign
import io.github.yuroyami.kiteplayer.subtitle.CueInsets
import io.github.yuroyami.kiteplayer.subtitle.CueLayout
import io.github.yuroyami.kiteplayer.subtitle.CueRegion
import io.github.yuroyami.kiteplayer.subtitle.CueStyle
import io.github.yuroyami.kiteplayer.subtitle.StyledSpan
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two TTML paragraphs in one region, drawn by the Android rasterizer (#492). The drawing classes
 * are stubs on the host, so only a device or an emulator can run this.
 */
@RunWith(AndroidJUnit4::class)
class SubtitleRegionDeviceTest {

    @Test
    fun paragraphsOfARegionStackInsideItWithRealText() {
        val region = CueRegion(
            "low", left = 0.1f, top = 0.6f, width = 0.8f, height = 0.35f,
            padding = CueInsets(left = 0.05f, top = 0.05f, right = 0.05f, bottom = 0.05f),
            displayAlign = CueDisplayAlign.After,
            backgroundColor = 0xFF202020.toInt(),
        )
        fun para(text: String, order: Int) = SubtitleCue.Text(
            0, 1_000_000, listOf(StyledSpan(text, CueStyle())),
            CueLayout(alignment = CueAlignment.TopCenter, region = region, regionOrder = order),
        )
        fun draw(vararg cues: SubtitleCue) = AndroidSubtitleRasterizer().rasterize(cues.toList(), 640, 360, 1f)
        val images = draw(para("The second paragraph", 1), para("The first paragraph", 0))
        assertEquals(3, images.size, "the background and two paragraphs")
        val (box, first, second) = images
        assertEquals(listOf(64, 216, 512, 126), listOf(box.x, box.y, box.bitmap.width, box.bitmap.height), "the region's box")
        assertTrue(first.y + first.bitmap.height <= second.y, "the paragraphs overlap: ${first.y}+${first.bitmap.height} > ${second.y}")
        assertEquals(second.y + second.bitmap.height, 216 + 126 - 6, "the block sits on the padded bottom")
        assertTrue(first.x > 64 + 25 && first.x + first.bitmap.width < 64 + 512 - 25, "inside the padding: ${first.x}")
        // A paragraph wider than the region breaks at its inner width, 512 less two pads of 25.6.
        val long = draw(para("word ".repeat(40).trim(), 0)).last()
        assertTrue(long.bitmap.width <= 461 + 3, "broke at ${long.bitmap.width}")
        assertTrue(long.bitmap.height > first.bitmap.height * 2, "into several lines")
    }
}
