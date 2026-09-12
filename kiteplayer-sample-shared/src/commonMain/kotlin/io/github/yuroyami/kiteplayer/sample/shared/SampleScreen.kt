package io.github.yuroyami.kiteplayer.sample.shared

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.LoopMode
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.audioviz.AudioVizBrowser
import io.github.yuroyami.kiteplayer.audioviz.AudioVizSettings
import io.github.yuroyami.kiteplayer.audioviz.KiteAudioViz
import io.github.yuroyami.kiteplayer.audioviz.isAudioOnly
import io.github.yuroyami.kiteplayer.audioviz.rememberAudioVizState
import io.github.yuroyami.kiteplayer.compose.KitePlayerVideo
import io.github.yuroyami.kiteplayer.compose.KiteRenderPath
import kotlinx.coroutines.delay
import kotlin.time.Duration

/**
 * The sample's front screen: [media] playing on repeat, drawn by the audio visualiser when it has no
 * picture and shown as video when it has one. The drawing browser, the settings and the transport sit
 * over it, and the director chooses the drawings until someone picks one by hand. The file's
 * copyright tag shows as a credit. When the song file is missing, a note says so.
 *
 * [videoPath] is how media with a picture is drawn. The controls sit over it, so a platform whose
 * native view hides Compose content needs [KiteRenderPath.ComposeCanvas]. [extra] adds a host's own
 * buttons to the top bar.
 */
@Composable
fun SampleScreen(
    player: KitePlayer,
    media: SampleMedia,
    modifier: Modifier = Modifier,
    videoPath: KiteRenderPath = KiteRenderPath.Auto,
    extra: @Composable RowScope.() -> Unit = {},
) {
    val viz = rememberAudioVizState(player)
    val snapshot by player.state.collectAsState()
    var panel by remember { mutableStateOf(Panel.None) }
    // The director changes the drawing without telling Compose, so the name is read again twice a second.
    val showing by produceState(viz.showing.name, viz) {
        while (true) {
            value = viz.showing.name
            delay(500)
        }
    }

    LaunchedEffect(player, media) {
        viz.drawing = viz.catalogue.firstOrNull { it.name == FIRST_DRAWING } ?: viz.catalogue.first()
        viz.directed = true
        player.setLoop(LoopMode.One)
        player.open(MediaItem(media.path))
        player.play()
    }

    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black)) {
        val narrow = maxWidth < NARROW_WIDTH
        val bar = if (narrow) NARROW_BAR_HEIGHT else BAR_HEIGHT
        if (snapshot.isAudioOnly) {
            KiteAudioViz(viz, Modifier.fillMaxSize())
        } else {
            KitePlayerVideo(player = player, modifier = Modifier.fillMaxSize(), path = videoPath)
        }

        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            when (panel) {
                Panel.Browser -> AudioVizBrowser(
                    viz,
                    Modifier.fillMaxSize().padding(top = bar),
                    onPick = { panel = Panel.None },
                )
                Panel.Settings -> AudioVizSettings(
                    viz,
                    Modifier.align(Alignment.TopEnd).padding(top = bar, end = 12.dp, bottom = BAR_HEIGHT),
                )
                Panel.None -> Unit
            }
            if (media.songMissing && panel == Panel.None) SongHint(Modifier.align(Alignment.BottomCenter))
            val credit = snapshot.metadata["copyright"]
            if (credit != null && panel == Panel.None) Credit(credit, Modifier.align(Alignment.BottomStart))
            TopBar(
                title = snapshot.metadata["title"] ?: media.path.substringAfterLast('/').substringBeforeLast('.'),
                detail = listOfNotNull(snapshot.metadata["artist"], showing.takeIf { snapshot.isAudioOnly })
                    .joinToString("  ·  "),
                height = bar,
                narrow = narrow,
                panel = panel,
                directed = viz.directed,
                onPanel = { panel = if (panel == it) Panel.None else it },
                onDirector = { viz.directed = !viz.directed },
                extra = extra,
            )
            if (panel != Panel.Browser) Transport(player, snapshot.duration)
        }
    }
}

private enum class Panel { None, Browser, Settings }

