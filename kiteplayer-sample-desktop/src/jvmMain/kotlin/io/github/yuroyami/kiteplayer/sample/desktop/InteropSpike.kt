package io.github.yuroyami.kiteplayer.sample.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.DpSize
import androidx.compose.runtime.withFrameNanos
import java.awt.Point
import java.awt.Robot
import java.awt.event.InputEvent
import kotlin.system.exitProcess

/**
 * The Phase 1 stop-gate spike: can a heavyweight AWT canvas escape Compose's frame clock, and can
 * Compose still draw and receive clicks on top of it?
 *
 * It answers both by MEASUREMENT rather than by someone looking at the window. The canvas paints
 * pure red on its own thread through a `BufferStrategy`; one Compose box paints pure green over
 * it. A `Robot` then reads the pixel where they overlap, which says which one is on top, and
 * clicks that same point, which says whether Compose or the interop component received it. Those
 * are two different questions on macOS, where Compose documents that an interop view can catch
 * the mouse while visually beneath.
 *
 * Then it chokes the Compose frame clock deliberately and compares the two frame rates. That is
 * the whole premise of the desktop native view: if the canvas holds its rate while Compose
 * collapses, video stops caring about UI jank.
 *
 * Run it three ways, because on macOS blending alone is not enough (a `metalOrderHack` inside
 * Compose puts interop back on top unless the Compose layer is a `SkiaSwingLayer`):
 *
 *   java -cp <cp> ...InteropSpikeKt
 *   java -Dcompose.interop.blending=true -cp <cp> ...InteropSpikeKt
 *   java -Dcompose.interop.blending=true -Dcompose.swing.render.on.graphics=true -cp <cp> ...
 *
 * `-Dspike.seconds=N` sets the choked measurement window; it defaults to 12.
 */

private const val OVERLAY_SIZE_DP = 220

/**
 * Where the overlay sits INSIDE the window, plus the window itself.
 *
 * Deliberately not a screen position: layout runs before the window is showing, and asking an
 * unshown component for its screen location throws inside composition. The probe adds the
 * window's own origin later, when it is provably on screen.
 */
@Volatile private var overlayInWindow: Point? = null
@Volatile private var spikeWindow: java.awt.Window? = null

/** The overlay's centre on screen, or null while the window is not showing yet. */
private fun overlayCentreOnScreen(): Point? {
    val inWindow = overlayInWindow ?: return null
    val w = spikeWindow ?: return null
    if (!w.isShowing) return null
    val base = (w as? androidx.compose.ui.awt.ComposeWindow)?.contentPane?.takeIf { it.isShowing }
        ?.locationOnScreen ?: return null
    return Point(base.x + inWindow.x, base.y + inWindow.y)
}


/** The verdict of one configuration, printed in one machine-readable block. */
private fun report(
    blending: String,
    swingGraphics: String,
    zorder: String,
    click: String,
    canvasIdle: Double,
    composeIdle: Double,
    canvasBurn: Double,
    composeBurn: Double,
    seconds: Int,
) {
    println("=== INTEROP SPIKE RESULT ===")
    println("compose.interop.blending        = $blending")
    println("compose.swing.render.on.graphics= $swingGraphics")
    println("z-order (who owns the pixel)    = $zorder")
    println("click routing                   = $click")
    println("canvas fps  idle / choked       = ${"%.1f".format(canvasIdle)} / ${"%.1f".format(canvasBurn)}")
    println("compose fps idle / choked       = ${"%.1f".format(composeIdle)} / ${"%.1f".format(composeBurn)}")
    println("each arm measured over          = $seconds s")
    // Relative, not absolute: the canvas must keep its OWN rate while Compose loses its. An
    // absolute frame-rate bar would only measure how busy this machine happened to be.
    val canvasKept = if (canvasIdle > 0) canvasBurn / canvasIdle else 0.0
    val composeLost = if (composeIdle > 0) composeBurn / composeIdle else 1.0
    println("canvas kept                     = ${"%.0f".format(canvasKept * 100)}% of its idle rate")
    println("compose kept                    = ${"%.0f".format(composeLost * 100)}% of its idle rate")
    val decoupled = canvasKept >= 0.90 && composeLost <= 0.25
    println("DECOUPLING = ${if (decoupled) "PASS" else "FAIL"}")
    println("Z_ORDER    = $zorder")
    println("CLICK      = $click")
    println("=== END ===")
}

