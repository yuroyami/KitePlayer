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
