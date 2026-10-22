package io.github.yuroyami.kiteplayer.sample.desktop

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.availability
import io.github.yuroyami.kiteplayer.compose.rememberKitePlayer
import io.github.yuroyami.kiteplayer.isAvailable
import io.github.yuroyami.kiteplayer.LoopMode
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.compose.KitePlayerControls
import io.github.yuroyami.kiteplayer.compose.KiteVideo
import io.github.yuroyami.kiteplayer.compose.KiteVideoState
import io.github.yuroyami.kiteplayer.compose.rememberKiteVideoState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

private val Ink = Color(0xFF0B0B10)
private val Panel = Color(0xFF16161F)
private val Accent = Color(0xFF9C7BFF)
private val Muted = Color(0xFF9A9AAE)

/** The whole sample: a player, a KiteVideo under the library's default controls, and the measurement driver. */
@Composable
internal fun DesktopSample(options: SampleOptions, onMeasurementDone: () -> Unit) {
    if (!KitePlayer.isAvailable) {
        Refusal("KitePlayer is unavailable on this JVM: ${KitePlayer.availability}")
        return
    }
    val player = rememberKitePlayer()

    val video = rememberKiteVideoState()
    var modifiersOn by remember { mutableStateOf(!options.measure) }
    var opened by remember { mutableStateOf(false) }
    val drawCost = remember { DrawCost() }

    LaunchedEffect(player, video) {
        // Let KiteVideo lay out once, so the renderer knows its viewport before the first frame.
        withFrameNanos { }
        player.attachRenderer(video.renderer)
        // The clip is ten seconds long; a measurement phase needs more frames than that.
        player.setLoop(LoopMode.One)
        if (options.measure) player.setMuted(true)
        runCatching { player.open(MediaItem(uri = options.media)) }
            .onFailure { failure -> System.err.println("open failed: ${failure.message}") }
        opened = true
        player.play()
    }

    Column(Modifier.fillMaxSize().background(Ink)) {
        Header(options)
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            VideoStage(video, modifiersOn, drawCost)
            // A measurement counts what the video costs to draw, so nothing is drawn over it then.
            if (opened && !options.measure) KitePlayerControls(player, Modifier.matchParentSize())
        }
        Panel(
            player = player,
            video = video,
            modifiersOn = modifiersOn,
            onToggleModifiers = { modifiersOn = !modifiersOn },
        )
    }

    if (options.measure) {
        LaunchedEffect(player, video) {
            val report = runMeasurement(
                options = options,
                player = player,
                video = video,
                drawCost = drawCost,
                setModifiers = { modifiersOn = it },
            )
            print(report)
            options.report?.let { file ->
                withContext(Dispatchers.IO) {
                    file.parentFile?.mkdirs()
                    file.writeText(report)
                }
            }
            onMeasurementDone()
        }
    }
}

/**
 * The flagship Compose claim, made visible: the SAME composable, with and without ordinary
 * Compose modifiers on the video itself. No platform view can be clipped, faded, rotated and
 * scaled like this, because its pixels never enter the Compose pipeline.
 */
@Composable
private fun VideoStage(video: KiteVideoState, modifiersOn: Boolean, drawCost: DrawCost) {
    val spin = rememberInfiniteSpin()
    val pulse = rememberInfinitePulse()
    // Outside the modifier chain: the whole decorated node, layer included.
    val outerTimer = Modifier.drawWithContent {
        val started = System.nanoTime()
        drawContent()
        drawCost.outer.record(System.nanoTime() - started)
    }
    // Inside it: KiteVideo's own draw and nothing else, so the two counts can be compared.
    val innerTimer = Modifier.drawWithContent {
        val started = System.nanoTime()
        drawContent()
        drawCost.inner.record(System.nanoTime() - started)
    }
    val decorated = if (!modifiersOn) {
        Modifier
    } else {
        Modifier
            // Read inside the layer block, never in composition: an animated video frame must
            // still invalidate drawing alone.
            .graphicsLayer {
                rotationZ = spin.value
                scaleX = pulse.value
                scaleY = pulse.value
                alpha = 0.92f
            }
            .clip(RoundedCornerShape(28.dp))
    }
    KiteVideo(
        state = video,
        modifier = Modifier.fillMaxSize().then(outerTimer).then(decorated).then(innerTimer),
    )
}

@Composable
private fun rememberInfiniteSpin(): State<Float> =
    rememberInfiniteTransition(label = "spin").animateFloat(
        initialValue = -6f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(tween(5200, easing = LinearEasing), RepeatMode.Reverse),
        label = "spin",
    )

@Composable
private fun rememberInfinitePulse(): State<Float> =
    rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.80f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(3000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )

@Composable
private fun Header(options: SampleOptions) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
        BasicText(
            text = "KiteVideo on Compose Desktop",
            style = TextStyle(color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold),
        )
        BasicText(
            text = options.media,
            style = TextStyle(color = Muted, fontSize = 12.sp),
        )
    }
}

@Composable
private fun Refusal(message: String) {
    Box(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) {
        BasicText(message, style = TextStyle(color = Color(0xFFFF8A8A), fontSize = 14.sp))
    }
}

/** The modifier toggle and the counters. Playback itself is the library's `KitePlayerControls`. */
@Composable
private fun Panel(
    player: KitePlayer,
    video: KiteVideoState,
    modifiersOn: Boolean,
    onToggleModifiers: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().background(Panel).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Button(
            label = if (modifiersOn) "Compose modifiers: on" else "Compose modifiers: off",
            enabled = true,
            highlighted = modifiersOn,
            onClick = onToggleModifiers,
        )
        Telemetry(player, video)
    }
}

/** Its own composable, so the counters ticking never recompose the video's parent. */
@Composable
private fun Telemetry(player: KitePlayer, video: KiteVideoState) {
    val stats by player.stats.collectAsState()
    var line by remember { mutableStateOf("") }
    LaunchedEffect(video) {
        while (true) {
            // Four times a second, not once per frame: this line's own state write would
            // otherwise invalidate the whole window on every vsync.
            delay(250)
            val cost = video.frameCost
            line = String.format(
                Locale.US,
                "published=%d  superseded=%d  failed=%d  upload mean=%.2f ms worst=%.2f ms",
                video.presentedFrames,
                video.supersededFrames,
                video.failedFrames,
                cost.averageNanos / 1e6,
                cost.worstNanos / 1e6,
            )
        }
    }
    BasicText(
        text = String.format(
            Locale.US,
            "%s  decode=%.1f fps  droppedLate=%d  droppedDecode=%d  %s",
            line,
            stats.videoDecodeFps,
            stats.droppedFramesLate,
            stats.droppedFramesDecode,
            stats.hardwareDecode,
        ),
        style = TextStyle(color = Muted, fontSize = 12.sp),
    )
}

@Composable
private fun Button(
    label: String,
    enabled: Boolean,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (highlighted) Accent.copy(alpha = 0.22f) else Color(0xFF23232F))
            .border(1.dp, if (highlighted) Accent else Color(0xFF33333F), RoundedCornerShape(9.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        BasicText(
            text = label,
            style = TextStyle(
                color = if (enabled) Color.White else Muted,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}
