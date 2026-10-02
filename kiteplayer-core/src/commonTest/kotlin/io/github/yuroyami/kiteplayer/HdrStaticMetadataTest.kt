package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.spi.HdrStaticMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The content peak a tone map uses comes from the stream's own metadata (#378). */
class HdrStaticMetadataTest {

    @Test
    fun thePeakIsTheBrightestPixelThenTheMasteringPeak() {
        assertEquals(4000f, HdrStaticMetadata(masteringMaxNits = 1000f, maxContentLightNits = 4000).peakNits)
        assertEquals(1000f, HdrStaticMetadata(masteringMaxNits = 1000f).peakNits)
        // A value outside 100 to 10000 nits is not a peak anyone graded to, so the next one is used.
        assertEquals(1000f, HdrStaticMetadata(masteringMaxNits = 1000f, maxContentLightNits = 50).peakNits)
        assertNull(HdrStaticMetadata(masteringMaxNits = 50_000f).peakNits)
        assertNull(HdrStaticMetadata().peakNits)
    }
}
