package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.DeinterlacePolicy
import io.github.yuroyami.kiteplayer.spi.FieldOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeinterlaceFilterTest {

    @Test
    fun autoDeinterlacesOnlyAStreamTheContainerCallsInterlaced() {
        val filter = "bwdif=mode=send_frame:parity=auto:deint=interlaced"
        assertEquals(filter, deinterlaceFilter(DeinterlacePolicy.Auto, FieldOrder.TopFirst))
        assertEquals(filter, deinterlaceFilter(DeinterlacePolicy.Auto, FieldOrder.BottomFirst))
        assertNull(deinterlaceFilter(DeinterlacePolicy.Auto, FieldOrder.Progressive))
        assertNull(deinterlaceFilter(DeinterlacePolicy.Auto, FieldOrder.Unknown), "unknown is not interlaced")
    }

    @Test
    fun alwaysDeinterlacesEveryFrameOfEveryStream() {
        for (order in FieldOrder.entries) {
            assertEquals("bwdif=mode=send_frame:parity=auto:deint=all", deinterlaceFilter(DeinterlacePolicy.Always, order), "$order")
        }
    }

    @Test
    fun offNeverDeinterlaces() {
        for (order in FieldOrder.entries) assertNull(deinterlaceFilter(DeinterlacePolicy.Off, order), "$order")
    }
}
