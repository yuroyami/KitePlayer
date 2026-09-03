package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteffmpeg.dsl.videoFilters
import io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi
import io.github.yuroyami.kiteplayer.MediaItem
import kotlin.test.Test
import kotlin.test.assertEquals

/** The typed route onto `MediaItem.videoFilter`: the DSL compiles, the item carries the string. */
@OptIn(KitePlayerLowLevelApi::class)
class TypedFiltersTest {

    @Test
    fun `a typed chain lands on the item as the string the golden pins`() {
        val item = MediaItem("clip.mp4").withVideoFilter(videoFilters { scale(1280, 720) })
        assertEquals("scale=1280:720", item.videoFilter)
    }

    @Test
    fun `steps join the way the graph builder reads them`() {
        val item = MediaItem("clip.mp4").withVideoFilter(
            videoFilters {
                scale(1280, 720)
                eq(brightness = 0.1)
            },
        )
        assertEquals("scale=1280:720,eq=brightness=0.1", item.videoFilter)
    }

    @Test
    fun `everything else on the item is left alone`() {
        val original = MediaItem("clip.mp4", headers = mapOf("Authorization" to "Bearer x"), formatHint = "mp4")
        val filtered = original.withVideoFilter(videoFilters { scale(-1, 480) })
        assertEquals(original.copy(videoFilter = "scale=-1:480"), filtered)
    }
}
