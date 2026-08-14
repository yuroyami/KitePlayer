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
