package io.github.yuroyami.kiteplayer.sample.shared

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
import io.github.yuroyami.kiteplayer.audioviz.SongMapStore
import io.github.yuroyami.kiteplayer.audioviz.isAudioOnly
import io.github.yuroyami.kiteplayer.audioviz.rememberAudioVizState
import io.github.yuroyami.kiteplayer.compose.KitePlayerVideo
import io.github.yuroyami.kiteplayer.compose.KiteRenderPath
import kotlinx.coroutines.delay
import kotlin.time.Duration

/**
 * The sample's front screen: one of [media]'s tracks playing on repeat, drawn by the audio
 * visualiser when it has no picture and shown as video when it has one. It opens on a track picked
 * at random, and the song buttons step through the rest.
 *
 * The drawing browser, the settings and the transport sit over it. The director chooses the
 * drawings until someone picks one by hand, with the arrows or by dragging across the picture. The
 * file's copyright tag shows as a credit. When no song file was found, a note says so.
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
    /** Where finished song maps are kept, so a song played before is mapped from its first note. */
    songMapStore: SongMapStore = SongMapStore.None,
    extra: @Composable RowScope.() -> Unit = {},
) {
    val viz = rememberAudioVizState(player, songMapStore = songMapStore)
    val snapshot by player.state.collectAsState()
    var panel by remember { mutableStateOf(Panel.None) }
    // A different song each launch, so repeated runs do not always show the same one first.
    var track by remember(media) { mutableStateOf(media.tracks.indices.random()) }
    // The director changes the drawing without telling Compose, so its name is read again twice a
    // second. A drawing picked by hand is Compose state already and shows at once.
    val directedName by produceState(viz.showing.name, viz) {
        while (true) {
            value = viz.showing.name
            delay(500)
        }
    }
    val showing = if (viz.directed) directedName else viz.showing.name

    /** Shows the drawing [delta] along the catalogue, and stops the director taking it back. */
    val stepDrawing = rememberUpdatedState<(Int) -> Unit> { delta ->
        val list = viz.catalogue
        val at = list.indexOfFirst { it.name == viz.showing.name }.coerceAtLeast(0)
        viz.directed = false
        viz.drawing = list[(at + delta).mod(list.size)]
    }.value

    /** Any drawing at all, for when you want to be surprised rather than to browse. */
    val randomDrawing: () -> Unit = {
        viz.directed = false
        viz.drawing = viz.catalogue.filter { it.name != viz.showing.name }.random()
    }

    val stepTrack: (Int) -> Unit = { delta -> track = (track + delta).mod(media.tracks.size) }

    LaunchedEffect(player, media) {
        viz.drawing = viz.catalogue.firstOrNull { it.name == FIRST_DRAWING } ?: viz.catalogue.first()
        viz.directed = true
        // Some songs offer the detector no section it will support, and Bad Cat is one of them, so
        // without this the director can sit on one drawing for a whole track.
        viz.director.maximumHoldSeconds = DIRECTOR_LONGEST_HOLD_SECONDS
        player.setLoop(LoopMode.One)
    }

    LaunchedEffect(player, media, track) {
        player.open(MediaItem(media.tracks[track].path))
        player.play()
    }

    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black)) {
        val narrow = maxWidth < NARROW_WIDTH
        // The bar wraps its buttons onto as many rows as the width needs, so nothing scrolls and
        // nothing is hidden. Everything below it is placed under whatever height that came to.
        var bar by remember { mutableStateOf(BAR_HEIGHT) }
        if (snapshot.isAudioOnly) {
            KiteAudioViz(viz, Modifier.fillMaxSize())
        } else {
            KitePlayerVideo(player = player, modifier = Modifier.fillMaxSize(), path = videoPath)
        }

        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            // Under everything else, so the bars keep their own taps: drag across the picture to
            // change the drawing without opening the browser.
            if (panel == Panel.None) {
                SwipeArea(
                    Modifier.fillMaxSize().padding(top = bar, bottom = BAR_HEIGHT),
                    onStep = stepDrawing,
                )
            }
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
                title = snapshot.metadata["title"] ?: media.tracks[track].label,
                detail = listOfNotNull(snapshot.metadata["artist"], showing.takeIf { snapshot.isAudioOnly })
                    .joinToString("  ·  "),
                narrow = narrow,
                panel = panel,
                directed = viz.directed,
                tracks = media.tracks.size,
                onPanel = { panel = if (panel == it) Panel.None else it },
                onDirector = { viz.directed = !viz.directed },
                onDrawing = stepDrawing,
                onRandom = randomDrawing,
                onTrack = stepTrack,
                onHeight = { bar = it },
                extra = extra,
            )
            if (panel != Panel.Browser) Transport(player, snapshot.duration)
        }
    }
}

