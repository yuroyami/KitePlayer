package io.github.yuroyami.kiteplayer.sample.android

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.yuroyami.kiteplayer.compose.KiteVideo
import io.github.yuroyami.kiteplayer.compose.rememberKiteVideoState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale

/** Video drawn as Compose content, rather than a native View hosted inside Compose. */
internal class ComposeVideoActivity : ComponentActivity() {
    private lateinit var controller: SampleController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = SampleController(applicationContext)
        val player = controller.player

        setContent {
            // The Window overload enables the API 31+ MediaCodec/HardwareBuffer path. The no-arg
            // overload is deliberately software-only because it cannot prove GPU completion.
            val videoState = rememberKiteVideoState(window)
            var outputStats by remember {
                mutableStateOf("presented=0  superseded=0  failed=0  cpuSamples=0")
            }

            SampleComposeScreen(
                title = getString(R.string.demo_compose_native),
                detail = getString(R.string.demo_compose_native_detail),
                controller = controller,
                outputStats = outputStats,
                video = {
                    KiteVideo(
                        state = videoState,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(20.dp)),
                    )
                },
            )

            LaunchedEffect(player, videoState) {
                // Let KiteVideo bind its hardware-accelerated View to this exact Window first.
                withFrameNanos { }
                controller.openNormally(videoState.renderer)
            }
            LaunchedEffect(videoState) {
                var previousPresented = videoState.presentedFrames
                var previousSampleNanos = SystemClock.elapsedRealtimeNanos()
                while (isActive) {
                    delay(1_000)
                    val sampleNanos = SystemClock.elapsedRealtimeNanos()
                    val presented = videoState.presentedFrames
                    val elapsedNanos = (sampleNanos - previousSampleNanos).coerceAtLeast(1L)
                    val rgbaPublishFps = (presented - previousPresented).coerceAtLeast(0L) *
                        1_000_000_000.0 / elapsedNanos
                    previousPresented = presented
                    previousSampleNanos = sampleNanos

                    val cost = videoState.frameCost
                    val stats = player.stats.value
                    outputStats = String.format(
                        Locale.US,
                        "RGBA fps=%.1f published=%d superseded=%d failed=%d cpuSamples=%d",
                        rgbaPublishFps,
                        presented,
                        videoState.supersededFrames,
                        videoState.failedFrames,
                        cost.samples,
                    )
                    Log.i(
                        "KitePerf",
                        String.format(
                            Locale.US,
                            "path=compose rgbaPublishFps=%.1f decodedFps=%.1f decoded=%d " +
                                "submitted=%d headless=%d droppedLate=%d droppedDecode=%d " +
                                "rendererPresented=%d rendererSuperseded=%d rendererFailed=%d " +
                                "audioUnderruns=%d hardware=%s cpuSamples=%d",
                            rgbaPublishFps,
                            stats.videoDecodeFps,
                            stats.decodedVideoFrames,
                            stats.submittedFrames,
                            stats.headlessFrames,
                            stats.droppedFramesLate,
                            stats.droppedFramesDecode,
                            presented,
                            videoState.supersededFrames,
                            videoState.failedFrames,
                            stats.audioUnderruns,
                            stats.hardwareDecode,
                            cost.samples,
                        ),
                    )
                }
            }
        }
    }

    override fun onPause() {
        controller.onBackground()
        super.onPause()
    }

    override fun onDestroy() {
        // Composition owns the renderer; the Activity/controller owns the player.
        try {
            super.onDestroy()
        } finally {
            controller.shutdown()
        }
    }
}
