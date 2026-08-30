@file:OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.sample

import io.github.yuroyami.kiteplayer.KiteLog
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.PlayerEvent
import io.github.yuroyami.kiteplayer.RenderQuality
import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteplayer.SeekMode
import io.github.yuroyami.kiteplayer.mobile.mobileBackends
import io.github.yuroyami.kiteplayer.mobile.installMobileRenderer
import io.github.yuroyami.kiteplayer.view.KitePlayerUIView
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import platform.CoreGraphics.CGRectGetHeight
import platform.CoreGraphics.CGRectGetWidth
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSBundle
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSSelectorFromString
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIApplication
import platform.UIKit.UIButton
import platform.UIKit.UIButtonTypeSystem
import platform.UIKit.UIColor
import platform.UIKit.UIControlEventTouchUpInside
import platform.UIKit.UIControlStateNormal
import platform.UIKit.UIViewController
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fileno
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.fsync
import platform.posix.fwrite
import platform.posix.rename
import kotlin.math.roundToInt
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The one Swift-facing entry point of the private iOS sample framework. */
public fun sampleViewController(): UIViewController = SampleController()

/**
 * Re-consumed through the phone coordinate at S1.e.2: the hand-built CALayer and renderer are
 * gone, one [KitePlayerUIView] owns the whole presentation lifecycle, and the smoke's oracle
 * keys keep exactly their S1.b meanings, now read from the view's own diagnostics.
 */
private class SampleController : UIViewController(nibName = null, bundle = null) {

    private val playerView = KitePlayerUIView().apply { installMobileRenderer() }
    private val scope = MainScope()
    private val smokeMode = NSProcessInfo.processInfo.arguments.contains(SMOKE_ARGUMENT)

    /** `--scenario a.mkv b.mp4`: clip names under Documents, run in order by [runScenario]. */
    private val scenarioClips: List<String> = NSProcessInfo.processInfo.arguments.let { arguments ->
        val at = arguments.indexOf(SCENARIO_ARGUMENT)
        if (at < 0) emptyList() else arguments.drop(at + 1).map { it.toString() }
    }
    private val scenarioMode = scenarioClips.isNotEmpty()

    /** `--hwdec-off` forces the software decoder, so a hardware route can be measured against it. */
    private val hwdecOff = NSProcessInfo.processInfo.arguments.contains(HWDEC_OFF_ARGUMENT)

    /** `--dither` turns dithering on, so its cost can be measured against the same run without it. */
    private val ditherOn = NSProcessInfo.processInfo.arguments.contains(DITHER_ARGUMENT)

    /** `--deband` turns debanding on, so the taps it costs can be measured against a run without them. */
    private val debandOn = NSProcessInfo.processInfo.arguments.contains(DEBAND_ARGUMENT)
    private var scenarioStarted = false
    private val controlButtons = mutableListOf<UIButton>()

    private var smokeStarted = false
    private var sampleStarted = false
    private var sampleClosing = false
    private var samplePlayer: KitePlayer? = null
    private var terminalJob: Job? = null

