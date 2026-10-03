package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.HwdecKind
import io.github.yuroyami.kiteplayer.HwdecPolicy
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaCodecPolicyTest {
    @Test
    fun `auto and require allow the direct MediaCodec candidate`() {
        assertTrue(HwdecPolicy.Auto.allowsMediaCodec())
        assertTrue(HwdecPolicy.Require.allowsMediaCodec())
    }

    @Test
    fun `off and ineligible preference lists refuse MediaCodec`() {
        assertFalse(HwdecPolicy.Off.allowsMediaCodec())
        assertFalse(HwdecPolicy.Prefer(emptyList()).allowsMediaCodec())
        assertFalse(
            HwdecPolicy.Prefer(listOf(HwdecKind.VideoToolbox, HwdecKind.Vaapi)).allowsMediaCodec(),
        )
    }

    @Test
    fun `prefer stays disabled until cross-factory ordering is globally known`() {
        assertFalse(HwdecPolicy.Prefer(listOf(HwdecKind.MediaCodec)).allowsMediaCodec())
        assertFalse(
            HwdecPolicy.Prefer(listOf(HwdecKind.VideoToolbox, HwdecKind.MediaCodec))
                .allowsMediaCodec(),
        )
    }
}

class DirectSurfaceGeometryTest {
    private val stream = io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo(
        index = 0,
        kind = io.github.yuroyami.kiteplayer.TrackKind.Video,
        codec = "h264",
        rotationDegrees = 90,
    )

    @Test
    fun `a mirrored stream is refused where the codec turns the picture and kept where the renderer does`() {
        val mirrored = stream.copy(mirrored = true)
        assertTrue(directSurfaceGeometryRefusal(mirrored, applyCodecRotation = true).orEmpty().contains("mirror"))
        kotlin.test.assertNull(directSurfaceGeometryRefusal(mirrored, applyCodecRotation = false))
        kotlin.test.assertNull(directSurfaceGeometryRefusal(stream, applyCodecRotation = true))
    }
}

class DirectSurfaceDolbyVisionTest {
    private val stream = io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo(
        index = 0,
        kind = io.github.yuroyami.kiteplayer.TrackKind.Video,
        codec = "hevc",
    )

    @Test
    fun `a Dolby Vision base layer that cannot play alone is refused and one that can is kept`() {
        val profile5 = stream.copy(dolbyVision = io.github.yuroyami.kiteplayer.DolbyVisionInfo(profile = 5, level = 6, baseLayerCompatibility = 0))
        val profile81 = stream.copy(dolbyVision = io.github.yuroyami.kiteplayer.DolbyVisionInfo(profile = 8, level = 6, baseLayerCompatibility = 1))
        assertTrue(directSurfaceDolbyVisionRefusal(profile5).orEmpty().contains("profile 5"))
        kotlin.test.assertNull(directSurfaceDolbyVisionRefusal(profile81))
        kotlin.test.assertNull(directSurfaceDolbyVisionRefusal(stream))
    }
}
