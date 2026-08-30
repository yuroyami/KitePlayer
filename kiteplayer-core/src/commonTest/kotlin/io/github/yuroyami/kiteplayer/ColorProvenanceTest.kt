package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.ColorTransfer
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which colour fields are the file's own word, and which are this library's guess.
 *
 * ### The question this settles, and why the answer is "no"
 *
 * The plan asked whether [PlaybackWarning.HdrToneMapped] should say WHICH of the two it
 * acted on: a transfer the container declared, or one this library guessed. The answer is that
 * it never needs to, because a guess can never be HDR. Every guess anywhere in the pair is the
 * standard-versus-high-definition rule, which answers BT.601 or BT.709 and nothing else, so a
 * tone map is always acting on the file's own declaration.
 *
 * That is a property of the guess rule rather than an accident, so it is pinned here: if a future
 * guess rule ever learns to answer PQ or HLG, this goes red and the warning has to grow a field.
 */
class ColorProvenanceTest {

    @Test
    fun `a guessed colour is never high dynamic range`() {
        for (height in listOf(1, 240, 480, 576, 577, 720, 1080, 2160, 4320)) {
            val guess = ColorSpaceInfo.guessFor(height)
            assertFalse(
                guess.isHdr,
                "guessFor($height) answered ${guess.transfer}; a guessed HDR transfer would mean " +
                    "PlaybackWarning.HdrToneMapped can fire on something nobody declared",
            )
            assertFalse(guess.transferSpecified, "a guess must admit it is one")
            assertFalse(guess.matrixSpecified)
            assertFalse(guess.primariesSpecified)
            assertFalse(guess.allSpecified)
        }
        assertFalse(ColorSpaceInfo.Unspecified.isHdr)
        assertFalse(ColorSpaceInfo.Unspecified.allSpecified)
    }

    @Test
    fun `a declared colour says so field by field`() {
        // The default is "the source said all of this", which is what a decoder reporting real
        // metadata produces. A field-by-field flag exists because containers are partial: a file
        // can declare its transfer and say nothing about its range.
        val declared = ColorSpaceInfo(transfer = ColorTransfer.Pq)
        assertTrue(declared.isHdr)
        assertTrue(declared.allSpecified)

        val partial = declared.copy(rangeSpecified = false)
        assertTrue(partial.transferSpecified, "the transfer is still the file's word")
        assertFalse(partial.allSpecified, "but the set as a whole is not")
    }
}
