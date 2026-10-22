package io.github.yuroyami.kiteplayer.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.StreamThumbnail
import io.github.yuroyami.kiteplayer.TrackKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * Default controls for [player], drawn over its video: play and pause, previous and next for a
 * queue, the seek bar with its buffered ranges and the times, the volume, menus for the audio and
 * subtitle tracks, the quality and the speed, and full screen and picture-in-picture buttons when
 * [onFullScreen] and [onPictureInPicture] are given. Pass it as the controls of [KitePlayerVideo],
 * `KitePlayerVideo(player) { KitePlayerControls(player) }`, or place it over a video yourself; it
 * fills what it is given.
 *
 * It is made only from the state holders, [rememberTransportState], [rememberSeekBarState],
 * [rememberVolumeState], [rememberTrackMenuState], [rememberQualityMenuState],
 * [rememberSpeedMenuState] and [rememberControlsVisibility], so an application that wants its own
 * look builds it from the same parts.
 *
 * - [visibility] decides when the controls show. A tap on the picture shows or hides them, and they
 *   hide by themselves while the player plays. Pass your own to show them from elsewhere.
 * - [style] is how they look, and [labels] every word they show or a screen reader says.
 * - The play, previous and next buttons and the seek bar lay out left to right in a right-to-left
 *   layout too, because they stand for time, which does not turn round. Text and the other buttons
 *   follow the layout direction.
 * - Every control is reached by Tab, the arrow keys and a D-pad. Space and the media play-pause key
 *   play or pause from anywhere inside, the left and right arrows move the focused seek bar by ten
 *   seconds, and Escape closes a menu. The first key while the controls are hidden only shows them.
 * - While the controls show, subtitles move up above them, and settle back when they hide, unless
 *   the application set the subtitle position itself in between.
 * - On a touch screen, a sideways drag over the picture scrubs, as the seek bar does. The width of
 *   the picture stands for the whole item, or for ten minutes of a longer one.
 *
 * Nothing is drawn while [player] is null.
 */
@Composable
public fun KitePlayerControls(
    player: KitePlayer?,
    modifier: Modifier = Modifier,
    visibility: ControlsVisibility = rememberControlsVisibility(player),
    style: KitePlayerControlsStyle = DefaultControlsStyle,
    labels: KitePlayerControlsLabels = DefaultControlsLabels,
    onFullScreen: (() -> Unit)? = null,
    onPictureInPicture: (() -> Unit)? = null,
) {
    if (player == null) return
    Controls(rememberControlsSource(player), modifier, visibility, style, labels, onFullScreen, onPictureInPicture)
}

internal enum class ControlsMenu { Audio, Subtitles, Quality, Speed }

/** How far the arrow keys move the seek bar. */
internal val SeekStep: Duration = 10.seconds

/** How far the arrow keys move the volume. */
private const val VolumeStep = 0.1f

/** The most of an item a drag across the whole picture stands for. */
private val PictureScrubSpan: Duration = 10.minutes

/** Below this width the volume shows as its button alone, as a phone has volume keys. */
private val WideControls: Dp = 480.dp

private val SeekBarHeight: Dp = 32.dp
private val ThumbRadius: Dp = 7.dp
private val PreviewWidth: Dp = 160.dp
private val MenuShape = RoundedCornerShape(8.dp)