fun main() {
    val seconds = (System.getProperty("spike.seconds") ?: "12").toInt()
    val blending = System.getProperty("compose.interop.blending") ?: "unset"
    val swingGraphics = System.getProperty("compose.swing.render.on.graphics") ?: "unset"
    val canvas = SpikeCanvasShared()

    Thread({
        // WAIT for the window to exist rather than guessing how long Compose takes to start. A
        // fixed sleep here read NO_OVERLAY_POSITION on the first run for no reason but a cold JVM,
        // which is the same mistake this repository just fixed in test_ring_threads.
        var waited = 0
        while (overlayCentreOnScreen() == null && waited < 60_000) {
            Thread.sleep(250)
            waited += 250
        }
        // A little longer once it exists, so the first real frames are on the glass.
        Thread.sleep(1500)
        var zorder = "UNKNOWN"
        var click = "UNKNOWN"
        try {
            val robot = Robot()
            val centre = overlayCentreOnScreen()
            if (centre == null) {
                zorder = "NO_OVERLAY_POSITION_AFTER_60S"
            } else {
                val pixel = robot.getPixelColor(centre.x, centre.y)
                // Green means Compose won the pixel; red means the heavyweight canvas is on top.
                zorder = when {
                    pixel.green > 180 && pixel.red < 90 -> "COMPOSE_ABOVE"
                    pixel.red > 180 && pixel.green < 90 -> "CANVAS_ABOVE"
                    else -> "INCONCLUSIVE(rgb=${pixel.red},${pixel.green},${pixel.blue})"
                }
                sharedOverlayClicked.set(false)
                robot.mouseMove(centre.x, centre.y)
                Thread.sleep(400)
                robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
                Thread.sleep(120)
                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                Thread.sleep(900)
                click = if (sharedOverlayClicked.get()) "COMPOSE_RECEIVED" else "COMPOSE_MISSED"
            }

            // A CONTROL arm first, because "the canvas held 50 fps" means nothing without the
            // rate it reaches when nothing is choking Compose. The pair is the evidence: what
            // matters is whether the canvas keeps ITS OWN rate while Compose loses its.
            sharedCanvasFrames.set(0)
            sharedComposeFrames.set(0)
            Thread.sleep(seconds * 1000L)
            val canvasIdle = sharedCanvasFrames.get().toDouble() / seconds
            val composeIdle = sharedComposeFrames.get().toDouble() / seconds

            sharedCanvasFrames.set(0)
            sharedComposeFrames.set(0)
            sharedBurning.set(true)
            Thread.sleep(seconds * 1000L)
            val canvasBurn = sharedCanvasFrames.get().toDouble() / seconds
            val composeBurn = sharedComposeFrames.get().toDouble() / seconds
            sharedBurning.set(false)
            report(
                blending, swingGraphics, zorder, click,
                canvasIdle, composeIdle, canvasBurn, composeBurn, seconds,
            )
        } catch (t: Throwable) {
            println("=== INTEROP SPIKE RESULT ===")
            println("probe failed: ${t::class.simpleName}: ${t.message}")
            println("=== END ===")
        }
        exitProcess(0)
    }, "spike-probe").apply { isDaemon = true }.start()

    application {
        // A FIXED position and size, and always on top: the pixel probe reads absolute screen
        // coordinates, and the first run read the desktop behind the window (rgb 21,21,21)
        // because the window was not in front. A spike that measures the wallpaper measures
        // nothing.
        val state = rememberWindowState(
            position = WindowPosition(120.dp, 120.dp),
            size = DpSize(900.dp, 700.dp),
        )
        Window(
            onCloseRequest = ::exitApplication,
            state = state,
            alwaysOnTop = true,
            title = "KitePlayer interop spike",
        ) {
            LaunchedEffect(Unit) {
                window.toFront()
                window.requestFocus()
            }
            var clicks by mutableStateOf(0)
            val density = LocalDensity.current.density

            // The Compose frame clock, counted and optionally choked. A burner inside the frame
            // callback is what a heavy UI does to itself, which is the thing video must survive.
            LaunchedEffect(Unit) {
                while (true) {
                    withFrameNanos {
                        sharedComposeFrames.incrementAndGet()
                        if (sharedBurning.get()) {
                            val until = System.nanoTime() + 200_000_000L
                            @Suppress("ControlFlowWithEmptyBody")
                            while (System.nanoTime() < until) {
                            }
                        }
                    }
                }
            }

            Box(Modifier.fillMaxSize()) {
                // POSITIVE CONTROL: with -Dspike.control=true there is no interop component at
                // all, so the green box is unarguably on top. If the probe cannot read
                // COMPOSE_ABOVE even here, the probe is broken and every other verdict in this
                // spike is worthless. A stop-gate failure must not rest on an unchecked ruler.
                if (System.getProperty("spike.control") != "true") {
                    SwingPanel(
                        factory = { canvas.also { it.startPainting() } },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // Pure green, deliberately opaque, over the canvas. If blending is off this is
                // drawn UNDER the heavyweight component and the probe reads red instead.
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(40.dp)
                        .size(OVERLAY_SIZE_DP.dp)
                        .background(Color(0f, 1f, 0f))
                        .clickable {
                            clicks++
                            sharedOverlayClicked.set(true)
                        }
                        .onGloballyPositioned { coords ->
                            // Compose reports PIXELS; AWT's locationOnScreen is in logical
                            // points. On a Retina display those differ by the density, and
                            // mixing them put the probe on empty window background. The
                            // positive control caught it, which is what it is for.
                            val inWindow = coords.positionInWindow()
                            overlayInWindow = Point(
                                ((inWindow.x + coords.size.width / 2f) / density).toInt(),
                                ((inWindow.y + coords.size.height / 2f) / density).toInt(),
                            )
                            spikeWindow = window
                        },
                )
                // Kept so a human watching sees the same thing the probe measures.
                Box(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
                    androidx.compose.material.Text(
                        "clicks=$clicks",
                        color = Color.White,
                    )
                }
            }
        }
    }
}
