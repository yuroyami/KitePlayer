package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioSink

/**
 * A Kotlin ring behind a Kotlin render callback, which is the only arrangement this target has.
 *
 * The device sink pulls from this ring on its own writer thread, so the ring's real-time side
 * follows the C ring's protocol and never waits for the feeder. The one target that has a second
 * arrangement is native, where the ring is C; see `AudioPath.native.kt`.
 */
internal actual suspend fun openAudioPath(
    sink: AudioSink,
    request: AudioFormat,
    capacityFrames: (AudioFormat) -> Int,
): OpenedAudioPath = openKotlinAudioPath(sink, request, capacityFrames)
