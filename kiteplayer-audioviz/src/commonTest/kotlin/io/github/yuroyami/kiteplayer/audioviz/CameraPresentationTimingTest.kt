package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
import io.github.yuroyami.kiteplayer.audioviz.viz.CameraRig
import io.github.yuroyami.kiteplayer.audioviz.viz.VizFuture
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import kotlin.test.Test
import kotlin.test.assertTrue

/** Ideal display-clock simulation; excludes detection, worker, audio-output and display delay. */
class CameraPresentationTimingTest {
    @Test
    fun cameraImpactFitsTheDeclaredLookaheadAcrossRefreshRatesAndEventOffsets() {
        val errors = mutableListOf<String>()
        for (fps in listOf(60, 90, 120, 240)) for (eventMicros in listOf(1_011_000L, 1_037_000L, 1_074_000L)) {
            val flat = Camera2D(wander = 0f, roll = 0f, shake = 0f, cuts = false)
            val flying = CameraRig(topSpeed = 10f, cuts = false)
            val event = AudioEvent(Generation.Initial, 0L, 0L,
                AudioDetection(AudioEventKind.LowTransient, eventMicros, eventMicros + 10_000L, 0.7f, 0.8f, 0.2f))
            var delivered = false
            val maxima = FloatArray(2) { -Float.MAX_VALUE }
            val peakAt = LongArray(2)
            for (index in 0..fps * 2) {
                val at = index * 1_000_000L / fps
                val due = !delivered && at >= eventMicros
                if (due) delivered = true
                val events = if (due) arrayOf(DeliveredAudioEvent(event, 0L)) else emptyArray()
                // Audible, or the flight would stand still and the surge would have no speed to peak.
                val frame = SpectrumFrame(at, FloatArray(4), FloatArray(4), FloatArray(4),
                    0.5f, 0f, 0f, 0f, 0f, 0f, events = AudioEventDelivery(Generation.Initial, 0L, at, events))
                val future = object : VizFuture {
                    override fun at(secondsAhead: Float): SpectrumFrame? = null
                    override val nextOnsetSeconds: Float = -1f
                    override fun nextEvent(kind: AudioEventKind): UpcomingAudioEvent? {
                        val seconds = (eventMicros - at) / 1_000_000f
                        return if (kind == AudioEventKind.LowTransient && seconds > 0f && seconds <= 0.1f)
                            UpcomingAudioEvent(event, seconds) else null
                    }
                }
                val state = VizRenderState(frame, index.toFloat() / fps, 1f / fps, VizPalette.Classic, future = future)
                flat.advance(state)
                val speed = flying.advance(state) * fps
                val values = floatArrayOf(flat.zoom, speed)
                for (camera in 0..1) if (values[camera] > maxima[camera]) {
                    maxima[camera] = values[camera]
                    peakAt[camera] = at
                }
            }
            for (camera in 0..1) {
                val error = peakAt[camera] - eventMicros
                val label = "camera=$camera fps=$fps eventUs=$eventMicros peakErrorUs=$error"
                println(label)
                if (error !in -15_000L..30_000L) errors += label
            }
        }
        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
    }
}
