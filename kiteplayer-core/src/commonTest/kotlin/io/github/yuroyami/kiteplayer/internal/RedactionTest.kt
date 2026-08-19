package io.github.yuroyami.kiteplayer.internal

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * SEC-3: the support bundle's redaction kept the query string, which is where credentials live.
 *
 * Every row below is a real shape a media URI takes. The point of the suite is that the redacted
 * form is the basename ALONE: no host, no path, no query, no fragment and no userinfo, because a
 * bundle gets pasted into an issue tracker by someone who is not thinking about any of that.
 */
class RedactionTest {

    @Test
    fun `a query string never survives redaction`() {
        assertEquals("video.mp4", redactUri("https://host/video.mp4?token=SECRET"))
        assertEquals("video.mp4", redactUri("https://host/video.mp4?token=SECRET&sig=OTHER"))
        assertEquals("video.mp4", redactUri("https://host/video.mp4#t=30"))
        assertEquals("video.mp4", redactUri("https://host/video.mp4?token=SECRET#t=30"))
    }

    @Test
    fun `the host and the directory and the userinfo go with it`() {
        assertEquals("movie.mp4", redactUri("https://user:password@host:8443/deep/path/movie.mp4"))
        assertEquals("movie.mp4", redactUri("/home/someone/movie.mp4"))
        assertEquals("movie.mp4", redactUri("movie.mp4"))
    }

    @Test
    fun `a uri with nothing left to name says so instead of returning empty`() {
        assertEquals("(redacted)", redactUri("https://host/?token=SECRET"))
        assertEquals("(redacted)", redactUri(""))
    }

    @Test
    fun `a uri quoted inside an error message is redacted where it stands`() {
        assertEquals(
            "cannot open a.mp4: 404",
            redactUrisIn("cannot open https://host/deep/a.mp4?token=SECRET: 404"),
        )
        assertEquals(
            "tried a.mp4 then b.mp4",
            redactUrisIn("tried https://one/a.mp4?k=1 then https://two/x/b.mp4#f"),
        )
    }

    @Test
    fun `text with no uri in it is returned unchanged`() {
        assertEquals("the decoder refused frame 12", redactUrisIn("the decoder refused frame 12"))
    }
}