    override fun viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor.blackColor
        view.addSubview(playerView)
        if (!smokeMode && !scenarioMode) installControls()
        // A scripted run outlives the auto-lock timer, and a locked phone suspends the app.
        if (scenarioMode) UIApplication.sharedApplication.idleTimerDisabled = true
    }

    override fun viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        playerView.setFrame(view.bounds)
        layoutControls()
    }

    override fun viewDidAppear(animated: Boolean) {
        super.viewDidAppear(animated)
        if (scenarioMode) {
            if (scenarioStarted) return
            scenarioStarted = true
            scope.launch { runScenario() }
        } else if (smokeMode) {
            if (smokeStarted) return
            smokeStarted = true
            scope.launch { runSmoke() }
        } else if (!sampleStarted) {
            sampleStarted = true
            scope.launch { openSample() }
        }
    }

    override fun viewDidDisappear(animated: Boolean) {
        super.viewDidDisappear(animated)
        if (!smokeMode && !scenarioMode) closeSample()
    }

    private fun installControls() {
        addControl("Play", "playSample")
        addControl("Pause", "pauseSample")
        addControl("Seek 5s", "seekSample")
    }

    private fun addControl(title: String, action: String) {
        val button = UIButton.buttonWithType(UIButtonTypeSystem)
        button.setTitle(title, forState = UIControlStateNormal)
        button.setTitleColor(UIColor.whiteColor, forState = UIControlStateNormal)
        button.backgroundColor = UIColor.colorWithWhite(white = 0.16, alpha = 0.88)
        button.layer.cornerRadius = CONTROL_CORNER_RADIUS
        button.enabled = false
        button.addTarget(
            target = this,
            action = NSSelectorFromString(action),
            forControlEvents = UIControlEventTouchUpInside,
        )
        view.addSubview(button)
        controlButtons += button
    }

    private fun layoutControls() {
        if (controlButtons.isEmpty()) return
        val width = CGRectGetWidth(view.bounds)
        val height = CGRectGetHeight(view.bounds)
        val bottomInset = view.safeAreaInsets.useContents { bottom }
        val controlsWidth = width - CONTROL_MARGIN * 2 - CONTROL_SPACING * (controlButtons.size - 1)
        val buttonWidth = (controlsWidth / controlButtons.size).coerceAtLeast(0.0)
        val y = (height - bottomInset - CONTROL_MARGIN - CONTROL_HEIGHT).coerceAtLeast(CONTROL_MARGIN)

        controlButtons.forEachIndexed { index, button ->
            val x = CONTROL_MARGIN + index * (buttonWidth + CONTROL_SPACING)
            button.setFrame(CGRectMake(x, y, buttonWidth, CONTROL_HEIGHT))
        }
    }

    private suspend fun openSample() {
        if (sampleClosing) return
        try {
            val mediaPath = requireNotNull(
                NSBundle.mainBundle.pathForResource(SMOKE_RESOURCE, ofType = SMOKE_EXTENSION),
            ) { "$SMOKE_RESOURCE.$SMOKE_EXTENSION is not in the application bundle" }

            val activePlayer = KitePlayer.create(
                PlayerConfig(backends = mobileBackends()),
            )
            samplePlayer = activePlayer
            playerView.player = activePlayer
            activePlayer.open(MediaItem(mediaPath))
            if (sampleClosing) return

            setControlsEnabled(true)
            printSampleSummary("ready (${activePlayer.state.value.status})", activePlayer)
            terminalJob = scope.launch {
                val terminal = activePlayer.state.first { snapshot -> snapshot.status.isTerminal }
                setControlsEnabled(false)
                delay(TERMINAL_STATS_SETTLE)
                if (samplePlayer === activePlayer && !sampleClosing) {
                    printSampleSummary("terminal ${terminal.status}", activePlayer)
                }
            }
        } catch (failure: Throwable) {
            if (!sampleClosing) {
                println("iOS sample could not open: ${failure.message ?: failure::class.simpleName}")
                samplePlayer?.let { activePlayer ->
                    printSampleSummary("terminal ${activePlayer.state.value.status}", activePlayer)
                }
                closeSample()
            }
        }
    }

    @ObjCAction
    private fun playSample() {
        val activePlayer = samplePlayer ?: return
        if (sampleClosing) return
        activePlayer.play()
        printSampleSummary("action play", activePlayer)
    }

    @ObjCAction
    private fun pauseSample() {
        val activePlayer = samplePlayer ?: return
        if (sampleClosing) return
        activePlayer.pause()
        printSampleSummary("action pause", activePlayer)
    }

    @ObjCAction
    private fun seekSample() {
        val activePlayer = samplePlayer ?: return
        if (sampleClosing) return
        setControlsEnabled(false)
        scope.launch {
            try {
                activePlayer.seek(SEEK_POSITION, SeekMode.Precise)
                if (samplePlayer === activePlayer && !sampleClosing) {
                    printSampleSummary("action precise seek to 5 s", activePlayer)
                }
            } catch (failure: Throwable) {
                if (!sampleClosing) {
                    println("iOS sample seek failed: ${failure.message ?: failure::class.simpleName}")
                    printSampleSummary("action precise seek failed", activePlayer)
                }
            } finally {
                val status = activePlayer.state.value.status
                if (samplePlayer === activePlayer && !sampleClosing && !status.isTerminal) {
                    setControlsEnabled(true)
                }
            }
        }
    }

    private fun setControlsEnabled(enabled: Boolean) {
        controlButtons.forEach { it.enabled = enabled }
    }

    private fun closeSample() {
        if (sampleClosing) return
        sampleClosing = true
        setControlsEnabled(false)
        terminalJob?.cancel()
        terminalJob = null

        val activePlayer = samplePlayer
        // The view closes its renderer synchronously, before detach, when the player is cleared.
        playerView.player = null
        // Request close synchronously from the lifecycle callback. Kotlin/Native exposes no
        // overridable UIViewController deinit hook, so disappearance is this private host's boundary.
        activePlayer?.close()
        scope.launch {
            val closeFailure = runCatching {
                if (activePlayer != null) withTimeout(CLOSE_TIMEOUT) { activePlayer.closeAndAwait() }
            }.exceptionOrNull()
            if (samplePlayer === activePlayer) samplePlayer = null
            closeFailure?.let { println("iOS sample player close failed: ${it.message ?: it::class.simpleName}") }
        }
    }

    private suspend fun runSmoke() {
        val result = SmokeResult()
        var player: KitePlayer? = null
        var playerTeardownCompleted = false

        try {
            withTimeout(SMOKE_TIMEOUT) {
                val mediaPath = requireNotNull(
                    NSBundle.mainBundle.pathForResource(SMOKE_RESOURCE, ofType = SMOKE_EXTENSION),
                ) { "$SMOKE_RESOURCE.$SMOKE_EXTENSION is not in the application bundle" }

                val activePlayer = KitePlayer.create(
                    PlayerConfig(backends = mobileBackends()),
                )
                player = activePlayer
                playerView.player = activePlayer
                activePlayer.open(MediaItem(mediaPath))

                awaitPresentationAfter(0L)
                val beforeSeek = playerView.presentedFrames
                result.seekRequested = true
                activePlayer.seek(SEEK_POSITION, SeekMode.Precise)
                awaitPresentationAfter(beforeSeek)
                val landedMillis = activePlayer.position().inWholeMilliseconds
                result.seekLanded = playerView.presentedFrames > beforeSeek &&
                    landedMillis in SEEK_LANDING_RANGE
                check(result.seekLanded) {
                    "the precise seek position was $landedMillis ms"
                }

                activePlayer.play()
                val terminal = activePlayer.state.first { snapshot ->
                    snapshot.status == PlaybackStatus.Ended || snapshot.status == PlaybackStatus.Failed
                }
                result.terminalState = terminal.status.name
                captureCounters(result, activePlayer, playerView)
                check(terminal.status == PlaybackStatus.Ended) { "playback ended as ${terminal.status}" }
                check(terminal.error == null) { "healthy playback retained ${terminal.error}" }

                withTimeout(CLOSE_TIMEOUT) { activePlayer.closeAndAwait() }
                val final = activePlayer.state.value
                check(final.status == PlaybackStatus.Idle) { "awaited close left ${final.status}" }
                check(final.error == null) { "healthy close retained ${final.error}" }
            }
            // Set this only after the entire timeout scope returned. A timeout delivered at the close
            // boundary must take the failure path even if every assertion inside the scope had run.
            playerTeardownCompleted = true
        } catch (_: Throwable) {
            runCatching { player?.close() }
        } finally {
            val rendererTeardownCompleted = runCatching { playerView.player = null }.isSuccess
            captureCounters(result, player, playerView)
            result.layerImage = runCatching { playerView.hasPicture }.getOrDefault(false)
            result.teardownCompleted = playerTeardownCompleted && rendererTeardownCompleted
            try {
                writeSmokeResult(result)
            } finally {
                exitProcess(0)
            }
        }
    }


    // ---------------------------------------------------------------------------------------------
    // The device scenario: open, play, pause, seek, open the next clip, with every step timed.
    // Writes Documents/scenario-trace.log, which `xcrun devicectl device copy from` pulls back.
    // ---------------------------------------------------------------------------------------------

    private suspend fun runScenario() {
        val documents = documentsDirectory()
        val trace = ScenarioTrace("$documents/$SCENARIO_TRACE_NAME")
        KiteLog.install { tag, message -> trace.line("log $tag: $message") }
        val first = "$documents/${scenarioClips[0]}"
        val second = "$documents/${scenarioClips.getOrElse(1) { scenarioClips[0] }}"
        var player = KitePlayer.create(scenarioConfig())
        playerView.player = player
        var sampler = scope.launch { sampleScenario(player, trace) }
        val events = scope.launch {
            player.events.collect { event ->
                when (event) {
                    is PlayerEvent.AudioFormatChanged ->
                        trace.line("AUDIO negotiated ${event.channels} channels at ${event.sampleRate} Hz")
                    is PlayerEvent.Warning -> trace.line("WARN ${event.warning.message}")
                    else -> Unit
                }
            }
        }
        try {
            withTimeout(SCENARIO_TIMEOUT) {
                trace.line("### hardwareDecode=" + (if (hwdecOff) "Off" else "Auto") +
            " dither=" + (if (ditherOn) "on" else "off") +
            " deband=" + (if (debandOn) "on" else "off"))
        player.setRenderQuality(RenderQuality(dither = ditherOn, deband = debandOn))
        trace.line("### phase 1: first open, fresh player")
                openAndSettle(player, trace, first, stopFirst = false)

                trace.line("### phase 2: SECOND open, same player, same file")
                openAndSettle(player, trace, first, stopFirst = true)

                trace.line("### phase 3: third open, same player, other file")
                openAndSettle(player, trace, second, stopFirst = true)

                trace.line("### phase 4: same file, BRAND NEW player")
                sampler.cancel()
                val closeStarted = TimeSource.Monotonic.markNow()
                runCatching { withTimeout(CLOSE_TIMEOUT) { player.closeAndAwait() } }
                    .onFailure { trace.line("phase 4 close failed: $it") }
                trace.line("close took ${closeStarted.elapsedNow().inWholeMilliseconds} ms")
                runCatching { playerView.player = null }
                player = KitePlayer.create(scenarioConfig())
                playerView.player = player
                sampler = scope.launch { sampleScenario(player, trace) }
                openAndSettle(player, trace, second, stopFirst = false)
            }
        } catch (failure: Throwable) {
            trace.line("scenario failed: ${failure.stackTraceToString()}")
        } finally {
            sampler.cancel()
            events.cancel()
            runCatching { trace.line(player.diagnosticsDump()) }
            runCatching { withTimeout(CLOSE_TIMEOUT) { player.closeAndAwait() } }
                .onFailure { trace.line("final close failed: $it") }
            runCatching { playerView.player = null }
            trace.line("scenario done")
            trace.close()
            KiteLog.install(null)
            exitProcess(0)
        }
    }

    private fun scenarioConfig(): PlayerConfig = PlayerConfig(
        backends = mobileBackends(),
        hardwareDecode = if (hwdecOff) HwdecPolicy.Off else HwdecPolicy.Auto,
    )

    private suspend fun openAndSettle(
        player: KitePlayer,
        trace: ScenarioTrace,
        path: String,
        stopFirst: Boolean,
    ) {
        if (stopFirst) {
            val stopStarted = TimeSource.Monotonic.markNow()
            player.stop()
            trace.line("stop took ${stopStarted.elapsedNow().inWholeMilliseconds} ms")
        }
        val openStarted = TimeSource.Monotonic.markNow()
        player.open(MediaItem(path))
        val snapshot = player.state.value
        trace.line(
            "open $path took ${openStarted.elapsedNow().inWholeMilliseconds} ms " +
                "status=${snapshot.status} duration=${snapshot.duration} " +
                "hwdec=${player.stats.value.hardwareDecode} " +
                "tracks=${snapshot.tracks.all.joinToString { "${it.kind}:${it.codec}" }}",
        )
        scenarioPlay(player, trace)
        delay(5.seconds)
        // The seek is the whole point of the ordering: it moves the player's epoch, and the NEXT
        // open is the one that used to sit out both startup deadlines and land on no picture.
        val target = (snapshot.duration ?: 30.seconds) / 2
        val preciseStarted = TimeSource.Monotonic.markNow()
        player.seek(target, SeekMode.Precise)
        trace.line(
            "precise seek to $target took ${preciseStarted.elapsedNow().inWholeMilliseconds} ms " +
                "landed=${player.position()} status=${player.state.value.status}",
        )
        delay(3.seconds)
        scenarioPause(player, trace)
        trace.line(player.diagnosticsDump())
    }

    private suspend fun scenarioPlay(player: KitePlayer, trace: ScenarioTrace) {
        val submittedBefore = player.stats.value.submittedFrames
        player.play()
        val playingAfter = awaitWithin(5.seconds) { player.state.value.status == PlaybackStatus.Playing }
        val frameAfter = awaitWithin(5.seconds) { player.stats.value.submittedFrames > submittedBefore }
        trace.line("play: Playing after $playingAfter ms, next frame submitted after $frameAfter ms (-1 = not within 5 s)")
    }

    private suspend fun scenarioPause(player: KitePlayer, trace: ScenarioTrace) {
        val started = TimeSource.Monotonic.markNow()
        player.pause()
        val pausedAfter = awaitWithin(5.seconds) { player.state.value.status == PlaybackStatus.Paused }
        // When the picture really stops: the last time the submitted count moved after the call.
        var lastSubmitted = player.stats.value.submittedFrames
        var lastChangeMs = 0L
        val watchStarted = TimeSource.Monotonic.markNow()
        while (watchStarted.elapsedNow() < 3.seconds) {
            delay(50.milliseconds)
            val submitted = player.stats.value.submittedFrames
            if (submitted != lastSubmitted) {
                lastSubmitted = submitted
                lastChangeMs = started.elapsedNow().inWholeMilliseconds
            }
        }
        trace.line(
            "pause: Paused after $pausedAfter ms, last frame submitted at +$lastChangeMs ms, " +
                "position=${player.position()} (-1 = not within 5 s)",
        )
    }

    private suspend fun scenarioSeekLater(player: KitePlayer, trace: ScenarioTrace, target: Duration) {
        val submittedBefore = player.stats.value.submittedFrames
        player.seekLater(target, SeekMode.KeyframeThenRefine)
        val frameAfter = awaitWithin(15.seconds) { player.stats.value.submittedFrames > submittedBefore }
        val nearAfter = awaitWithin(15.seconds) {
            (player.position() - target).absoluteValue < 2.seconds && player.stats.value.submittedFrames > submittedBefore
        }
        trace.line(
            "seekLater to $target: first frame after $frameAfter ms, within 2 s of target after $nearAfter ms, " +
                "position=${player.position()} status=${player.state.value.status}",
        )
    }

    /** Milliseconds until [condition] holds, polled every 10 ms, or -1 when [limit] passes first. */
    private suspend fun awaitWithin(limit: Duration, condition: () -> Boolean): Long {
        val started = TimeSource.Monotonic.markNow()
        while (started.elapsedNow() < limit) {
            if (condition()) return started.elapsedNow().inWholeMilliseconds
            delay(10.milliseconds)
        }
        return -1
    }

    /** One line every [SCENARIO_SAMPLE_INTERVAL]: the counters, and how fast the position moves against wall time. */
    private suspend fun sampleScenario(player: KitePlayer, trace: ScenarioTrace) {
        val started = TimeSource.Monotonic.markNow()
        var lastPosition = player.position()
        var lastMark = TimeSource.Monotonic.markNow()
        while (true) {
            delay(SCENARIO_SAMPLE_INTERVAL)
            val stats = player.stats.value
            val position = player.position()
            val wall = lastMark.elapsedNow()
            val rate = if (wall > Duration.ZERO) (position - lastPosition) / wall else 0.0
            lastPosition = position
            lastMark = TimeSource.Monotonic.markNow()
            trace.line(
                "t=${started.elapsedNow().inWholeMilliseconds} ${liveActorState(player)} " +
                    "status=${player.state.value.status} " +
                    "pos=${position.inWholeMilliseconds} rate=${(rate * 100).roundToInt()}% " +
                    "decoded=${stats.decodedVideoFrames} submitted=${stats.submittedFrames} " +
                    "presented=${playerView.presentedFrames} droppedLate=${stats.droppedFramesLate} " +
                    "refused=${stats.refusedFrames} repeated=${stats.repeatedFrames} " +
                    "videoQ=${stats.videoQueueDepth.inWholeMilliseconds} audioQ=${stats.audioQueueDepth.inWholeMilliseconds} " +
                    "underruns=${stats.audioUnderruns} drift=${stats.avDrift.inWholeMilliseconds} " +
                    "fps=${stats.videoDecodeFps.roundToInt()} hw=${stats.hardwareDecode} master=${stats.masterClock}",
            )
        }
    }

    private suspend fun awaitPresentationAfter(previous: Long) {
        while (playerView.presentedFrames <= previous || !playerView.hasPicture) {
            delay(PRESENTATION_POLL)
        }
    }
}


