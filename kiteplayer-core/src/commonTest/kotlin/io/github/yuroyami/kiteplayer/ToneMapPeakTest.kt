package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.HdrStaticMetadata
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import io.github.yuroyami.kiteplayer.spi.toneMapPeakNits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The peak a tone mapper rolls a frame off from, out of the title's static metadata and the scene's dynamic one (#470). */
class ToneMapPeakTest {

    private class Frame(override val hdr: HdrStaticMetadata?, override val sceneMaxNits: Float?) : VideoFrame {
        override val pts: Pts = Pts.Zero
        override val duration: Pts? = null
        override val size: VideoSize = VideoSize(16, 16)
        override val pixelFormat: PlayerPixelFormat = PlayerPixelFormat.Yuv420p10le
        override val colorSpace: ColorSpaceInfo = ColorSpaceInfo()
        override val hardwareSurface: HwSurfaceKind? = null
        override val generation: Generation = Generation(0)
        override fun close() = Unit
    }

    private val title = HdrStaticMetadata(masteringMaxNits = 4000f, maxContentLightNits = 1500)

    @Test
    fun aSceneDimmerThanTheTitleRollsOffFromItsOwnPeak() {
        assertEquals(350f, Frame(title, 350f).toneMapPeakNits)
    }

    @Test
    fun aSceneIsHeldAtTheTitlesPeak() {
        assertEquals(1500f, Frame(title, 3000f).toneMapPeakNits)
    }

    @Test
    fun aScenePeakOutsideTheUsableRangeIsIgnored() {
        assertEquals(1500f, Frame(title, 40f).toneMapPeakNits)
        assertEquals(1500f, Frame(title, 20_000f).toneMapPeakNits)
        assertEquals(1500f, Frame(title, null).toneMapPeakNits)
    }

    @Test
    fun aSceneAloneIsEnoughAndNothingAtAllLeavesTheAssumedMaster() {
        assertEquals(600f, Frame(null, 600f).toneMapPeakNits)
        assertNull(Frame(null, null).toneMapPeakNits)
    }
}