@Composable
internal fun Controls(
    source: ControlsSource,
    modifier: Modifier,
    visibility: ControlsVisibility,
    style: KitePlayerControlsStyle,
    labels: KitePlayerControlsLabels,
    onFullScreen: (() -> Unit)?,
    onPictureInPicture: (() -> Unit)?,
) {
    val transport = rememberTransportState(source)
    val seekBar = rememberSeekBarState(source)
    val volume = rememberVolumeState(source)
    val audio = rememberTrackMenuState(source, TrackKind.Audio, labels)
    val subtitles = rememberTrackMenuState(source, TrackKind.Subtitle, labels)
    val quality = rememberQualityMenuState(source, labels)
    val speed = rememberSpeedMenuState(source, DefaultSpeeds, labels)

    var openMenu by remember { mutableStateOf<ControlsMenu?>(null) }
    var menuByKey by remember { mutableStateOf(false) }
    var returnFocusTo by remember { mutableStateOf<ControlsMenu?>(null) }
    val menuButtons = remember { ControlsMenu.entries.associateWith { FocusRequester() } }
    val pictureFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    var focusPlay by remember { mutableStateOf(false) }
    var focusInside by remember { mutableStateOf(false) }
    var height by remember { mutableIntStateOf(0) }
    var barHeight by remember { mutableIntStateOf(0) }
    val focusManager = LocalFocusManager.current

    fun closeMenu() {
        val closing = openMenu ?: return
        openMenu = null
        if (menuByKey) returnFocusTo = closing
    }

    // A scrub or an open menu holds the controls up; the timeout starts again when it lets go.
    val held = seekBar.scrubbing || openMenu != null
    SideEffect { visibility.held = held }
    LaunchedEffect(visibility.visible) {
        if (visibility.visible) return@LaunchedEffect
        openMenu = null
        // Focus left with the controls, and the picture keeps the keys working.
        if (focusInside) {
            focusInside = false
            pictureFocus.requestFocus()
        }
    }
    LaunchedEffect(returnFocusTo) {
        val menu = returnFocusTo ?: return@LaunchedEffect
        returnFocusTo = null
        menuButtons.getValue(menu).requestFocus()
    }

    val lift = remember(source) { SubtitleLift() }
    val wantedLift = if (visibility.visible && height > 0 && barHeight > 0) {
        (1f - barHeight.toFloat() / height).coerceIn(SubtitleLift.LOWEST, 1f)
    } else {
        null
    }
    LaunchedEffect(lift, wantedLift) {
        if (wantedLift != null) lift.raise(source, wantedLift) else lift.settle(source)
    }
    DisposableEffect(lift) { onDispose { lift.settle(source) } }

    BoxWithConstraints(
        modifier
            .onSizeChanged { height = it.height }
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Spacebar || event.key == Key.MediaPlayPause) {
                    if (event.type == KeyEventType.KeyDown) {
                        visibility.show()
                        transport.togglePlay()
                    }
                    return@onPreviewKeyEvent true
                }
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val wasHidden = !visibility.visible
                visibility.show()
                // The first press only brings the controls up, as on a television, and focuses play.
                if (wasHidden && event.key in WakeKeys) {
                    focusPlay = true
                    return@onPreviewKeyEvent true
                }
                false
            }
            .onKeyEvent { event -> event.type == KeyEventType.KeyDown && moveFocus(event, focusManager::moveFocus) },
    ) {
        val wide = maxWidth >= WideControls
        val tallest = maxHeight
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(seekBar, visibility) { pictureScrub(seekBar, visibility) }
                .focusRequester(pictureFocus)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) {
                    pictureFocus.requestFocus()
                    if (openMenu != null) closeMenu() else visibility.toggle()
                }
                .semantics { contentDescription = if (visibility.visible) labels.hideControls else labels.showControls },
        )
        if (visibility.visible) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(style.scrimColor)
                    .onFocusChanged { if (visibility.visible) focusInside = it.hasFocus },
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    TransportRow(source, transport, visibility, style, labels, playFocus, Modifier.align(Alignment.Center))
                }
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .padding(bottom = 4.dp)
                        .onSizeChanged { barHeight = it.height },
                ) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        if (seekBar.seekable) SeekBar(seekBar, visibility, style, labels)
                        TimeRow(seekBar, style, labels)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        ControlButton(
                            icon = if (volume.muted) ControlIcons.Muted else ControlIcons.Volume,
                            label = if (volume.muted) labels.unmute else labels.mute,
                            style = style,
                            visibility = visibility,
                            onClick = volume::toggleMute,
                        )
                        if (wide) VolumeSlider(volume, visibility, style, labels)
                        Spacer(Modifier.weight(1f))
                        MenuButton(ControlsMenu.Audio, audio.options.size > 1, ControlIcons.Audio, labels.audio, menuButtons, style, visibility) {
                            menuByKey = it
                            openMenu = if (openMenu == ControlsMenu.Audio) null else ControlsMenu.Audio
                        }
                        MenuButton(ControlsMenu.Subtitles, subtitles.options.isNotEmpty(), ControlIcons.Subtitles, labels.subtitles, menuButtons, style, visibility) {
                            menuByKey = it
                            openMenu = if (openMenu == ControlsMenu.Subtitles) null else ControlsMenu.Subtitles
                        }
                        // The automatic option and one variant leave nothing to choose.
                        MenuButton(ControlsMenu.Quality, quality.options.size > 2, ControlIcons.Quality, labels.quality, menuButtons, style, visibility) {
                            menuByKey = it
                            openMenu = if (openMenu == ControlsMenu.Quality) null else ControlsMenu.Quality
                        }
                        MenuButton(ControlsMenu.Speed, speed.options.isNotEmpty(), ControlIcons.Speed, labels.speed, menuButtons, style, visibility) {
                            menuByKey = it
                            openMenu = if (openMenu == ControlsMenu.Speed) null else ControlsMenu.Speed
                        }
                        onPictureInPicture?.let {
                            ControlButton(ControlIcons.PictureInPicture, labels.pictureInPicture, style, visibility, it)
                        }
                        onFullScreen?.let {
                            ControlButton(ControlIcons.FullScreen, labels.fullScreen, style, visibility, it)
                        }
                    }
                }
                val barDp = with(LocalDensity.current) { barHeight.toDp() }
                if (seekBar.scrubbing) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        ScrubPreview(
                            seekBar,
                            style,
                            labels,
                            Modifier.align(Alignment.BottomCenter).padding(horizontal = 8.dp).padding(bottom = barDp + 4.dp),
                        )
                    }
                }
                openMenu?.let { menu ->
                    val (title, state) = when (menu) {
                        ControlsMenu.Audio -> labels.audio to audio
                        ControlsMenu.Subtitles -> labels.subtitles to subtitles
                        ControlsMenu.Quality -> labels.quality to quality
                        ControlsMenu.Speed -> labels.speed to speed
                    }
                    MenuPanel(
                        title,
                        state,
                        style,
                        focusFirst = menuByKey,
                        onClose = ::closeMenu,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 8.dp, bottom = barDp + 4.dp)
                            .heightIn(max = (tallest - barDp - 16.dp).coerceAtLeast(96.dp)),
                    )
                }
                LaunchedEffect(focusPlay) {
                    if (!focusPlay) return@LaunchedEffect
                    focusPlay = false
                    playFocus.requestFocus()
                }
            }
        }
    }
}

