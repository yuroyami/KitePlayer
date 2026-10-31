package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.spi.ColorMatrix
import io.github.yuroyami.kiteplayer.spi.ColorPrimaries
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.ColorTransfer
import kotlin.test.Test
import kotlin.test.assertEquals
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
            val guess = ColorSpaceInfo.guessFor(width = height * 4 / 3, height = height)
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

    /**
     * A picture 1280 or more pixels wide is high definition whatever its height, as mpv and
     * libplacebo guess (#606). A 720p film in a scope ratio, 1280x536, played with the BT.601
     * matrix though it was encoded with BT.709.
     */
    @Test
    fun aWidePictureIsGuessedAsHighDefinitionWhateverItsHeight() {
        assertEquals(ColorMatrix.Bt709, ColorSpaceInfo.guessFor(width = 1280, height = 536).matrix)
        assertEquals(ColorPrimaries.Bt709, ColorSpaceInfo.guessFor(width = 1280, height = 536).primaries)
        assertEquals(ColorMatrix.Bt709, ColorSpaceInfo.guessFor(width = 1152, height = 648).matrix)
        assertEquals(ColorMatrix.Bt709, ColorSpaceInfo.guessFor(width = 1920, height = 800).matrix)
        assertEquals(ColorMatrix.Bt601, ColorSpaceInfo.guessFor(width = 720, height = 576).matrix)
        assertEquals(ColorMatrix.Bt601, ColorSpaceInfo.guessFor(width = 1024, height = 576).matrix)
        assertEquals(ColorMatrix.Bt601, ColorSpaceInfo.guessFor(width = 640, height = 480).matrix)
        for (width in listOf(1, 720, 1279, 1280, 3840)) {
            for (height in listOf(1, 480, 576, 577, 1080, 2160)) {
                val guess = ColorSpaceInfo.guessFor(width, height)
                assertFalse(guess.isHdr, "guessFor($width x $height) answered ${guess.transfer}")
                assertFalse(guess.matrixSpecified, "a guess must admit it is one")
            }
        }
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
