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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import java.awt.Point
import java.awt.Robot
import java.awt.event.InputEvent
import javax.swing.JFrame
import javax.swing.JWindow
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/**
 * The third architecture, and the one that attacks the real cause.
 *
 * The other two spikes lose the click for the same reason: on macOS the heavyweight canvas is a
 * native view, and native hit-testing hands the mouse to the topmost NATIVE view under the
 * cursor. What Compose painted over it afterwards does not enter into that decision, which is why
 * blending fixes the picture and not the input.
 *
 * A separate window is not subject to that at all. The controls live in a borderless child window
 * sitting above the video window, so they are the topmost native thing where they are, and the
 * mouse goes to them because the OS agrees they are on top rather than because Compose drew last.
 *
 * If this works, the desktop native view survives with an overlay-window design and no JAWT and
 * no platform GPU code.
 */

private const val OVERLAY_SIZE = 220

@Volatile private var ovOverlayCentre: Point? = null

/** A COUNTER, not a sticky flag: a flag that is true cannot say who set it or when. */
private val ovClicks = java.util.concurrent.atomic.AtomicInteger(0)

fun main() {
    val seconds = (System.getProperty("spike.seconds") ?: "12").toInt()
    val hold = System.getProperty("spike.hold") == "true"
    val canvas = SpikeCanvasShared()

    lateinit var composePanel: ComposePanel
    SwingUtilities.invokeAndWait { composePanel = ComposePanel() }
    composePanel.isOpaque = false
    composePanel.background = java.awt.Color(0, 0, 0, 0)
    composePanel.setContent {
        val density = LocalDensity.current.density
        var shown by remember { mutableStateOf(0) }
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
            androidx.compose.material.Text(
                "clicks=$shown",
                color = Color.White,
                fontSize = androidx.compose.ui.unit.TextUnit(30f, androidx.compose.ui.unit.TextUnitType.Sp),
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
            )
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(20.dp)
                    .size(OVERLAY_SIZE.dp)
                    .background(Color(0f, 1f, 0f))
                    .clickable {
                        sharedOverlayClicked.set(true)
                        shown = ovClicks.incrementAndGet()
                    }
                    .onGloballyPositioned { coords ->
                        val inPanel = coords.positionInWindow()
                        val panel = composePanel
                        if (panel.isShowing) {
                            val base = panel.locationOnScreen
                            ovOverlayCentre = Point(
                                base.x + ((inPanel.x + coords.size.width / 2f) / density).toInt(),
                                base.y + ((inPanel.y + coords.size.height / 2f) / density).toInt(),
                            )
                        }
                    },
            )
        }
    }

    SwingUtilities.invokeAndWait {
        val frame = JFrame("KitePlayer interop spike, overlay window")
        frame.contentPane.layout = null
        canvas.setBounds(0, 0, 900, 700)
        frame.contentPane.add(canvas)
        frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        frame.setSize(900, 700)
        frame.setLocation(120, 120)
        frame.isVisible = true
        frame.toFront()

        // The controls, in their own borderless window owned by the video window. Owned windows
        // stay above their owner and move with it, which is the behaviour a control overlay wants.
        val overlay = JWindow(frame)
        overlay.background = java.awt.Color(0, 0, 0, 0)
        overlay.contentPane.layout = null
        composePanel.setBounds(0, 0, 400, 320)
        overlay.contentPane.add(composePanel)
        val origin = frame.contentPane.locationOnScreen
        overlay.setBounds(origin.x, origin.y, 400, 320)
        overlay.isVisible = true
    }
    canvas.startPainting()

    if (hold) {
        // Reset, then report only CHANGES. The previous version printed a sticky flag once a
        // second, which said a click had happened at second one and could not say whose it was.
        sharedOverlayClicked.set(false)
        ovClicks.set(0)
        println("hold mode: window is open, click the green square")
        var last = 0
        while (true) {
            Thread.sleep(500)
            val now = ovClicks.get()
            if (now != last) {
                println("HUMAN CLICK $now REACHED COMPOSE")
                last = now
            }
        }
    }

    var waited = 0
    while (ovOverlayCentre == null && waited < 60_000) {
        Thread.sleep(250)
        waited += 250
    }
    Thread.sleep(1500)

    var zorder = "UNKNOWN"
    var click = "UNKNOWN"
    val centre = ovOverlayCentre
    if (centre == null) {
        zorder = "NO_OVERLAY_POSITION_AFTER_60S"
    } else {
        val robot = Robot()
        val pixel = robot.getPixelColor(centre.x, centre.y)
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

    val canvasKept = if (canvasIdle > 0) canvasBurn / canvasIdle else 0.0
    val composeKept = if (composeIdle > 0) composeBurn / composeIdle else 1.0
    println("=== INTEROP SPIKE RESULT ===")
    println("host                            = JFrame canvas + owned JWindow of Compose")
    println("canvas fps  idle / choked       = ${"%.1f".format(canvasIdle)} / ${"%.1f".format(canvasBurn)}")
    println("compose fps idle / choked       = ${"%.1f".format(composeIdle)} / ${"%.1f".format(composeBurn)}")
    println("canvas kept                     = ${"%.0f".format(canvasKept * 100)}% of its idle rate")
    println("compose kept                    = ${"%.0f".format(composeKept * 100)}% of its idle rate")
    println("DECOUPLING = ${if (canvasKept >= 0.90 && composeKept <= 0.25) "PASS" else "FAIL"}")
    println("Z_ORDER    = $zorder")
    println("CLICK      = $click")
    println("=== END ===")
    exitProcess(0)
}