/** The keys whose first press, while the controls are hidden, only shows them. */
private val WakeKeys = setOf(
    Key.DirectionLeft,
    Key.DirectionRight,
    Key.DirectionUp,
    Key.DirectionDown,
    Key.DirectionCenter,
    Key.Enter,
)

/** Moves focus for an arrow key no control took, the same on every platform. */
internal fun moveFocus(event: KeyEvent, move: (FocusDirection) -> Boolean): Boolean {
    val direction = when (event.key) {
        Key.DirectionLeft -> FocusDirection.Left
        Key.DirectionRight -> FocusDirection.Right
        Key.DirectionUp -> FocusDirection.Up
        Key.DirectionDown -> FocusDirection.Down
        else -> return false
    }
    return move(direction)
}

@Composable
private fun TransportRow(
    source: ControlsSource,
    transport: TransportState,
    visibility: ControlsVisibility,
    style: KitePlayerControlsStyle,
    labels: KitePlayerControlsLabels,
    playFocus: FocusRequester,
    modifier: Modifier,
) {
    val queue = source.snapshot.queue.size > 1
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
        if (queue) {
            ControlButton(ControlIcons.Previous, labels.previous, style, visibility, transport::previous, enabled = transport.canGoPrevious)
        }
        ControlButton(
            icon = if (transport.showsPlay) ControlIcons.Play else ControlIcons.Pause,
            label = if (transport.showsPlay) labels.play else labels.pause,
            style = style,
            visibility = visibility,
            onClick = transport::togglePlay,
            size = style.playButtonSize,
            iconSize = style.iconSize * 1.5f,
            modifier = Modifier.focusRequester(playFocus),
        )
        if (queue) {
            ControlButton(ControlIcons.Next, labels.next, style, visibility, transport::next, enabled = transport.canGoNext)
        }
    }
}

@Composable
private fun ControlButton(
    icon: ImageVector,
    label: String,
    style: KitePlayerControlsStyle,
    visibility: ControlsVisibility,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = style.buttonSize,
    iconSize: Dp = style.iconSize,
    interaction: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier
            .size(size)
            .border(2.dp, if (focused) style.focusColor else Color.Transparent, CircleShape)
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, enabled = enabled, role = Role.Button) {
                visibility.show()
                onClick()
            }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            icon,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            colorFilter = ColorFilter.tint(if (enabled) style.contentColor else style.disabledColor),
        )
    }
}

