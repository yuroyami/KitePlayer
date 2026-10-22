package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteffmpeg.DecoderId

/**
 * On macOS the desktop JVM attaches VideoToolbox for the same codecs as the Apple native targets,
 * because KiteFFmpeg's macOS library carries the same hwaccels. On Windows it attaches Direct3D 11
 * (#101), which KiteFFmpeg's Windows library carries. The software renderers read each frame of
 * either through its downloaded copy.
 *
 * Linux decodes in software here. VA-API would link libva, a new required native library, which
 * the owner refused on yuroyami/KiteFFmpeg#61.
 */
internal actual fun platformDecoderSelection(codec: String, policy: HwdecPolicy): DecoderSelection =
    decoderSelection(policy, route = desktopJvmRoute(codec, osName))

/** The hardware route the desktop JVM offers for [codec] on the operating system named [osName]. */
internal fun desktopJvmRoute(codec: String, osName: String): HardwareRoute? = when {
    osName.startsWith("Mac") -> codec.videoToolboxRoute()
    osName.startsWith("Windows") -> codec.d3d11vaRoute()
    else -> null
}

/**
 * Audio decodes in software on the desktop JVM. The macOS library carries AudioToolbox decoders
 * too, but nothing has measured them on the JVM, and this project offers no route it has not measured.
 */
internal actual fun platformAudioDecoder(codec: String): DecoderId? = null

private val osName: String = System.getProperty("os.name").orEmpty()