private enum class Panel { None, Browser, Settings }

/**
 * A drag across the picture, left for the drawing after and right for the drawing before.
 *
 * It answers the whole drag rather than each step of it, so one long sweep moves one drawing. A
 * drag shorter than [SWIPE_MINIMUM] is somebody missing a button, not a swipe.
 */
@Composable
private fun SwipeArea(modifier: Modifier, onStep: (Int) -> Unit) {
    val step by rememberUpdatedState(onStep)
    Box(
        modifier.pointerInput(Unit) {
            var travelled = 0f
            detectHorizontalDragGestures(
                onDragStart = { travelled = 0f },
                onDragEnd = {
                    val minimum = SWIPE_MINIMUM.toPx()
                    if (travelled <= -minimum) step(1) else if (travelled >= minimum) step(-1)
                },
                onHorizontalDrag = { _, amount -> travelled += amount },
            )
        },
    )
}

/** The title and the buttons: one row when there is room, wrapping onto more rows when there is not. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TopBar(
    title: String,
    detail: String,
    narrow: Boolean,
    panel: Panel,
    directed: Boolean,
    tracks: Int,
    onPanel: (Panel) -> Unit,
    onDirector: () -> Unit,
    onDrawing: (Int) -> Unit,
    onRandom: () -> Unit,
    onTrack: (Int) -> Unit,
    onHeight: (Dp) -> Unit,
    extra: @Composable RowScope.() -> Unit,
) {
    val density = LocalDensity.current
    val buttons: @Composable () -> Unit = {
        // Most used first: the drawing before, the drawing after, and one at random.
        SampleButton(PREVIOUS) { onDrawing(-1) }
        SampleButton(NEXT) { onDrawing(1) }
        SampleButton("Random", onClick = onRandom)
        SampleButton("Drawings", on = panel == Panel.Browser) { onPanel(Panel.Browser) }
        SampleButton("Director", on = directed, onClick = onDirector)
        SampleButton("Settings", on = panel == Panel.Settings) { onPanel(Panel.Settings) }
        if (tracks > 1) {
            SampleButton("Song $PREVIOUS") { onTrack(-1) }
            SampleButton("Song $NEXT") { onTrack(1) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = extra)
    }
    Column(
        Modifier.fillMaxWidth().background(Color(0x99000000))
            .onSizeChanged { onHeight(with(density) { it.height.toDp() }) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (narrow) {
            Titles(title, detail)
            // Wrapping, never scrolling: on a narrow screen the buttons take a second row rather
            // than sliding out of reach.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                content = { buttons() },
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Titles(title, detail, Modifier.weight(1f))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    content = { buttons() },
                )
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

/** How long the sample lets the director sit on one drawing when the music offers no section. */
private const val DIRECTOR_LONGEST_HOLD_SECONDS = 30f

/** The drawing the sample opens on, before the director takes over. */
private const val FIRST_DRAWING = "Menger"

/** Single angle quotation marks, which every platform font here carries. */
private const val PREVIOUS = "\u2039"
private const val NEXT = "\u203A"

/** A drag shorter than this is a missed tap, not a swipe. */
private val SWIPE_MINIMUM = 48.dp

private val BAR_HEIGHT = 52.dp

/** Below this width the title gets a line of its own, above the buttons. */
private val NARROW_WIDTH = 640.dp
private val NARROW_BAR_HEIGHT = 100.dp
