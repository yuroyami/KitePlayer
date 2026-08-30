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
import java.io.File

/**
 * How this run was asked for. Everything has a default, so a bare `run` plays the conformance
 * clip in a window.
 */
internal data class SampleOptions(
    /** The media to open. */
    val media: String,
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
            val path = args.firstOrNull { !it.startsWith("--") }
                ?: property("kiteplayer.sample.media")
                ?: property("kiteplayer.sample.media.default")
                ?: "testmedia/sync1080p30.mp4"
            val reportPath = flagValue(flags, "--report") ?: property("kiteplayer.sample.report")
            return SampleOptions(
                media = File(path).absolutePath,
                measure = flags.contains("--measure") || property("kiteplayer.sample.measure") != null,
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
 * The Compose Desktop sample. Pass a media path, or nothing for the conformance
 * clip; pass `--measure` to take the upload numbers and exit.
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
            title = "KitePlayer on Compose Desktop",
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
            DesktopSample(options, onMeasurementDone = ::exitApplication)
        }
    }
}
