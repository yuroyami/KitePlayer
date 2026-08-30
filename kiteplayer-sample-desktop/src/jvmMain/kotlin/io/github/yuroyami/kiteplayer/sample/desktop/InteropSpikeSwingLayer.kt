package io.github.yuroyami.kiteplayer.sample.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
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
import javax.swing.JLayeredPane
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/**
 * The rescue attempt for the interop spike, and a different architecture rather than a different
 * flag.
 *
 * `InteropSpike` asks Compose to place its own content above a `SwingPanel`, which on macOS it
 * refuses to do in every configuration measured. This one never asks. It builds the window in
 * Swing, puts the heavyweight canvas in one layer of a `JLayeredPane` and a transparent
 * `ComposePanel` in a higher one, and lets SWING do the z-ordering, which is a job Swing has
 * always done. Compose is then just the thing painting the upper layer.
 *
 * Worth trying before concluding that a desktop native view needs JAWT and platform GPU code,
 * because if it works the whole tier-1 design survives with a different host.
 *
 * Run with the same measurement contract as its sibling:
 *
 *   java -Dcompose.swing.render.on.graphics=true -cp <cp> ...InteropSpikeSwingLayerKt
 */

private const val OVERLAY_SIZE = 220

@Volatile private var layerOverlayInWindow: Point? = null
@Volatile private var layerFrame: JFrame? = null

private fun layerOverlayCentre(): Point? {
    val inWindow = layerOverlayInWindow ?: return null
    val frame = layerFrame ?: return null
    if (!frame.isShowing) return null
    val base = frame.contentPane.takeIf { it.isShowing }?.locationOnScreen ?: return null
    return Point(base.x + inWindow.x, base.y + inWindow.y)
}

fun main() {
    val seconds = (System.getProperty("spike.seconds") ?: "12").toInt()
    val swingGraphics = System.getProperty("compose.swing.render.on.graphics") ?: "unset"
    val canvas = SpikeCanvasShared()

    // ComposePanel must be constructed on the AWT event thread; it refuses elsewhere.
    lateinit var composePanel: ComposePanel
    SwingUtilities.invokeAndWait { composePanel = ComposePanel() }
    composePanel.isOpaque = false
    composePanel.background = java.awt.Color(0, 0, 0, 0)
    composePanel.setContent {
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
        val density = LocalDensity.current.density
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(40.dp)
                    .size(OVERLAY_SIZE.dp)
                    .background(Color(0f, 1f, 0f))
                    .clickable { sharedOverlayClicked.set(true) }
                    .onGloballyPositioned { coords ->
                        // Compose pixels to AWT logical points, see the sibling spike.
                        val inWindow = coords.positionInWindow()
                        layerOverlayInWindow = Point(
                            ((inWindow.x + coords.size.width / 2f) / density).toInt(),
                            ((inWindow.y + coords.size.height / 2f) / density).toInt(),
                        )
                    },
            )
        }
    }

    SwingUtilities.invokeAndWait {
        val frame = JFrame("KitePlayer interop spike, Swing layered")
        val layers = JLayeredPane()
        layers.layout = null
        canvas.setBounds(0, 0, 900, 700)
        composePanel.setBounds(0, 0, 900, 700)
        // The canvas underneath, Compose above it. Swing's own layer ordering, not Compose's.
        layers.add(canvas, JLayeredPane.DEFAULT_LAYER)
        layers.add(composePanel, JLayeredPane.PALETTE_LAYER)
        layers.preferredSize = java.awt.Dimension(900, 700)
        frame.contentPane.add(layers)
        frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        frame.setSize(900, 700)
        frame.setLocation(120, 120)
        frame.isAlwaysOnTop = true
        frame.isVisible = true
        frame.toFront()
        layerFrame = frame
    }
    // The canvas is OURS, so if it swallows clicks that Compose should have had, it can hand
    // them on. macOS dispatches mouse input to the heavyweight component even when Compose
    // renders above it, which is documented; forwarding is the obvious answer and this measures
    // whether it actually reaches Compose.
    if (System.getProperty("spike.forward") == "true") {
        val forwarder = object : java.awt.event.MouseAdapter() {
            private fun relay(e: java.awt.event.MouseEvent) {
                val converted = SwingUtilities.convertMouseEvent(canvas, e, composePanel)
                composePanel.dispatchEvent(converted)
            }
            override fun mousePressed(e: java.awt.event.MouseEvent) = relay(e)
            override fun mouseReleased(e: java.awt.event.MouseEvent) = relay(e)
            override fun mouseClicked(e: java.awt.event.MouseEvent) = relay(e)
            override fun mouseMoved(e: java.awt.event.MouseEvent) = relay(e)
            override fun mouseEntered(e: java.awt.event.MouseEvent) = relay(e)
        }
        canvas.addMouseListener(forwarder)
        canvas.addMouseMotionListener(forwarder)
    }
    canvas.startPainting()

    var waited = 0
    while (layerOverlayCentre() == null && waited < 60_000) {
        Thread.sleep(250)
        waited += 250
    }
    Thread.sleep(1500)

    var zorder = "UNKNOWN"
    var click = "UNKNOWN"
    val centre = layerOverlayCentre()
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
    println("host                            = Swing JLayeredPane + ComposePanel")
    println("compose.swing.render.on.graphics= $swingGraphics")
    println("z-order (who owns the pixel)    = $zorder")
    println("click routing                   = $click")
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
