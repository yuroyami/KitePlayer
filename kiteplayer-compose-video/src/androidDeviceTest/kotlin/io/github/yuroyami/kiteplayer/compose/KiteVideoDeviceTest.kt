package io.github.yuroyami.kiteplayer.compose

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yuroyami.kiteplayer.PlaybackStats
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.HwdecKind
import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteplayer.HwdecStatus
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.mobile.mobileBackends
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The measured KiteVideo run of the 17.4.6 rider (A3): real playback, hardware-decoded through
 * the renderer-coupled MediaCodec/OES bridge, presented through Compose's own pipeline under a
 * clip and a rotation. The KV4-tagged logcat lines name the device and the applied performance
 * profile. Emulator numbers remain provisional; the tighter physical profile is device evidence.
 *
 * Media may live in this package's external files directory or its internal files directory. The
 * latter lets a debuggable test APK receive the fixture through `run-as` without changing ADB's
 * privilege level, then drive the installed instrumentation directly with `am instrument`.
 */
internal class KiteVideoDeviceTest {

    @Test
    fun kiteVideoPlaysRealMediaUnderComposeModifiers() {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "the measured Compose GPU path requires API 31, found API ${Build.VERSION.SDK_INT}"
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.context
        val profile = PerformanceProfile.resolve(
            requested = InstrumentationRegistry.getArguments().getString(PERFORMANCE_PROFILE_ARGUMENT),
        )
        val candidates = listOfNotNull(
            context.getExternalFilesDir(null)?.resolve("testmedia/sync1080p30.mp4"),
            context.filesDir.resolve("testmedia/sync1080p30.mp4"),
        )
        val clip = candidates.firstOrNull(File::isFile)
        checkNotNull(clip) { "matrix media not found at ${candidates.joinToString()}. Push it first." }
        assertEquals(
            FIXTURE_SHA256,
            sha256(clip),
            "the performance fixture changed; update its declared profile before accepting results",
        )

        val scenario = ActivityScenario.launch(KiteVideoTestActivity::class.java)
        try {
            lateinit var state: KiteVideoState
            lateinit var consumerReady: java.util.concurrent.CountDownLatch
            scenario.onActivity { activity ->
                state = activity.videoState
                consumerReady = activity.composeConsumerReady
            }
            check(consumerReady.await(CONSUMER_READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "KiteVideo did not bind its Window consumer before playback"
            }

            val player = KitePlayer.create(
                PlayerConfig(
                    backends = mobileBackends(),
                    hardwareDecode = HwdecPolicy.Require,
                ),
            )
            var teardownNanos = 0L
            val run = try {
                runBlocking {
                    player.attachRenderer(state.renderer)
                    player.open(MediaItem(clip.absolutePath))
                    val mediaDurationNanos = checkNotNull(player.state.value.duration) {
                        "the fixture opened without a duration"
                    }.inWholeNanoseconds
                    val playStartedNanos = SystemClock.elapsedRealtimeNanos()
                    player.play()
                    val ended = withTimeout(90_000) {
                        player.state.first {
                            it.status == PlaybackStatus.Ended || it.status == PlaybackStatus.Failed
                        }
                    }
                    val playbackElapsedNanos = SystemClock.elapsedRealtimeNanos() - playStartedNanos
                    ended.error?.let { failure -> Log.i(TAG, "error=$failure") }
                    assertEquals(
                        PlaybackStatus.Ended,
                        ended.status,
                        "playback failed before the GPU proof drain: ${ended.error}",
                    )
                    val postDrainStartedNanos = SystemClock.elapsedRealtimeNanos()
                    val postDrain = awaitStablePostDrainSnapshot(player, state)
                    MeasuredRun(
                        stats = postDrain.stats,
                        terminal = ended.status,
                        mediaDurationNanos = mediaDurationNanos,
                        playbackElapsedNanos = playbackElapsedNanos,
                        postDrainElapsedNanos =
                            SystemClock.elapsedRealtimeNanos() - postDrainStartedNanos,
                        postDrainOutcomes = postDrain.outcomes,
                        gpuCompletions = postDrain.gpuCompletions,
                    )
                }
            } finally {
                val teardownStartedNanos = SystemClock.elapsedRealtimeNanos()
                try {
                    try {
                        runBlocking { withTimeout(15_000) { player.closeAndAwait() } }
                    } finally {
                        state.renderer.close()
                    }
                } finally {
                    teardownNanos = SystemClock.elapsedRealtimeNanos() - teardownStartedNanos
                }
            }
            val stats = run.stats
            val terminal = run.terminal
            val outcomes = rendererOutcomes(state)
            val cost = state.frameCost
            val durationSeconds = run.mediaDurationNanos / NANOS_PER_SECOND
            val elapsedSeconds = run.playbackElapsedNanos / NANOS_PER_SECOND
            val postDrainSeconds = run.postDrainElapsedNanos / NANOS_PER_SECOND
            val teardownSeconds = teardownNanos / NANOS_PER_SECOND
            val expectedFrames = EXPECTED_SOURCE_FRAMES.toDouble()
            val publishedCoverage = outcomes.presented / expectedFrames
            val gpuProvenCoverage = run.gpuCompletions.frames / expectedFrames
            val gpuProvenSpanSeconds = run.gpuCompletions.spanNanos / NANOS_PER_SECOND
            val gpuProvenSpanRatio = gpuProvenSpanSeconds / durationSeconds
            val gpuProvenDrawFps = if (
                run.gpuCompletions.frames > 1L && gpuProvenSpanSeconds > 0.0
            ) {
                (run.gpuCompletions.frames - 1L) / gpuProvenSpanSeconds
            } else {
                0.0
            }
            val gpuNativeRate = gpuProvenDrawFps / SOURCE_FPS
            val playbackRealtimeRate = durationSeconds / elapsedSeconds
            val droppedFrames = stats.droppedFramesLate + stats.droppedFramesDecode
            val droppedRatio = droppedFrames / expectedFrames
            val rendererSupersededRatio = outcomes.superseded / expectedFrames
            val absoluteAvDriftMillis =
                kotlin.math.abs(stats.avDrift.inWholeNanoseconds / NANOS_PER_MILLISECOND)
            val accountingDelta = outcomes.total - stats.submittedFrames

            Log.i(
                TAG,
                "profile=${profile.label} model=${Build.MANUFACTURER}/${Build.MODEL} " +
                    "device=${Build.DEVICE} api=${Build.VERSION.SDK_INT}",
            )
            Log.i(TAG, "mediaDurationSeconds=${format(durationSeconds)}")
            Log.i(TAG, "sourceFps=${format(SOURCE_FPS)}")
            Log.i(TAG, "expectedSourceFrames=${format(expectedFrames)}")
            Log.i(TAG, "playbackElapsedSeconds=${format(elapsedSeconds)}")
            Log.i(TAG, "postEndedProofDrainSeconds=${format(postDrainSeconds)}")
            Log.i(TAG, "playbackRealtimeRate=${format(playbackRealtimeRate)}")
            Log.i(TAG, "rgbaPublishedCoverage=${format(publishedCoverage)}")
            Log.i(TAG, "gpuProvenDrawFrames=${run.gpuCompletions.frames}")
            Log.i(TAG, "gpuProvenDrawSpanSeconds=${format(gpuProvenSpanSeconds)}")
            Log.i(TAG, "gpuProvenDrawSpanRatio=${format(gpuProvenSpanRatio)}")
            Log.i(TAG, "gpuProvenDrawFps=${format(gpuProvenDrawFps)}")
            Log.i(TAG, "gpuNativeRate=${format(gpuNativeRate)}")
            Log.i(TAG, "gpuProvenDrawCoverage=${format(gpuProvenCoverage)}")
            Log.i(TAG, "droppedRatio=${format(droppedRatio)}")
            Log.i(TAG, "rendererSupersededRatio=${format(rendererSupersededRatio)}")

            Log.i(TAG, "decodedFrames=${stats.decodedVideoFrames}")
            Log.i(TAG, "submittedFrames=${stats.submittedFrames}")
            Log.i(TAG, "headlessFrames=${stats.headlessFrames}")
            Log.i(TAG, "droppedFramesLate=${stats.droppedFramesLate}")
            Log.i(TAG, "droppedFramesDecode=${stats.droppedFramesDecode}")
            Log.i(TAG, "repeatedFrames=${stats.repeatedFrames}")
            Log.i(TAG, "rebuffers=${stats.rebuffers}")
            Log.i(TAG, "avDriftMillis=${format(stats.avDrift.inWholeNanoseconds / NANOS_PER_MILLISECOND)}")
            Log.i(TAG, "rendererPresentedFrames=${outcomes.presented}")
            Log.i(TAG, "rendererSupersededFrames=${outcomes.superseded}")
            Log.i(TAG, "rendererFailedFrames=${outcomes.failed}")
            Log.i(TAG, "rendererOutcomeFrames=${outcomes.total}")
            Log.i(TAG, "rendererAccountingDelta=$accountingDelta")
            Log.i(TAG, "preTeardownAccountingDelta=${run.postDrainOutcomes.total - stats.submittedFrames}")
            Log.i(TAG, "audioUnderruns=${stats.audioUnderruns}")
            Log.i(TAG, "hardwareDecode=${stats.hardwareDecode}")
            Log.i(TAG, "costSamples=${cost.samples}")
            Log.i(TAG, "costAverageNanos=${cost.averageNanos}")
            Log.i(TAG, "costWorstNanos=${cost.worstNanos}")
            Log.i(TAG, "teardownMillis=${format(teardownNanos / NANOS_PER_MILLISECOND)}")
            Log.i(TAG, "terminal=$terminal")

            assertEquals(PlaybackStatus.Ended, terminal, "playback must end, not fail")
            assertTrue(
                kotlin.math.abs(durationSeconds - EXPECTED_MEDIA_DURATION_SECONDS) <=
                    MEDIA_DURATION_TOLERANCE_SECONDS,
                "fixture duration changed from $EXPECTED_MEDIA_DURATION_SECONDS seconds to " +
                    format(durationSeconds),
            )
            assertEquals(
                EXPECTED_SOURCE_FRAMES,
                stats.decodedVideoFrames,
                "the fixed fixture must decode every source frame",
            )
            assertEquals(
                stats.decodedVideoFrames,
                stats.submittedFrames + stats.headlessFrames + stats.droppedFramesLate,
                "every decoded frame must be submitted, headless, or explicitly late-dropped",
            )
            assertEquals(
                0L,
                accountingDelta,
                "renderer outcomes must balance submissions after drain and teardown: " +
                    "${outcomes.total} outcomes vs ${stats.submittedFrames} submissions",
            )
            assertTrue(
                gpuProvenCoverage >= profile.minimumGpuProvenCoverage,
                "${profile.label} GPU-proved only " +
                    "${format(gpuProvenCoverage * 100.0)}% of the native timeline",
            )
            assertTrue(
                gpuNativeRate >= profile.minimumGpuNativeRate,
                "${profile.label} GPU-proved draw cadence was ${format(gpuProvenDrawFps)} fps, " +
                    "${format(gpuNativeRate * 100.0)}% of the source rate",
            )
            assertTrue(
                gpuNativeRate <= profile.maximumGpuNativeRate,
                "${profile.label} GPU-proved draw cadence ran implausibly fast at " +
                    "${format(gpuNativeRate * 100.0)}% of source rate",
            )
            assertTrue(
                gpuProvenSpanRatio in profile.minimumProofSpanRatio..profile.maximumProofSpanRatio,
                "${profile.label} GPU-proved draw span was " +
                    "${format(gpuProvenSpanRatio * 100.0)}% of media duration",
            )
            assertTrue(
                postDrainSeconds <= profile.maximumPostEndedProofDrainSeconds,
                "${profile.label} needed ${format(postDrainSeconds)} seconds after Ended to " +
                    "stabilize GPU proof and renderer outcomes",
            )
            assertTrue(
                teardownSeconds <= profile.maximumTeardownSeconds,
                "${profile.label} teardown took ${format(teardownSeconds)} seconds, above its " +
                    "${format(profile.maximumTeardownSeconds)} second bound",
            )
            assertTrue(
                playbackRealtimeRate in profile.minimumPlaybackRealtimeRate..profile.maximumPlaybackRealtimeRate,
                "${profile.label} playback elapsed at ${format(playbackRealtimeRate * 100.0)}% realtime",
            )
            assertTrue(
                droppedRatio <= profile.maximumDroppedRatio,
                "${profile.label} dropped ${format(droppedRatio * 100.0)}% of the native timeline",
            )
            assertEquals(0L, stats.headlessFrames, "playback ran without its Compose renderer")
            assertTrue(
                rendererSupersededRatio <= profile.maximumRendererSupersededRatio,
                "${profile.label} safely superseded " +
                    "${format(rendererSupersededRatio * 100.0)}% of submissions under GPU pressure",
            )
            assertEquals(0L, outcomes.failed, "the Compose video renderer failed frames")
            assertEquals(0L, stats.audioUnderruns, "audio underruns occurred during the measured run")
            assertEquals(0L, stats.repeatedFrames, "video frames repeated during the fixed-rate run")
            assertEquals(0L, stats.rebuffers, "the local fixed fixture rebuffered")
            assertTrue(
                absoluteAvDriftMillis <= profile.maximumAbsoluteAvDriftMillis,
                "${profile.label} ended with ${format(absoluteAvDriftMillis)} ms absolute A/V drift",
            )
            assertEquals(
                HwdecStatus.HardwareZeroCopy(HwdecKind.MediaCodec),
                stats.hardwareDecode,
                "KiteVideo did not select its renderer-coupled MediaCodec bridge",
            )
            assertEquals(0L, cost.samples, "hardware frames must not enter the CPU conversion path")
        } finally {
            runCatching { scenario.close() }
        }
    }

    /**
     * Ended is an engine state; ImageReader publication and GPU completion remain asynchronous.
     * Wait for a bounded, stable pre-teardown snapshot with every accepted submission classified.
     * The later close-vs-callback boundary still has to preserve that exact accounting.
     */
    private suspend fun awaitStablePostDrainSnapshot(
        player: KitePlayer,
        state: KiteVideoState,
    ): PostDrainSnapshot = withTimeout(POST_DRAIN_TIMEOUT_MILLIS) {
        var stableMatches = 0
        var previousSubmitted = -1L
        var previousOutcomes: RendererOutcomes? = null
        var previousGpuCompletions: KiteVideoGpuCompletionStats? = null
        while (true) {
            val stats = player.stats.value
            val outcomes = rendererOutcomes(state)
            val gpuCompletions = state.gpuCompletionStats
            val pendingOutcomes = stats.submittedFrames - outcomes.total
            stableMatches = if (
                stats.submittedFrames > 0L &&
                pendingOutcomes == 0L &&
                stats.submittedFrames == previousSubmitted &&
                outcomes == previousOutcomes &&
                gpuCompletions == previousGpuCompletions
            ) {
                stableMatches + 1
            } else {
                0
            }
            if (stableMatches >= POST_DRAIN_STABLE_POLLS) {
                return@withTimeout PostDrainSnapshot(stats, outcomes, gpuCompletions)
            }
            previousSubmitted = stats.submittedFrames
            previousOutcomes = outcomes
            previousGpuCompletions = gpuCompletions
            delay(POST_DRAIN_POLL_MILLIS)
        }
        @Suppress("UNREACHABLE_CODE")
        error("post-drain accounting loop exited")
    }

    private fun rendererOutcomes(state: KiteVideoState): RendererOutcomes = RendererOutcomes(
        presented = state.presentedFrames,
        superseded = state.supersededFrames,
        failed = state.failedFrames,
    )

    private fun format(value: Double): String = String.format(Locale.US, "%.3f", value)

    private data class MeasuredRun(
        val stats: PlaybackStats,
        val terminal: PlaybackStatus,
        val mediaDurationNanos: Long,
        val playbackElapsedNanos: Long,
        val postDrainElapsedNanos: Long,
        val postDrainOutcomes: RendererOutcomes,
        val gpuCompletions: KiteVideoGpuCompletionStats,
    )

    private data class PostDrainSnapshot(
        val stats: PlaybackStats,
        val outcomes: RendererOutcomes,
        val gpuCompletions: KiteVideoGpuCompletionStats,
    )

    private data class RendererOutcomes(
        val presented: Long,
        val superseded: Long,
        val failed: Long,
    ) {
        val total: Long get() = presented + superseded + failed
    }

    /**
     * Emulator numbers remain provisional, but the gate must still reject the former 205/300-frame
     * regression. Physical hardware requires near-native delivery; a per-device benchmark can add
     * tighter jank and power bounds after the device has a recorded baseline.
     */
    private enum class PerformanceProfile(
        val label: String,
        val minimumGpuProvenCoverage: Double,
        val minimumGpuNativeRate: Double,
        val maximumGpuNativeRate: Double,
        val minimumProofSpanRatio: Double,
        val maximumProofSpanRatio: Double,
        val maximumPostEndedProofDrainSeconds: Double,
        val maximumTeardownSeconds: Double,
        val minimumPlaybackRealtimeRate: Double,
        val maximumPlaybackRealtimeRate: Double,
        val maximumDroppedRatio: Double,
        val maximumRendererSupersededRatio: Double,
        val maximumAbsoluteAvDriftMillis: Double,
    ) {
        Emulator(
            label = "emulator-provisional",
            minimumGpuProvenCoverage = 0.90,
            minimumGpuNativeRate = 0.85,
            maximumGpuNativeRate = 1.10,
            minimumProofSpanRatio = 0.80,
            maximumProofSpanRatio = 1.20,
            maximumPostEndedProofDrainSeconds = 2.0,
            maximumTeardownSeconds = 2.0,
            minimumPlaybackRealtimeRate = 0.80,
            maximumPlaybackRealtimeRate = 1.20,
            maximumDroppedRatio = 0.10,
            maximumRendererSupersededRatio = 0.01,
            maximumAbsoluteAvDriftMillis = 100.0,
        ),
        Physical(
            label = "physical-device",
            minimumGpuProvenCoverage = 0.99,
            minimumGpuNativeRate = 0.95,
            maximumGpuNativeRate = 1.05,
            minimumProofSpanRatio = 0.95,
            maximumProofSpanRatio = 1.05,
            maximumPostEndedProofDrainSeconds = 1.0,
            maximumTeardownSeconds = 1.0,
            minimumPlaybackRealtimeRate = 0.95,
            maximumPlaybackRealtimeRate = 1.05,
            maximumDroppedRatio = 0.01,
            maximumRendererSupersededRatio = 0.005,
            maximumAbsoluteAvDriftMillis = 50.0,
        );

        companion object {
            fun resolve(requested: String?): PerformanceProfile = when (requested?.lowercase(Locale.US)) {
                null, "" -> if (isProbablyEmulator()) Emulator else Physical
                "emulator" -> Emulator
                "physical" -> Physical
                else -> error(
                    "$PERFORMANCE_PROFILE_ARGUMENT must be emulator or physical, was $requested",
                )
            }

            private fun isProbablyEmulator(): Boolean {
                val fingerprint = Build.FINGERPRINT.lowercase(Locale.US)
                val model = Build.MODEL.lowercase(Locale.US)
                val hardware = Build.HARDWARE.lowercase(Locale.US)
                val product = Build.PRODUCT.lowercase(Locale.US)
                return fingerprint.startsWith("generic") ||
                    "emulator" in fingerprint ||
                    "sdk_gphone" in model ||
                    "emulator" in model ||
                    "ranchu" in hardware ||
                    "goldfish" in hardware ||
                    "emulator" in product
            }
        }
    }

    private companion object {
        private const val TAG = "KV4"
        private const val PERFORMANCE_PROFILE_ARGUMENT = "kiteplayer.performanceProfile"
        private const val SOURCE_FPS = 30.0
        private const val EXPECTED_SOURCE_FRAMES = 300L
        private const val EXPECTED_MEDIA_DURATION_SECONDS = 10.0
        private const val MEDIA_DURATION_TOLERANCE_SECONDS = 0.050
        private const val FIXTURE_SHA256 =
            "c12d952878f43c488327a05e51ff8791f215c93c39a1422d37bfa02eec1911de"
        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private const val NANOS_PER_MILLISECOND = 1_000_000.0
        private const val POST_DRAIN_TIMEOUT_MILLIS = 5_000L
        private const val POST_DRAIN_POLL_MILLIS = 50L
        private const val POST_DRAIN_STABLE_POLLS = 10
        private const val CONSUMER_READY_TIMEOUT_SECONDS = 5L
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        val hash = digest.digest()
        val hexadecimal = "0123456789abcdef"
        return buildString(hash.size * 2) {
            hash.forEach { byte ->
                val unsigned = byte.toInt() and 0xff
                append(hexadecimal[unsigned ushr 4])
                append(hexadecimal[unsigned and 0x0f])
            }
        }
    }
}
