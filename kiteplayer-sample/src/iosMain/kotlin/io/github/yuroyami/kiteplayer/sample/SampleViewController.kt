@file:OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.sample

import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlayerConfig
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
import platform.posix.fsync
import platform.posix.fwrite
import platform.posix.rename
import kotlin.system.exitProcess
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
        if (!smokeMode) installControls()
    }

    override fun viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        playerView.setFrame(view.bounds)
        layoutControls()
    }

    override fun viewDidAppear(animated: Boolean) {
        super.viewDidAppear(animated)
        if (smokeMode) {
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
        if (!smokeMode) closeSample()
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

    private suspend fun awaitPresentationAfter(previous: Long) {
        while (playerView.presentedFrames <= previous || !playerView.hasPicture) {
            delay(PRESENTATION_POLL)
        }
    }
}

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
