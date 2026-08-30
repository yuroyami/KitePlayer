package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioSink

/**
 * A Kotlin ring behind a Kotlin render callback, which is the only arrangement this target has.
 *
 * There is no C here and there never will be on js and wasmJs, and there is no device sink on the JVM
 * or on Android yet either. The one target that has a second arrangement is native; see
 * `AudioPath.native.kt`.
 */
internal actual suspend fun openAudioPath(
    sink: AudioSink,
    request: AudioFormat,
    capacityFrames: (AudioFormat) -> Int,
): OpenedAudioPath = openKotlinAudioPath(sink, request, capacityFrames)