/** A button that opens a menu. [onOpen] learns whether a key pressed it, so the menu takes focus only then. */
@Composable
private fun MenuButton(
    menu: ControlsMenu,
    shown: Boolean,
    icon: ImageVector,
    label: String,
    focus: Map<ControlsMenu, FocusRequester>,
    style: KitePlayerControlsStyle,
    visibility: ControlsVisibility,
    onOpen: (byKey: Boolean) -> Unit,
) {
    if (!shown) return
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    ControlButton(
        icon,
        label,
        style,
        visibility,
        onClick = { onOpen(focused) },
        modifier = Modifier.focusRequester(focus.getValue(menu)),
        interaction = interaction,
    )
}

@Composable
private fun SeekBar(
    state: SeekBarState,
    visibility: ControlsVisibility,
    style: KitePlayerControlsStyle,
    labels: KitePlayerControlsLabels,
) {
    val duration = state.duration ?: return
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shown = state.scrubTarget ?: state.position
    val total = duration.toDouble(DurationUnit.SECONDS).toFloat()
    // Whole seconds, so a screen reader is not handed a new value on every tick.
    val spokenAt = shown.inWholeSeconds.seconds
    val description = labels.positionOf(spokenAt, duration)
    val fraction = state.fraction
    val buffered = state.buffered
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(SeekBarHeight)
            .semantics {
                contentDescription = labels.seekBar
                stateDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(spokenAt.toDouble(DurationUnit.SECONDS).toFloat().coerceIn(0f, total), 0f..total)
                setProgress { seconds ->
                    if (!state.seekable) return@setProgress false
                    state.startScrub(seconds / total)
                    state.endScrub()
                    visibility.show()
                    true
                }
            }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val step = when (event.key) {
                    Key.DirectionLeft -> -SeekStep
                    Key.DirectionRight -> SeekStep
                    else -> return@onKeyEvent false
                }
                state.stepBy(step)
                true
            }
            .focusable(interactionSource = interaction)
            .pointerInput(state, visibility) { barScrub(state, visibility, ThumbRadius.toPx()) },
    ) {
        val radius = ThumbRadius.toPx()
        val y = size.height / 2
        val thickness = style.seekBarThickness.toPx()
        val span = size.width - 2 * radius
        fun at(part: Float) = Offset(radius + part * span, y)
        if (focused) {
            drawRoundRect(
                style.focusColor,
                topLeft = Offset(0f, y - radius - 3.dp.toPx()),
                size = Size(size.width, 2 * (radius + 3.dp.toPx())),
                cornerRadius = CornerRadius(radius + 3.dp.toPx()),
                style = Stroke(2.dp.toPx()),
            )
        }
        drawLine(style.trackColor, at(0f), at(1f), thickness, StrokeCap.Round)
        buffered.forEach { drawLine(style.bufferedColor, at(it.start), at(it.endInclusive), thickness, StrokeCap.Round) }
        drawLine(style.accentColor, at(0f), at(fraction), thickness, StrokeCap.Round)
        drawCircle(style.accentColor, if (state.scrubbing || focused) radius else radius * 0.75f, at(fraction))
    }
}

@Composable
private fun TimeRow(state: SeekBarState, style: KitePlayerControlsStyle, labels: KitePlayerControlsLabels) {
    val text = TextStyle(color = style.contentColor, fontSize = style.textSize)
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        BasicText(labels.time(state.position), style = text)
        Spacer(Modifier.weight(1f))
        state.duration?.let { BasicText(labels.time(it), style = text) }
    }
}

