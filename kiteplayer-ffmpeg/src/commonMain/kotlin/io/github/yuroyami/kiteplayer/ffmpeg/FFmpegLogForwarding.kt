package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteffmpeg.FFmpeg
import io.github.yuroyami.kiteffmpeg.FFmpegLogLevel
import io.github.yuroyami.kiteplayer.KiteLog
import kotlinx.atomicfu.atomic

/**
 * Routes FFmpeg's own log lines, warnings and worse, into [KiteLog] with the tag `ffmpeg`.
 *
 * KiteLog is silent until an application installs a sink, so FFmpeg prints nothing by default,
 * and with a sink its lines arrive beside the player's own. FFmpeg's log is one setting for the
 * whole process, so the first backend that opens an item installs this once.
 */
internal object FFmpegLogForwarding {
    private val installed = atomic(false)

    fun install() {
        if (!installed.compareAndSet(expect = false, update = true)) return
        try {
            FFmpeg.setLogSink(FFmpegLogLevel.Warning) { level, component, message ->
                val line = if (component.isEmpty()) message else "[$component] $message"
                KiteLog.log(TAG, line, mapOf("level" to level.name))
            }
        } catch (refused: Exception) {
            // An FFmpeg that does not match KiteFFmpeg refuses every call; the open reports that.
            installed.value = false
        }
    }

    const val TAG: String = "ffmpeg"
}