/**
 * The actor and worker line out of a live diagnostics dump.
 *
 * The stats flow is published by the actor, so it freezes while the actor sits inside a long open.
 * This reads the same state directly, which is the only way to see WHICH side is starving there.
 */
private fun liveActorState(player: KitePlayer): String =
    runCatching {
        player.diagnosticsDump().lineSequence().first { it.contains("actor passes=") }.trim()
    }.getOrElse { "actorState unavailable: $it" }

/** One stamped line per event, appended to a file under Documents and echoed to stdout. */
private class ScenarioTrace(path: String) {
    private val file = fopen(path, "a") ?: error("cannot open $path")
    private val started = TimeSource.Monotonic.markNow()

    /** Safe from any thread: one stdio call per line, and stdio locks the stream per call. */
    fun line(text: String) {
        val stamped = "[+${started.elapsedNow().inWholeMilliseconds}] $text\n"
        println(stamped.trimEnd())
        fputs(stamped, file)
        fflush(file)
    }

    fun close() {
        fclose(file)
    }
}

private fun documentsDirectory(): String = NSSearchPathForDirectoriesInDomains(
    directory = NSDocumentDirectory,
    domainMask = NSUserDomainMask,
    expandTilde = true,
).firstOrNull() as? String ?: error("the application has no Documents directory")