@Composable
private fun VolumeSlider(
    state: VolumeState,
    visibility: ControlsVisibility,
    style: KitePlayerControlsStyle,
    labels: KitePlayerControlsLabels,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val level = state.level
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Canvas(
            Modifier
                .width(96.dp)
                .height(style.buttonSize)
                .semantics {
                    contentDescription = labels.volume
                    stateDescription = labels.volumeLevel(level)
                    progressBarRangeInfo = ProgressBarRangeInfo(level, 0f..1f)
                    setProgress { wanted ->
                        state.setLevel(wanted)
                        visibility.show()
                        true
                    }
                }
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val step = when (event.key) {
                        Key.DirectionLeft -> -VolumeStep
                        Key.DirectionRight -> VolumeStep
                        else -> return@onKeyEvent false
                    }
                    state.setLevel(level + step)
                    true
                }
                .focusable(interactionSource = interaction)
                .pointerInput(state, visibility) {
                    val radius = ThumbRadius.toPx()
                    fun levelAt(x: Float) = ((x - radius) / (size.width - 2 * radius)).coerceIn(0f, 1f)
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        state.setLevel(levelAt(down.position.x))
                        visibility.show()
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            if (change.positionChange() != Offset.Zero) {
                                state.setLevel(levelAt(change.position.x))
                                change.consume()
                            }
                        }
                        visibility.show()
                    }
                },
        ) {
            val radius = ThumbRadius.toPx()
            val y = size.height / 2
            val thickness = style.seekBarThickness.toPx()
            val start = Offset(radius, y)
            val end = Offset(size.width - radius, y)
            val at = Offset(radius + level * (size.width - 2 * radius), y)
            val fill = if (state.muted) style.disabledColor else style.accentColor
            if (focused) drawCircle(style.focusColor, radius + 3.dp.toPx(), at, style = Stroke(2.dp.toPx()))
            drawLine(style.trackColor, start, end, thickness, StrokeCap.Round)
            drawLine(fill, start, at, thickness, StrokeCap.Round)
            drawCircle(fill, radius * 0.75f, at)
        }
    }
}

@Composable
private fun MenuPanel(
    title: String,
    state: TrackMenuState,
    style: KitePlayerControlsStyle,
    focusFirst: Boolean,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    val options = state.options
    val first = remember { FocusRequester() }
    val firstIndex = options.indexOfFirst { it.selected }.coerceAtLeast(0)
    val text = TextStyle(color = style.contentColor, fontSize = style.textSize)
    Column(
        modifier
            .widthIn(min = 160.dp, max = 320.dp)
            .clip(MenuShape)
            .background(style.menuColor)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && (event.key == Key.Escape || event.key == Key.Back)) {
                    onClose()
                    true
                } else {
                    false
                }
            }
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        BasicText(
            title,
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
            style = text.copy(color = style.contentColor.copy(alpha = 0.7f)),
        )
        options.forEachIndexed { index, option ->
            val interaction = remember(option.label) { MutableInteractionSource() }
            val focused by interaction.collectIsFocusedAsState()
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (index == firstIndex) Modifier.focusRequester(first) else Modifier)
                    .border(2.dp, if (focused) style.focusColor else Color.Transparent)
                    .selectable(
                        selected = option.selected,
                        interactionSource = interaction,
                        indication = LocalIndication.current,
                        role = Role.RadioButton,
                    ) {
                        state.select(option)
                        onClose()
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Canvas(Modifier.size(16.dp)) {
                    if (!option.selected) return@Canvas
                    val w = size.width
                    val stroke = 2.dp.toPx()
                    drawLine(style.accentColor, Offset(w * 0.15f, w * 0.55f), Offset(w * 0.4f, w * 0.8f), stroke, StrokeCap.Round)
                    drawLine(style.accentColor, Offset(w * 0.4f, w * 0.8f), Offset(w * 0.85f, w * 0.25f), stroke, StrokeCap.Round)
                }
                BasicText(option.label, Modifier.padding(start = 12.dp), style = text)
            }
        }
    }
    LaunchedEffect(focusFirst) {
        if (focusFirst && options.isNotEmpty()) first.requestFocus()
    }
}

@Composable
private fun ScrubPreview(
    state: SeekBarState,
    style: KitePlayerControlsStyle,
    labels: KitePlayerControlsLabels,
    modifier: Modifier,
) {
    val target = state.scrubTarget ?: return
    val picture = state.preview
    val image by produceState<ImageBitmap?>(null, picture?.image) {
        val bytes = picture?.image
        value = if (bytes == null) null else withContext(Dispatchers.Default) { decodePreview(bytes) }
    }
    val radius = with(LocalDensity.current) { ThumbRadius.toPx() }
    AtFraction(state.fraction, radius, modifier) {
        Column(
            Modifier.clip(MenuShape).background(style.menuColor).padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val shown = image
            if (shown != null && picture != null) PreviewPicture(shown, picture)
            BasicText(
                labels.time(target),
                Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                style = TextStyle(color = style.contentColor, fontSize = style.textSize),
            )
        }
    }
}

