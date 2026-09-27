package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteffmpeg.DecoderId

/**
 * On macOS the desktop JVM attaches VideoToolbox for the same codecs as the Apple native targets,
 * because KiteFFmpeg's macOS library carries the same hwaccels. The software renderers read each
 * frame through its downloaded copy.
 *
 * Linux and Windows decode in software here. KiteFFmpeg's Windows library can attach D3D11VA, but
 * nothing has measured that route on the JVM yet (#101).
 */
internal actual fun platformDecoderSelection(codec: String, policy: HwdecPolicy): DecoderSelection =
    decoderSelection(policy, route = if (isMacOs) codec.videoToolboxRoute() else null)

/**
 * Audio decodes in software on the desktop JVM. The macOS library carries AudioToolbox decoders
 * too, but nothing has measured them on the JVM, and this project offers no route it has not measured.
 */
internal actual fun platformAudioDecoder(codec: String): DecoderId? = null

private val isMacOs: Boolean = System.getProperty("os.name").orEmpty().startsWith("Mac")
