@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * An item's own cover picture reaches the application and the media session (#425): copied from the
 * cover's one packet, even when the picture is parked and nothing decodes it, and gone once the item
 * is.
 */
class CoverArtTest {

    private val song = MediaScript(durationUs = 3_000_000, videoIsCoverArt = true)

    @Test
    fun aSongsCoverIsPublishedOnceItIsRead() = runTest {
        val harness = CoreHarness(this, script = song)
        harness.openWithRenderer()
        harness.run(100.milliseconds)
        val cover = assertNotNull(harness.core.coverArt.value, "the cover was not published")
        assertEquals(true, cover.bytes.isNotEmpty())
        harness.core.stop()
        harness.run(100.milliseconds)
        assertNull(harness.core.coverArt.value, "the cover stayed after the item closed")
        harness.close()
    }

    @Test
    fun aParkedPicturesCoverIsPublishedToo() = runTest {
        val harness = CoreHarness(this, script = song, config = PlayerConfig(videoEnabled = false))
        harness.openWithRenderer()
        harness.run(100.milliseconds)
        assertNotNull(harness.core.coverArt.value, "an audio-only player got no cover")
        harness.close()
    }

    @Test
    fun aFilmHasNoCover() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 3_000_000))
        harness.openWithRenderer()
        harness.core.play()
        harness.run(300.milliseconds)
        assertNull(harness.core.coverArt.value, "a film's picture was taken for a cover")
        harness.close()
    }
}