@Composable
private fun PreviewPicture(image: ImageBitmap, picture: StreamThumbnail) {
    val region = if (picture.isWholeImage) IntSize(image.width, image.height) else IntSize(picture.width, picture.height)
    if (region.width <= 0 || region.height <= 0) return
    val offset = if (picture.isWholeImage) IntOffset.Zero else IntOffset(picture.x, picture.y)
    // A region that runs off the image draws nothing rather than throwing.
    if (offset.x + region.width > image.width || offset.y + region.height > image.height) return
    Canvas(Modifier.width(PreviewWidth).height(PreviewWidth * (region.height.toFloat() / region.width))) {
        drawImage(image, srcOffset = offset, srcSize = region, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
    }
}

private fun decodePreview(bytes: ByteArray): ImageBitmap? = try {
    bytes.decodeToImageBitmap()
} catch (_: Exception) {
    null
}

/** Places its one child centred over [fraction] of a bar inset by [inset], kept inside the width. */
@Composable
private fun AtFraction(fraction: Float, inset: Float, modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val placeable = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val width = constraints.maxWidth
        val centre = inset + fraction * (width - 2 * inset)
        val x = (centre - placeable.width / 2f).roundToInt().coerceIn(0, (width - placeable.width).coerceAtLeast(0))
        layout(width, placeable.height) { placeable.place(x, 0) }
    }
}

/** A press, a drag and a release on the seek bar. A press is a seek; a drag is a scrub. */
private suspend fun PointerInputScope.barScrub(state: SeekBarState, visibility: ControlsVisibility, inset: Float) {
    fun fractionAt(x: Float) = ((x - inset) / (size.width - 2 * inset)).coerceIn(0f, 1f)
    awaitEachGesture {
        val down = awaitFirstDown()
        if (!state.seekable) return@awaitEachGesture
        down.consume()
        state.startScrub(fractionAt(down.position.x))
        visibility.show()
        var lifted = false
        try {
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    lifted = true
                    change.consume()
                    break
                }
                if (change.positionChange() != Offset.Zero) {
                    state.scrubTo(fractionAt(change.position.x))
                    change.consume()
                }
            }
        } finally {
            if (lifted) state.endScrub() else state.cancelScrub()
            visibility.show()
        }
    }
}

/** A sideways drag of a finger over the picture scrubs. A mouse drag does not, and a tap still toggles. */
private suspend fun PointerInputScope.pictureScrub(state: SeekBarState, visibility: ControlsVisibility) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (down.type != PointerType.Touch) return@awaitEachGesture
        val from = state.fraction
        val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() } ?: return@awaitEachGesture
        val total = state.duration
        if (!state.seekable || total == null || size.width <= 0) return@awaitEachGesture
        val span = (minOf(PictureScrubSpan, total) / total).toFloat()
        fun fractionAt(x: Float) = from + (x - down.position.x) / size.width * span
        state.startScrub(fractionAt(drag.position.x))
        visibility.show()
        var lifted = false
        try {
            lifted = horizontalDrag(drag.id) { change ->
                state.scrubTo(fractionAt(change.position.x))
                change.consume()
            }
        } finally {
            if (lifted) state.endScrub() else state.cancelScrub()
            visibility.show()
        }
    }
}

/**
 * Moves subtitles above the controls while they show, and back when they hide, unless the
 * application moved them itself in between. The same rule the session guards keep for a pause they
 * did not make.
 */
internal class SubtitleLift {
    private var before: Float? = null
    private var lifted: Float? = null

    /** Lifts subtitles to [wanted], or keeps them where they are when they already stand higher. */
    fun raise(source: ControlsSource, wanted: Float) {
        val current = source.snapshot.subtitlePosition
        val base = before?.takeIf { mine(current) } ?: current
        val next = minOf(base, wanted)
        if (next >= base) {
            settle(source)
            return
        }
        before = base
        lifted = next
        source.send("setSubtitlePosition") { setSubtitlePosition(next) }
    }

    /** Puts subtitles back where they were before the lift, unless the application moved them. */
    fun settle(source: ControlsSource) {
        val restore = before ?: return
        val current = source.snapshot.subtitlePosition
        val untouched = mine(current)
        before = null
        lifted = null
        // Sent even when the player still shows the old position: the lift may be on its way, and
        // the engine applies the two in order.
        if (untouched) source.send("setSubtitlePosition") { setSubtitlePosition(restore) }
    }

    /**
     * True when [current] is the lift, or the position before it, which the player can still show
     * while the lift waits on its way to the engine.
     */
    private fun mine(current: Float): Boolean = current == lifted || current == before

    companion object {
        /** The highest the player places subtitles, a tenth of the way down. */
        const val LOWEST = 0.1f
    }
}