private val PlaybackStatus.isTerminal: Boolean
    get() = this == PlaybackStatus.Ended || this == PlaybackStatus.Failed

private fun printSampleSummary(stage: String, player: KitePlayer) {
    val stats = player.stats.value
    println("iOS sample: $stage")
    println("  decoded          ${stats.decodedVideoFrames} video frames")
    println("  submitted        ${stats.submittedFrames} frames")
    println("  dropped late     ${stats.droppedFramesLate}")
    println("  audio underruns  ${stats.audioUnderruns}")
}

private class SmokeResult {
    var seekRequested: Boolean = false
    var seekLanded: Boolean = false
    var terminalState: String = PlaybackStatus.Failed.name
    var decodedFrames: Long = 0
    var submittedFrames: Long = 0
    var presentedFrames: Long = 0
    var layerImage: Boolean = false
    var audioUnderruns: Long = 0
    var teardownCompleted: Boolean = false
}

private fun captureCounters(
    result: SmokeResult,
    player: KitePlayer?,
    view: KitePlayerUIView,
) {
    player?.let { activePlayer ->
        runCatching { activePlayer.stats.value }.getOrNull()?.let { stats ->
            result.decodedFrames = maxOf(result.decodedFrames, stats.decodedVideoFrames)
            result.submittedFrames = maxOf(result.submittedFrames, stats.submittedFrames)
            result.audioUnderruns = maxOf(result.audioUnderruns, stats.audioUnderruns)
        }
    }
    result.presentedFrames = maxOf(result.presentedFrames, view.presentedFrames)
}

