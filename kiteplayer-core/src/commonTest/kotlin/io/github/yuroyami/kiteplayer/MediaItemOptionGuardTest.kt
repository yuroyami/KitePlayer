package io.github.yuroyami.kiteplayer

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The two demuxer options an item may not set.
 *
 * MP3 seeking is only correct when the table of contents is used AND fast seek is unset, together.
 * Either key on its own gives seeking that lands in the wrong place, looks exactly like a player
 * bug, and stays invisible until somebody compares against another player. The engine owns that
 * strategy, so an item carrying either is refused where it is built rather than accepted and
 * quietly ignored at open.
 */
class MediaItemOptionGuardTest {

    @Test
    fun `fast seek is refused and the message names the key`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            MediaItem("a.mp3", openOptions = mapOf("fflags" to "fastseek"))
        }
        assertTrue("fastseek" in failure.message.orEmpty(), "the refusal must name it: ${failure.message}")
    }

    @Test
    fun `fast seek is found among other flags however they are joined`() {
        for (flags in listOf("discardcorrupt,fastseek", "fastseek+genpts", "genpts,fastseek,nobuffer")) {
            assertFailsWith<IllegalArgumentException>("accepted $flags") {
                MediaItem("a.mp3", openOptions = mapOf("fflags" to flags))
            }
        }
    }

    @Test
    fun `the table of contents key is refused`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            MediaItem("a.mp3", openOptions = mapOf("usetoc" to "0"))
        }
        assertTrue("usetoc" in failure.message.orEmpty(), "the refusal must name it: ${failure.message}")
    }

    @Test
    fun `other flags are still accepted`() {
        // The guard must not turn into a whitelist: these are ordinary and useful.
        MediaItem("a.mp4", openOptions = mapOf("fflags" to "genpts,discardcorrupt"))
        MediaItem("a.mp4", openOptions = mapOf("probesize" to "5000000"))
        MediaItem("a.mp4", openOptions = mapOf("analyzeduration" to "1000000"))
    }

    @Test
    fun `a flag that merely starts with the refused name is allowed`() {
        // "fastseeker" is not "fastseek". Matching on substring would refuse a key nobody banned.
        MediaItem("a.mp4", openOptions = mapOf("fflags" to "fastseeker"))
    }

    @Test
    fun `an item with no options is built as before`() {
        assertTrue(MediaItem("a.mp4").openOptions.isEmpty())
    }
}
