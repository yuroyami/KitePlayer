package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.spi.RendererEvent
import kotlin.test.Test
import kotlin.test.assertEquals

/** A renderer repeats what it does with HDR once a second, so every open hears it, and a change at once. */
class HdrAnnouncerTest {

    @Test
    fun anAnnouncementRepeatsAtMostOnceASecond() {
        var now = 0L
        val heard = mutableListOf<RendererEvent>()
        val announcer = HdrAnnouncer(nowMillis = { now }) { heard += it }

        announcer.announce("Pq")
        now = 400
        announcer.announce("Pq")
        assertEquals(1, heard.size, "a second announcement within a second")

        now = 1_000
        announcer.announce("Hlg")
        assertEquals(listOf<RendererEvent>(RendererEvent.ToneMapEngaged("Pq"), RendererEvent.ToneMapEngaged("Hlg")), heard)
    }

    @Test
    fun aChangeBetweenShownAndToneMappedIsAnnouncedAtOnce() {
        var now = 0L
        val heard = mutableListOf<RendererEvent>()
        val announcer = HdrAnnouncer(nowMillis = { now }) { heard += it }

        announcer.announceShown("Pq", headroom = 2f)
        now = 100
        announcer.announce("Pq")
        now = 200
        announcer.announceShown("Pq", headroom = 2f)
        assertEquals(
            listOf(RendererEvent.HdrShown("Pq", 2f), RendererEvent.ToneMapEngaged("Pq"), RendererEvent.HdrShown("Pq", 2f)),
            heard,
        )
    }
}
