package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.Backends
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.TrackChange
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.output.AndroidOutputBackend
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioRenderCallback
import io.github.yuroyami.kiteplayer.spi.AudioSink
import io.github.yuroyami.kiteplayer.spi.AudioSinkBuffer
import io.github.yuroyami.kiteplayer.spi.AudioSinkFactory
import io.github.yuroyami.kiteplayer.spi.OutputBackend
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The audible truth: multitrack.mkv's English audio is a 440 Hz tone and its Japanese audio is
 * 660 Hz. Tapping the PCM the engine renders into the sink and counting zero crossings tells
 * WHICH stream is actually being heard, which no snapshot assertion can.
 */
internal class TrackSwitchAudibleDeviceTest {

    /** Zero crossings and frames rendered since the last [reset], channel 0 only. */
    private class PcmProbe {
        val crossings = AtomicLong(0)
        val frames = AtomicLong(0)
        var lastSample: Float = 0f
        var sampleRate: Int = 0

        fun reset() {
            crossings.set(0)
            frames.set(0)
        }

        /** Estimated tone frequency over the measured window: crossings happen twice per cycle. */
        fun frequency(): Double {
            val f = frames.get()
            if (f == 0L || sampleRate == 0) return 0.0
            return crossings.get() * sampleRate / (2.0 * f)
        }
    }

    private val probe = PcmProbe()

    private inner class TapBuffer(private val real: AudioSinkBuffer) : AudioSinkBuffer by real {
        override fun writeInterleaved(
            source: FloatArray,
            sourceOffset: Int,
            destinationFrameOffset: Int,
            frames: Int,
        ) {
            real.writeInterleaved(source, sourceOffset, destinationFrameOffset, frames)
            val channels = real.format.channels
            var last = probe.lastSample
            var crossed = 0L
            for (i in 0 until frames) {
                val s = source[sourceOffset + i * channels]
                if (last * s < 0f && abs(s) > 1e-4f) crossed++
                last = s
            }
            probe.lastSample = last
            probe.crossings.addAndGet(crossed)
            probe.frames.addAndGet(frames.toLong())
        }
    }

    private inner class TapSink(private val real: AudioSink) : AudioSink by real {
        override suspend fun open(request: AudioFormat, render: AudioRenderCallback): AudioFormat {
            val accepted = real.open(request) { destination, frames, deadlineNanos ->
                render.onRender(TapBuffer(destination), frames, deadlineNanos)
            }
            probe.sampleRate = accepted.sampleRate
            return accepted
        }
    }

    private inner class TapOutputBackend : OutputBackend by AndroidOutputBackend {
        override val audioSink: AudioSinkFactory = object : AudioSinkFactory {
            override val name: String = "tap(${AndroidOutputBackend.audioSink.name})"
            override suspend fun create(): AudioSink = TapSink(AndroidOutputBackend.audioSink.create())
        }
    }

    @Test
    fun theSwitchedToAudioTrackIsTheOneActuallyHeard() = runBlocking {
        val mediaDir = formatMatrixMediaDir() ?: error("no media dir on this device")
        val player = KitePlayer.create(
            PlayerConfig(
                backends = Backends(
                    backend = KiteCodecMediaBackend(onWarning = { println("BACKEND WARN ${it.message}") }),
                    output = TapOutputBackend(),
                ),
            ),
        )
        try {
            withTimeout(30_000) { player.open(MediaItem("$mediaDir/multitrack.mkv")) }
            val tracks = player.state.value.tracks
            player.play()
            delay(1_000)
            probe.reset()
            delay(2_000)
            val before = probe.frequency()
            println("BEFORE SWITCH: ${before}Hz over ${probe.frames.get()} frames (selected=${tracks.selectedAudio})")

            val jpn = tracks.audio.first { it.language == "jpn" }
            val change = withTimeout(30_000) { player.selectTrack(TrackKind.Audio, jpn.id) }
            println("CHANGE: $change")
            assertTrue(change is TrackChange.Applied, "switch was $change")
            delay(1_500)
            probe.reset()
            delay(2_000)
            val after = probe.frequency()
            println("AFTER SWITCH: ${after}Hz over ${probe.frames.get()} frames (selected=${player.state.value.tracks.selectedAudio})")

            assertTrue(abs(before - 440.0) < 60.0, "English track should measure near 440 Hz, was $before")
            assertTrue(
                abs(after - 660.0) < 60.0,
                "after switching to Japanese the heard tone must be near 660 Hz, was $after " +
                    "(440 here means the OLD stream is still playing: the switch is silent-broken)",
            )
        } finally {
            player.closeAndAwait()
        }
    }
}
