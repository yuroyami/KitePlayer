package io.github.yuroyami.kiteplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A signed URL or a request header never reaches text an app can show or log: the label the lock
 * screen falls back to, a printed item, and the message and printout of an error or a warning.
 */
class SecretFreeTextTest {

    private val signed = "https://storage.example/lessons/lesson-3.mp4?X-Goog-Signature=SECRET&X-Goog-Expires=900"

    @Test
    fun theLabelIsTheFileNameWithoutTheQuery() {
        assertEquals("lesson-3.mp4", MediaItem(signed).label)
        assertEquals("(redacted)", MediaItem("https://storage.example/?token=SECRET").label)
    }

    @Test
    fun aPrintedItemShowsHeaderNamesButNotTheirValues() {
        val printed = MediaItem(signed, headers = mapOf("Authorization" to "Bearer SECRET")).toString()
        assertFalse("SECRET" in printed, printed)
        assertTrue("lesson-3.mp4" in printed, printed)
        assertTrue("Authorization" in printed, printed)
    }

    @Test
    fun anErrorQuotesOnlyTheFileName() {
        val errors = listOf(
            PlaybackError.SourceUnavailable(signed, cause = null, detail = "HTTP 403 for $signed"),
            PlaybackError.SourceStalled(signed, 30.seconds),
            PlaybackError.NotMedia(signed),
        )
        for (error in errors) {
            assertFalse("SECRET" in error.message, error.message)
            assertFalse("SECRET" in error.toString(), error.toString())
            assertTrue("lesson-3.mp4" in error.message, error.message)
        }
    }

    @Test
    fun aWarningQuotesOnlyTheFileName() {
        val warning = PlaybackWarning.SubtitleSourceUnreadable(signed.replace(".mp4", ".srt"), "HTTP 404 for $signed")
        assertFalse("SECRET" in warning.message, warning.message)
        assertFalse("SECRET" in warning.toString(), warning.toString())
        assertTrue(warning.toString().startsWith("SubtitleSourceUnreadable: "), warning.toString())
    }
}
