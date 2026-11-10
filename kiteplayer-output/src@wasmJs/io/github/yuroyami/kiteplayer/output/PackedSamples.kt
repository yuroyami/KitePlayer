@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer.output

import kotlin.js.JsAny

/**
 * [count] floats of [samples] as one string of code units 0 to 255, four for each float, its bits
 * in little-endian order (#909).
 *
 * Kotlin/Wasm has no bulk move of a typed array, so a block crossed into JavaScript one sample at
 * a time: about 96,000 calls a second for 48 kHz stereo. One string crosses at once, and
 * [samplesOf] rebuilds the floats on the other side in one tight loop. Latin-1 by construction:
 * every code unit is below 0x100, so none is in the surrogate range and no encoding step can
 * reinterpret one. The order is little-endian because a `Float32Array` reads its bytes in the
 * host's order, which is little-endian in every browser engine.
 */
internal fun packSamples(samples: FloatArray, count: Int): String {
    val chars = CharArray(count * 4)
    for (i in 0 until count) {
        val bits = samples[i].toRawBits()
        val at = i * 4
        chars[at] = (bits and 0xFF).toChar()
        chars[at + 1] = ((bits ushr 8) and 0xFF).toChar()
        chars[at + 2] = ((bits ushr 16) and 0xFF).toChar()
        chars[at + 3] = ((bits ushr 24) and 0xFF).toChar()
    }
    return chars.concatToString()
}

/** The floats [packSamples] packed, as a fresh `Float32Array` whose buffer a message can transfer. */
@JsFun(
    """(packed) => {
      const n = packed.length;
      const bytes = new Uint8Array(n);
      for (let i = 0; i < n; i++) bytes[i] = packed.charCodeAt(i);
      return new Float32Array(bytes.buffer);
    }""",
)
internal external fun samplesOf(packed: String): JsAny
