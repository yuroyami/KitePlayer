// The handoff carries a C pointer, so this file crosses into cinterop and says so at the top.
@file:OptIn(ExperimentalForeignApi::class, RawRingApi::class)

package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioSink
import io.github.yuroyami.kiteplayer.spi.NativeRingAudioSink
import io.github.yuroyami.kiteplayer.spi.RawRingApi
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * The one place in the engine that knows there are two arrangements.
 *
 * A sink that owns its device callback in C says so by implementing [NativeRingAudioSink], and then it
 * owns the ring as well: the engine writes into a ring the sink created and the sink releases it on
 * close. Every other sink, including every test fake and every future push-model sink, takes the
 * portable path with a Kotlin ring behind a Kotlin callback, unchanged from before B1.8.
 *
 * The test is `is NativeRingAudioSink` and not a platform check on purpose. Whether the device callback
 * can be C is a property of the sink, not of the operating system: on macOS `CoreAudioSink` has one and
 * a hypothetical sink writing to a file does not, and both are macOS.
 */
internal actual suspend fun openAudioPath(
    sink: AudioSink,
    request: AudioFormat,
    capacityFrames: (AudioFormat) -> Int,
): OpenedAudioPath {
    if (sink is NativeRingAudioSink) {
        val handoff = sink.openWithRing(request, capacityFrames)
        return OpenedAudioPath(
            format = handoff.format,
            // Adopted rather than created, and deliberately not owned: the sink frees it, because only
            // the code that stops the device can promise the callback is out of the render path first.
            ring = NativeAudioRing.adopting(handoff.ring, handoff.format),
        )
    }
    return openKotlinAudioPath(sink, request, capacityFrames)
}