/** The title and the buttons: one row when there is room, and on a narrow screen the buttons below the title. */
@Composable
private fun TopBar(
    title: String,
    detail: String,
    height: Dp,
    narrow: Boolean,
    panel: Panel,
    directed: Boolean,
    onPanel: (Panel) -> Unit,
    onDirector: () -> Unit,
    extra: @Composable RowScope.() -> Unit,
) {
    val buttons: @Composable RowScope.() -> Unit = {
        SampleButton("Drawings", on = panel == Panel.Browser) { onPanel(Panel.Browser) }
        SampleButton("Settings", on = panel == Panel.Settings) { onPanel(Panel.Settings) }
        SampleButton(if (directed) "Director on" else "Director off", on = directed, onClick = onDirector)
        extra()
    }
    Column(
        Modifier.fillMaxWidth().height(height).background(Color(0x99000000)).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        if (narrow) {
            Titles(title, detail)
            Row(
                Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                content = buttons,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Titles(title, detail, Modifier.weight(1f))
                buttons()
            }
        }
    }
}

@Composable
private fun Titles(title: String, detail: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Label(title, bold = true)
        if (detail.isNotEmpty()) Label(detail, small = true)
    }
}

/** Play or pause, the time, and a bar to seek with: tap or drag anywhere along it. */
@Composable
private fun BoxScope.Transport(player: KitePlayer, duration: Duration?) {
    val snapshot by player.state.collectAsState()
    val progress by player.progress.collectAsState()
    val playing = snapshot.status == PlaybackStatus.Playing
    val length = duration?.takeIf { it > Duration.ZERO }
    val seek by rememberUpdatedState<(Float) -> Unit> { fraction ->
        length?.let { player.seekLater(it * fraction.toDouble()) }
    }
    Row(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(BAR_HEIGHT)
            .background(Color(0x99000000)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SampleButton(if (playing) "Pause" else "Play", on = false) { if (playing) player.pause() else player.play() }
        Label(clock(progress.position) + " / " + (length?.let(::clock) ?: "--:--"))
        val fraction = if (length == null) 0f else (progress.position / length).toFloat().coerceIn(0f, 1f)
        Canvas(
            Modifier.weight(1f).height(24.dp)
                .pointerInput(length) { detectTapGestures { seek(it.x / size.width) } }
                .pointerInput(length) { detectHorizontalDragGestures { change, _ -> seek(change.position.x / size.width) } },
        ) {
            val y = size.height / 2
            drawLine(Color(0x55FFFFFF), Offset(0f, y), Offset(size.width, y), 4.dp.toPx(), StrokeCap.Round)
            drawLine(Color.White, Offset(0f, y), Offset(size.width * fraction, y), 4.dp.toPx(), StrokeCap.Round)
        }
    }
}

/** Shown while the clip plays because the song file is missing. */
@Composable
private fun SongHint(modifier: Modifier) {
    BasicText(
        "The song file is missing, so the test clip plays instead. Check kiteplayer.sample.song in the " +
            "root local.properties, then build again.",
        modifier.padding(start = 12.dp, end = 12.dp, bottom = BAR_HEIGHT + 12.dp)
            .clip(RoundedCornerShape(6.dp)).background(Color(0x99000000)).padding(horizontal = 12.dp, vertical = 8.dp),
        style = TextStyle(color = Color(0xFFE8EEF8), fontSize = 13.sp),
    )
}

/** The song's copyright tag, such as the licence a shared song comes under, above the transport. */
@Composable
private fun Credit(text: String, modifier: Modifier) {
    Label(text, modifier.padding(start = 12.dp, end = 12.dp, bottom = BAR_HEIGHT + 8.dp), small = true)
}

/** A rounded text button, filled while [on]. Hosts use it for their own buttons in the top bar. */
@Composable
fun SampleButton(text: String, on: Boolean = false, onClick: () -> Unit) {
    BasicText(
        text,
        Modifier.clip(RoundedCornerShape(6.dp)).background(if (on) Color(0xFFE8EEF8) else Color(0xFF2A3140))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        style = TextStyle(color = if (on) Color(0xFF0B0E14) else Color(0xFFE8EEF8), fontSize = 13.sp),
    )
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier, bold: Boolean = false, small: Boolean = false) {
    BasicText(
        text,
        modifier,
        style = TextStyle(
            color = if (small) Color(0xFFB6BECD) else Color(0xFFE8EEF8),
            fontSize = if (bold) 15.sp else if (small) 12.sp else 13.sp,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun clock(time: Duration): String {
    val seconds = time.inWholeSeconds.coerceAtLeast(0)
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

/** The drawing the sample opens on, before the director takes over. */
private const val FIRST_DRAWING = "Acid Tunnel"

private val BAR_HEIGHT = 52.dp

/** Below this width the title gets a line of its own, above the buttons. */
private val NARROW_WIDTH = 640.dp
private val NARROW_BAR_HEIGHT = 100.dp
