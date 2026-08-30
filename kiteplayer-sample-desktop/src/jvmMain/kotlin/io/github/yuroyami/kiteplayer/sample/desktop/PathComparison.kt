package io.github.yuroyami.kiteplayer.sample.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.yuroyami.kiteplayer.KitePlayerPlatform
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.compose.KitePlayerVideo
import io.github.yuroyami.kiteplayer.compose.KiteRenderPath
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.exitProcess

/**
 * The phase's exit measurement: does the desktop native view actually save the picture from a
 * busy UI, on real video rather than on a coloured rectangle?
 *
 * The interop spike proved a canvas painting a solid colour keeps its rate. This proves the thing
 * the phase is FOR: a real player, real frames, the engine's own counters. It runs one arm per
 * invocation so the two paths never share a process, a window or a warmed-up decoder:
 *
 *   -Dcompare.path=native|compose   which rendering path to use
 *   -Dcompare.burn=true|false       whether to choke the Compose frame clock
 *   -Dcompare.seconds=N             how long to measure, default 20
 *
 * The number that matters is dropped frames, and the comparison that matters is each path against
 * ITSELF with and without the burner. Comparing the two paths to each other would mostly measure
 * that they draw differently.
 */
private val composeFrames = AtomicLong(0)

fun main() {
    val useNative = (System.getProperty("compare.path") ?: "compose") == "native"
    val burn = System.getProperty("compare.burn") == "true"
    val seconds = (System.getProperty("compare.seconds") ?: "20").toInt()
    val media = System.getProperty("kiteplayer.sample.media.default")
        ?: error("no media path: pass -Dkiteplayer.sample.media.default=<file>")

    application {
        val state = rememberWindowState(size = DpSize(960.dp, 620.dp))
        Window(
            onCloseRequest = ::exitApplication,
            state = state,
            title = "KitePlayer path comparison",
        ) {
            val player = androidx.compose.runtime.remember { KitePlayerPlatform.createOrNull() }
            if (player == null) {
                LaunchedEffect(Unit) {
                    println("no player available on this platform")
                    exitProcess(1)
                }
                return@Window
            }

            Box(Modifier.fillMaxSize()) {
                KitePlayerVideo(
                    player = player,
                    modifier = Modifier.fillMaxSize(),
                    path = if (useNative) KiteRenderPath.NativeView else KiteRenderPath.ComposeCanvas,
                    onEffectivePath = { println("effective path = $it") },
                )
            }

            // The burner runs on the Compose frame clock, which is exactly where a heavy UI
            // spends itself. 200 ms per frame is not subtle on purpose: the question is whether
            // the picture notices at all, not how much jank is tolerable.
            // Counted in every arm, not only the choked one: the DRAW rate is the number that
            // separates the two paths, and it is meaningless without the idle rate beside it.
            LaunchedEffect(Unit) {
                while (true) {
                    withFrameNanos {
                        composeFrames.incrementAndGet()
                        if (burn) {
                            val until = System.nanoTime() + 200_000_000L
                            @Suppress("ControlFlowWithEmptyBody")
                            while (System.nanoTime() < until) {
                            }
                        }
                    }
                }
            }

            LaunchedEffect(player) {
                runCatching { player.open(MediaItem(uri = media)) }
                    .onFailure {
                        println("open failed: ${it.message}")
                        exitProcess(1)
                    }
                // LOOP, or the measurement window outlives the clip and both arms report the
                // same thing: the first run measured a ten second file over a fifteen second
                // window and saw no difference because most of the window was not playing.
                player.setLoop(io.github.yuroyami.kiteplayer.LoopMode.One)
                player.play()
                // Let the pipeline settle before the counters are read, so start-up costs are not
                // charged to the arm being measured.
                delay(3000)
                val before = player.stats.value
                composeFrames.set(0)
                delay(seconds * 1000L)
                val after = player.stats.value
                val composeFps = composeFrames.get().toDouble() / seconds
                val presented = after.submittedFrames - before.submittedFrames
                val droppedLate = after.droppedFramesLate - before.droppedFramesLate
                println("=== PATH COMPARISON ===")
                println("path            = ${if (useNative) "native view" else "compose canvas"}")
                println("compose burner  = $burn")
                println("window          = $seconds s")
                println("frames submitted= $presented")
                println("dropped late    = $droppedLate")
                println("submitted per s = ${"%.1f".format(presented.toDouble() / seconds)}")
                println("compose fps     = ${"%.1f".format(composeFps)}")
                // What it means, so the numbers are not read as saying more than they do: the
                // engine submits on its own dispatcher and keeps submitting whatever the UI does,
                // so the submission counts cannot separate the paths and are not supposed to.
                // On the Compose canvas the picture is DRAWN by Compose, so its rate is the
                // compose fps above. The native view paints itself and keeps its own rate.
                println("=== END ===")
                runCatching { player.closeAndAwait() }
                exitProcess(0)
            }
        }
    }
}