private fun writeSmokeResult(result: SmokeResult) {
    val directory = NSSearchPathForDirectoriesInDomains(
        directory = NSDocumentDirectory,
        domainMask = NSUserDomainMask,
        expandTilde = true,
    ).firstOrNull() as? String ?: error("the application has no Documents directory")
    val temporaryPath = "$directory/$RESULT_TEMPORARY_NAME"
    val resultPath = "$directory/$RESULT_NAME"
    val bytes = result.toJson().encodeToByteArray()
    val file = fopen(temporaryPath, "wb") ?: error("cannot open the smoke result temporary file")

    var writeFailure: Throwable? = null
    try {
        val written = bytes.usePinned { pinned ->
            fwrite(pinned.addressOf(0), 1.convert(), bytes.size.convert(), file)
        }
        check(written.toInt() == bytes.size) { "the smoke result write was short" }
        check(fflush(file) == 0) { "the smoke result flush failed" }
        check(fsync(fileno(file)) == 0) { "the smoke result sync failed" }
    } catch (failure: Throwable) {
        writeFailure = failure
    }
    val closeResult = fclose(file)
    writeFailure?.let { throw it }
    check(closeResult == 0) { "the smoke result close failed" }
    check(rename(temporaryPath, resultPath) == 0) { "the smoke result atomic replace failed" }
}

