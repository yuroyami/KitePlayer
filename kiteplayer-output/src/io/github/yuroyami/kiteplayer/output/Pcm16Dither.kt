package io.github.yuroyami.kiteplayer.output

/**
 * Triangular noise of one 16-bit step from a xorshift generator: no allocation and no lock,
 * because it runs on the audio writer thread. Android's 16-bit fallback and the desktop sink both
 * pack with it (#445, #815).
 */
internal class Pcm16Dither(private var state: Int = 0x2545F491) {
    private fun uniform(): Float {
        var x = state
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        state = x
        return (x ushr 8) * (1f / (1 shl 24))
    }

    /** The difference of two uniform values, in -1 to 1 steps. */
    fun next(): Float = uniform() - uniform()
}
