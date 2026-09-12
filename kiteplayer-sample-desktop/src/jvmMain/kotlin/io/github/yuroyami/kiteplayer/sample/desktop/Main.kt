package io.github.yuroyami.kiteplayer.sample.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.yuroyami.kiteplayer.sample.shared.sampleMedia
import java.io.File

/**
 * How this run was asked for. Everything has a default, so a bare `run` opens the visualiser on the
 * sample song, or on the conformance clip when the song file is missing.
 */
internal data class SampleOptions(
    /** The media the video screen opens: the one asked for, else the conformance clip. */
    val media: String,
    /** The media asked for by argument or property, or null. */
    val requested: String?,
    /** The song the build passes in as `kiteplayer.sample.song`, or null. */
    val song: String?,
    /** True shows the video screen with its modifier toggle instead of the visualiser. */
    val classic: Boolean,
    /** True runs the upload measurement and exits when it is written. */
    val measure: Boolean,
    /** Published frames to collect per measurement phase. */
    val frames: Int,
    /** How many times the plain and decorated phases alternate. */
    val repeats: Int,
    /** Where the measurement report is written, in addition to stdout. */
    val report: File?,
) {
    companion object {
        fun from(args: Array<String>): SampleOptions {
            val flags = args.filter { it.startsWith("--") }
            val requested = args.firstOrNull { !it.startsWith("--") } ?: property("kiteplayer.sample.media")
            val path = requested
                ?: property("kiteplayer.sample.media.default")
                ?: "testmedia/sync1080p30.mp4"
            val reportPath = flagValue(flags, "--report") ?: property("kiteplayer.sample.report")
            val measure = flags.contains("--measure") || property("kiteplayer.sample.measure") != null
            return SampleOptions(
                media = File(path).absolutePath,
                requested = requested?.let { File(it).absolutePath },
                song = property("kiteplayer.sample.song"),
                classic = measure || flags.contains("--modifiers"),
                measure = measure,
                frames = (flagValue(flags, "--frames") ?: property("kiteplayer.sample.frames"))
                    ?.toIntOrNull()?.coerceAtLeast(30) ?: 300,
                repeats = (flagValue(flags, "--repeats") ?: property("kiteplayer.sample.repeats"))
                    ?.toIntOrNull()?.coerceIn(1, 20) ?: 1,
                report = reportPath?.let(::File),
            )
        }

        /** An empty value still counts as set, so `-Pkiteplayer.sample.measure` alone works. */
        private fun property(key: String): String? =
            System.getProperty(key)?.takeUnless { it.equals("false", ignoreCase = true) }

        private fun flagValue(flags: List<String>, name: String): String? =
            flags.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')
    }
}

/**
 * The Compose Desktop sample. It opens on the audio visualiser playing the sample song, or plays a
 * path given as the first argument. `--modifiers` shows the
 * video screen with its clip, alpha and rotation toggle; `--measure` takes the upload numbers and exits.
 */
fun main(args: Array<String>) {
    val options = SampleOptions.from(args)
    application {
        val windowState = rememberWindowState(
            size = DpSize(1120.dp, 760.dp),
            position = WindowPosition(Alignment.Center),
        )
        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = if (options.classic) "KitePlayer on Compose Desktop" else "KitePlayer",
        ) {
            // The launch proof, printed once the window is really on screen.
            LaunchedEffect(window) {
                var waited = 0
                while (!window.isShowing && waited < 240) {
                    withFrameNanos { }
                    waited++
                }
                println("window showing=${window.isShowing} bounds=${window.bounds}")
            }
            if (options.classic) {
                DesktopSample(options, onMeasurementDone = ::exitApplication)
            } else {
                VisualizerSample(sampleMedia(options.requested, options.song, options.media) { File(it).isFile })
            }
        }
    }
}
