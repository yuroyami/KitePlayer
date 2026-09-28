package io.github.yuroyami.kiteplayer.compose

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.NeedsPushedMedia
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.mobile.mobileBackends
import io.github.yuroyami.kiteplayer.output.AndroidOutputBackend
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioRenderCallback
import io.github.yuroyami.kiteplayer.spi.AudioSink
import io.github.yuroyami.kiteplayer.spi.AudioSinkFactory
import io.github.yuroyami.kiteplayer.spi.OutputBackend
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A video queue on the Compose GPU path, whose renderer decodes with a MediaCodec decoder of its
 * own. The preload leaves that decoder for the swap, so the sound follows on one AudioTrack and
 * the second item's pictures arrive once the new decoder starts (#304). See
 * `docs/gapless-queue.md`.
 */
@NeedsPushedMedia
internal class GaplessVideoQueueDeviceTest {

    /** Every call the engine made on the device. Atomic, because the engine calls from its own threads. */
    private class Counts {
        val opens = AtomicInteger()
        val stops = AtomicInteger()
        val pauses = AtomicInteger()
        val drains = AtomicInteger()
    }

    private class CountingSink(private val real: AudioSink, private val counts: Counts) : AudioSink by real {
        override suspend fun open(request: AudioFormat, render: AudioRenderCallback): AudioFormat {
            counts.opens.incrementAndGet()
            return real.open(request, render)
        }

        override suspend fun stop() {
            counts.stops.incrementAndGet()
            real.stop()
        }

        override suspend fun setPaused(paused: Boolean): Boolean {
            if (paused) counts.pauses.incrementAndGet()
            return real.setPaused(paused)
        }

        override suspend fun drain() {
            counts.drains.incrementAndGet()
            real.drain()
        }
    }

    private class CountingOutput(private val real: OutputBackend, counts: Counts) : OutputBackend by real {
        override val audioSink: AudioSinkFactory = object : AudioSinkFactory {
            override val name: String = "counting(${real.audioSink.name})"
            override suspend fun create(): AudioSink = CountingSink(real.audioSink.create(), counts)
        }
    }

    @Test
    fun aVideoQueueOnTheGpuPathKeepsOneAudioTrackAndShowsTheSecondItem() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val clip = listOfNotNull(
            context.getExternalFilesDir(null)?.resolve("testmedia/sync1080p30.mp4"),
            context.filesDir.resolve("testmedia/sync1080p30.mp4"),
        ).firstOrNull(File::isFile)
        checkNotNull(clip) { "fixture not found; push testmedia/sync1080p30.mp4 first" }

        val scenario = ActivityScenario.launch(KiteVideoTestActivity::class.java)
        try {
            lateinit var state: KiteVideoState
            lateinit var consumerReady: java.util.concurrent.CountDownLatch
            scenario.onActivity { activity ->
                state = activity.videoState
                consumerReady = activity.composeConsumerReady
            }
            check(consumerReady.await(30, TimeUnit.SECONDS)) { "KiteVideo did not bind its Window consumer" }

            val counts = Counts()
            val base = mobileBackends()
            val player = KitePlayer.create(
                PlayerConfig(backends = base.copy(output = CountingOutput(base.output ?: AndroidOutputBackend, counts))),
            )
            try {
                runBlocking {
                    player.attachRenderer(state.renderer)
                    val item = MediaItem(clip.absolutePath)
                    withTimeout(60_000) { player.openQueue(listOf(item, item)) }
                    player.play()
                    // Eleven seconds of playback, with room for the emulator's graphics host, which
                    // freezes the whole system for 15 to 17 seconds now and then (#301).
                    waitFor(90.seconds) { player.state.value.queueIndex == 1 && player.position() >= 1.seconds }
                    val submittedAtJoin = player.stats.value.submittedFrames
                    delay(2_000)
                    val stats = player.stats.value
                    val warnings = player.warningHistory().map { it.warning }
                    Log.i(TAG, "submitted=${stats.submittedFrames} atJoin=$submittedAtJoin underruns=${stats.audioUnderruns} warnings=$warnings")

                    assertEquals(PlaybackStatus.Playing, player.state.value.status, "the second item plays")
                    assertEquals(emptyList(), warnings.filterIsInstance<PlaybackWarning.GaplessFallback>(), "no fallback")
                    assertEquals(1, counts.opens.get(), "one AudioTrack for both items")
                    assertEquals(0, counts.stops.get(), "the AudioTrack never stopped between the items")
                    assertEquals(0, counts.pauses.get(), "the AudioTrack never paused between the items")
                    assertEquals(0, counts.drains.get(), "the AudioTrack never drained between the items")
                    assertTrue(
                        stats.submittedFrames > submittedAtJoin,
                        "the renderer took the second item's pictures from its new decoder: $submittedAtJoin then " +
                            "${stats.submittedFrames}",
                    )
                }
            } finally {
                runBlocking { withTimeout(15_000) { player.closeAndAwait() } }
                state.renderer.close()
            }
        } finally {
            scenario.close()
        }
    }

    private suspend fun waitFor(limit: Duration, condition: () -> Boolean) {
        val met = withTimeoutOrNull(limit) {
            while (!condition()) delay(10)
            true
        }
        assertNotNull(met, "the condition did not hold within $limit")
    }

    private companion object {
        const val TAG = "KiteGaplessVideo"
    }
}