private fun SmokeResult.toJson(): String = buildString {
    append('{')
    append("\"seekRequested\":").append(seekRequested)
    append(",\"seekLanded\":").append(seekLanded)
    append(",\"terminalState\":\"").append(terminalState).append('"')
    append(",\"decodedFrames\":").append(decodedFrames)
    append(",\"submittedFrames\":").append(submittedFrames)
    append(",\"presentedFrames\":").append(presentedFrames)
    append(",\"layerImage\":").append(layerImage)
    append(",\"audioUnderruns\":").append(audioUnderruns)
    append(",\"teardownCompleted\":").append(teardownCompleted)
    append('}')
}

private const val SMOKE_ARGUMENT = "--s1b-smoke"
private const val SCENARIO_ARGUMENT = "--scenario"
private const val HWDEC_OFF_ARGUMENT = "--hwdec-off"
private const val DITHER_ARGUMENT = "--dither"
private const val DEBAND_ARGUMENT = "--deband"
private const val SCENARIO_TRACE_NAME = "scenario-trace.log"
private val SCENARIO_TIMEOUT = 10.minutes
private val SCENARIO_SAMPLE_INTERVAL = 250.milliseconds
private const val SMOKE_RESOURCE = "sync1080p30"
private const val SMOKE_EXTENSION = "mp4"
private const val RESULT_NAME = "s1b-smoke.json"
private const val RESULT_TEMPORARY_NAME = "s1b-smoke.json.tmp"
private val SMOKE_TIMEOUT = 45.seconds
private val CLOSE_TIMEOUT = 12.seconds
private val SEEK_POSITION = 5.seconds
private val SEEK_LANDING_RANGE = 5_000L..5_034L
private val PRESENTATION_POLL = 10.milliseconds
private val TERMINAL_STATS_SETTLE = 2.seconds
private const val CONTROL_MARGIN = 16.0
private const val CONTROL_SPACING = 8.0
private const val CONTROL_HEIGHT = 44.0
private const val CONTROL_CORNER_RADIUS = 8.0
