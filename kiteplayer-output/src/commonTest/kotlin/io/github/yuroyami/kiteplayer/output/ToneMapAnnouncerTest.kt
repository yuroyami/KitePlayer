package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.spi.RendererEvent
import kotlin.test.Test
import kotlin.test.assertEquals

/** A renderer repeats its tone map announcement once a second, so every open hears it. */
class ToneMapAnnouncerTest {

    @Test
    fun anAnnouncementRepeatsAtMostOnceASecond() {
        var now = 0L
        val heard = mutableListOf<RendererEvent>()
        val announcer = ToneMapAnnouncer(nowMillis = { now }) { heard += it }

        announcer.announce("Pq")
        now = 400
        announcer.announce("Pq")
        assertEquals(1, heard.size, "a second announcement within a second")

        now = 1_000
        announcer.announce("Hlg")
        assertEquals(listOf<RendererEvent>(RendererEvent.ToneMapEngaged("Pq"), RendererEvent.ToneMapEngaged("Hlg")), heard)
    }
}
