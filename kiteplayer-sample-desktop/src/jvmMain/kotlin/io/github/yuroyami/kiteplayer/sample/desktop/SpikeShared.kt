package io.github.yuroyami.kiteplayer.sample.desktop

import java.awt.Canvas
import java.awt.Graphics
import java.awt.image.BufferStrategy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * What both interop spikes share: the self-painting canvas and the two frame counters.
 *
 * The two spikes differ only in who hosts what (Compose hosting a `SwingPanel`, or Swing hosting
 * a `ComposePanel` in a layer above the canvas). Everything being measured is the same, so it is
 * defined once here rather than copied, which also means the two results are comparable.
 */

/** Frames the canvas thread actually presented. */
val sharedCanvasFrames = AtomicLong(0)

/** Frames Compose's own clock delivered. */
val sharedComposeFrames = AtomicLong(0)

/** Set by the Compose overlay's click handler, read by the probe. */
val sharedOverlayClicked = AtomicBoolean(false)

/** Whether the burner is currently choking the Compose frame clock. */
val sharedBurning = AtomicBoolean(false)

/**
 * A plain heavyweight canvas that paints itself, on its own thread, at roughly 60 Hz.
 *
 * `BufferStrategy` is created lazily because it needs a displayable peer, which only exists once
 * the canvas has been added to a window that is showing.
 */
class SpikeCanvasShared : Canvas() {
    fun startPainting() {
        val painter = Thread({
            var strategy: BufferStrategy? = null
            while (!Thread.currentThread().isInterrupted) {
                val frameStart = System.nanoTime()
                if (strategy == null && isDisplayable) {
                    createBufferStrategy(2)
                    strategy = bufferStrategy
                }
                val active = strategy
                if (active != null && width > 0 && height > 0) {
                    do {
                        do {
                            val g: Graphics = active.drawGraphics
                            // Pure red, so the pixel probe cannot confuse it with the overlay.
                            g.color = java.awt.Color(255, 0, 0)
                            g.fillRect(0, 0, width, height)
                            g.color = java.awt.Color.WHITE
                            g.drawString("canvas frames ${sharedCanvasFrames.get()}", 16, 24)
                            g.dispose()
                        } while (active.contentsRestored())
                        active.show()
                    } while (active.contentsLost())
                    sharedCanvasFrames.incrementAndGet()
                }
                val elapsed = System.nanoTime() - frameStart
                val remaining = 16_666_666L - elapsed
                if (remaining > 0) {
                    Thread.sleep(remaining / 1_000_000L, (remaining % 1_000_000L).toInt())
                }
            }
        }, "spike-canvas-painter")
        painter.isDaemon = true
        painter.start()
    }
}
