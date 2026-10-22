package io.github.yuroyami.kiteplayer.session

import io.github.yuroyami.kiteplayer.LoopMode
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlayerMemento
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What the resume card keeps, and who its root is for (#431). */
class PlaybackResumptionTest {

    private val memento = PlayerMemento(
        queue = listOf(MediaItem("https://example.com/one.m4a"), MediaItem("https://example.com/two.m4a")),
        queueIndex = 1,
        position = 83.seconds,
        speed = 1.25,
        preservePitch = true,
        volume = 0.8f,
        muted = false,
        loop = LoopMode.Off,
        shuffle = false,
        subtitleDelay = Duration.ZERO,
        audioDelay = Duration.ZERO,
        audioLanguage = "fr",
        subtitleLanguage = null,
        subtitlesOff = false,
    )

    @Test
    fun `the saved state reads back as it was saved`() {
        val saved = assertNotNull(resumptionFrom(resumptionEntries(memento, "Chapter Two", "An Audiobook")))
        assertEquals(memento, saved.memento)
        assertEquals("Chapter Two", saved.title)
        assertEquals("An Audiobook", saved.subtitle)
        assertNull(assertNotNull(resumptionFrom(resumptionEntries(memento, "Chapter Two", null))).subtitle)
    }

    @Test
    fun `nothing saved or something unreadable offers no item`() {
        assertNull(resumptionFrom(emptyMap()))
        val entries = resumptionEntries(memento, "Chapter Two", null)
        assertNull(resumptionFrom(entries - "title"), "a card needs words")
        assertNull(resumptionFrom(entries + ("memento.version" to "99")), "a memento from a newer build does not read back")
        assertNull(resumptionFrom(entries.filterKeys { !it.startsWith("memento.queue.size") }))
    }

    @Test
    fun `only a trusted caller asking for the recent root with something saved gets a root`() {
        assertTrue(answersRecentRoot(recent = true, trusted = true, saved = true))
        assertFalse(answersRecentRoot(recent = false, trusted = true, saved = true), "a browser that is not the resume card")
        assertFalse(answersRecentRoot(recent = true, trusted = false, saved = true), "an application nobody trusts for media control")
        assertFalse(answersRecentRoot(recent = true, trusted = true, saved = false), "nothing to resume")
    }
}
