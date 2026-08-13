@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.sample

import io.github.yuroyami.kiteplayer.Backends
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackException
import io.github.yuroyami.kiteplayer.PlaybackStats
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.PlayerEvent
import io.github.yuroyami.kiteplayer.PlayerSnapshot
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.ffmpeg.KiteCodecMediaBackend
import io.github.yuroyami.kiteplayer.ffmpeg.KiteCodecVideoFrame
import io.github.yuroyami.kiteplayer.ffmpeg.corePixelBufferOrNull
import io.github.yuroyami.kiteplayer.ffmpeg.uploadPlanesOrNull
import io.github.yuroyami.kiteplayer.output.AppKitWindow
import io.github.yuroyami.kiteplayer.output.MetalPicture
import io.github.yuroyami.kiteplayer.output.MetalVideoRenderer
import io.github.yuroyami.kiteplayer.output.AppleHostClock
import io.github.yuroyami.kiteplayer.output.AppleOutputBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Plays a file through the player.
 *
 * There is one object in this file's playback path: [KitePlayer]. It is handed the two backends this
 * platform has, the FFmpeg one for reading and decoding and the Apple one for the clock and the device,
 * and everything else is its own: the demux pump, both decoders, the audio feeder, the presentation
 * schedule, the seek machine and the state it publishes. This file parses arguments, creates the player,
 * opens, plays, prints what the player reports, and wires a window when asked for one.
 *
 * That is the whole point of the file. Before the player existed this was 425 lines of hand-wired
 * pipeline, and every one of those lines was a decision an application should never have to make.
 *
 * The numbers it prints are development evidence from a debug binary and nothing more. What they are
 * good for is noticing a regression: frames submitted against frames drawn, drops, repeats, underruns
 * and the drift between the audio clock and real time.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
fun main(args: Array<String>) {
    val path = args.firstOrNull { !it.startsWith("--") }
    if (path == null || args.contains("-h") || args.contains("--help")) {
        println("usage: kiteplayer <media file> [--window] [--no-video] [--seek=<seconds>]")
        println()
        println("Plays the file and reports the position, the audio to video drift, and the frame")
        println("accounting, all taken from the player's own flows.")
        println()
        println("  --window        open a window and draw the video in it")
        println("  --no-video      play the audio track only")
        println("  --seek=<sec>    once playing, seek to that position and carry on from there")
        exitProcess(if (path == null) 2 else 0)
    }
    val videoEnabled = !args.contains("--no-video")
    val wantWindow = args.contains("--window")
    val seekArgument = args.firstOrNull { it.startsWith("--seek=") }
    val seekTo = seekArgument?.removePrefix("--seek=")?.toDoubleOrNull()?.seconds
    if (seekArgument != null && seekTo == null) {
        println("--seek takes a number of seconds, as in --seek=5")
        exitProcess(2)
    }

    // Everything either half of the pipeline degrades on, printed as it happens and counted in one
    // place, so a run's own summary says how many there were.
    val report = SessionReport()

    // Explicit composition, because nothing is discovered: Kotlin/Native has no classpath service
    // lookup, so naming the backends here is the honest alternative to a reflective search that would
    // fail differently on every platform. The warning callback is the backend's own: it reports colour
    // it could only convert approximately, which is a degradation and never a failure.
    val player = KitePlayer.create(
        PlayerConfig(
            backends = Backends(
                backend = KiteCodecMediaBackend(onWarning = { report.warn(it.message) }),
                output = AppleOutputBackend,
            ),
            statsInterval = STATS_INTERVAL,
        ),
    )

    if (wantWindow && videoEnabled) {
        // AppKit owns the main thread and its run loop. The media is opened first, on this thread,
        // because the window is sized from the picture; then the renderer is attached, which is legal
        // at any time, and playback runs on another thread while the run loop has this one.
        val snapshot = runBlocking { openOrExit(player, path, report, videoEnabled) }
        val size = snapshot.videoSize
        val window = AppKitWindow(
            title = path.substringAfterLast('/'),
            width = (size?.displayWidth ?: 1280).coerceAtMost(1600),
            height = (size?.height ?: 720).coerceAtMost(1000),
            useMetalLayer = true,
        )
        // The Metal renderer is the S2.c default; the CG image-view path stays one flag away as
        // the measured software fallback. The resolver is the consumer-side mapping of the
        // backend's two frame truths onto the renderer's seam: a VideoToolbox CVPixelBuffer with
        // no copy, or native-format planes with one memcpy each.
        val renderer = MetalVideoRenderer(
            layer = checkNotNull(window.metalLayer) { "the window was built with useMetalLayer" },
            resolver = { frame ->
                val decoded = frame as KiteCodecVideoFrame
                decoded.corePixelBufferOrNull()?.let { MetalPicture.CorePixelBuffer(it) }
                    ?: decoded.uploadPlanesOrNull()?.let { planes ->
                        MetalPicture.SoftwarePlanes(
                            width = planes.width,
                            height = planes.height,
                            format = planes.format,
                            planes = planes.planes.map {
                                MetalPicture.SoftwarePlanes.Plane(it.bytes, it.bytesPerRow, it.rows)
                            },
                        )
                    }
            },
        )
        player.attachRenderer(renderer)
        val sessionContext = newSingleThreadContext("kiteplayer-sample")
        val session = CoroutineScope(sessionContext)
        // Closing the window means the viewer is done. Without this the run loop keeps going with
        // nothing on screen and the file plays to its end headless, audio and all.
        window.onCloseRequested = { session.cancel() }
        session.launch {
            try {
                playToEnd(player, path, report, renderer = null, seekTo = seekTo)
            } finally {
                // Closed before its counters are read, so an image still on its way to the window is
                // accounted for one way or the other rather than being missed.
                renderer.close()
                println("  window drew       ${renderer.presentedFrames} frames")
                println("  superseded        ${renderer.supersededFrames} (the renderer was the bottleneck)")
                println("  never drawn       ${renderer.failedFrames}")
                window.stop()
            }
        }
        window.runEventLoop()
        closeAndWait(player)
        return
    }

    val counting = CountingRenderer(AppleHostClock)
    runBlocking {
        // Attached before the open, so the frame the open ends on is drawn by it rather than counted as
        // a frame nothing drew.
        player.attachRenderer(counting)
        openOrExit(player, path, report, videoEnabled)
        playToEnd(player, path, report, counting, seekTo)
    }
    closeAndWait(player)
}

/**
 * Opens [path], prints what the container turned out to be, and exits with one sentence if it cannot.
 *
 * A missing file, a permission error, bytes that are not media, a codec this FFmpeg build does not have:
 * all of them are the same thing to a user, so all of them get a sentence rather than a stack trace. The
 * player throws one type for all of them and carries the typed error inside it.
 */
private suspend fun openOrExit(
    player: KitePlayer,
    path: String,
    report: SessionReport,
    videoEnabled: Boolean,
): PlayerSnapshot {
    report.start(player)
    try {
        player.open(MediaItem(path))
    } catch (failure: PlaybackException) {
        println("cannot play $path")
        println("  ${failure.error.cause?.message ?: failure.error.message}")
        player.close()
        exitProcess(1)
    }

    var snapshot = player.state.value
    val video = snapshot.tracks.selectedVideo?.let { snapshot.tracks.find(it) }
    val audio = snapshot.tracks.selectedAudio?.let { snapshot.tracks.find(it) }

    println("file      $path")
    video?.let {
        val fps = it.frameRate?.let { rate -> ((rate * 1000).toInt() / 1000.0).toString() } ?: "?"
        println("video     ${it.codec}, ${it.videoSize?.width}x${it.videoSize?.height}, $fps fps")
    }
    audio?.let { println("audio     ${it.codec}, ${it.sampleRate} Hz, ${it.channels} channel(s)") }
    println("duration  ${snapshot.duration?.let { format(it) } ?: "unknown"}")

    val negotiated = report.negotiatedAudio
    if (audio != null && negotiated != null) {
        println("device    ${negotiated.sampleRate} Hz, ${negotiated.channels} channel(s)")
        // The conversion stage is keyed on what the decoder produces against what the device accepted.
        // The same channel count at the same rate makes every stage a copy; anything else is a downmix, a
        // rate conversion, or both, and a 5.1 file on a stereo device is the case that used to reach the
        // ring as garbage.
        val converts = audio.channels != negotiated.channels || audio.sampleRate != negotiated.sampleRate
        println(
            if (converts) {
                "pipeline  ${audio.channels} channel(s) at ${audio.sampleRate} Hz " +
                    "into ${negotiated.channels} at ${negotiated.sampleRate} Hz"
            } else {
                "pipeline  pass through, the device took what the decoder produces"
            },
        )
    }

    // A track change reopens the container and seeks back, which is why it is a call and not a config
    // flag: the player has to be open before it can be told to play something else out of the file.
    if (!videoEnabled && snapshot.tracks.selectedVideo != null) {
        player.selectTrack(TrackKind.Video, null)
        println("video     deselected, audio only")
        snapshot = player.state.value
    }
    println()
    return snapshot
}

/**
 * Plays until the media ends or the player fails, printing one line of state as it goes.
 *
 * Every figure comes from the player: the position from its own published reading, everything else from
 * the statistics it samples on a timer. Nothing here measures playback, which is the difference between
 * a sample and a test harness.
 *
 * [seekTo] is the one thing this loop does rather than reports. Given it, the loop lets playback settle,
 * asks for that position once, prints where the player says it landed, and carries on printing, so a
 * reader can see both the landing and that the file kept playing from it.
 */
private suspend fun playToEnd(
    player: KitePlayer,
    path: String,
    report: SessionReport,
    renderer: CountingRenderer?,
    seekTo: Duration? = null,
) {
    println("playing. press ctrl-c to stop.")
    player.play()

    val startedAt = AppleHostClock.nanos()
    var basePosition: Duration? = null
    var seekPending = seekTo
    val total = player.state.value.duration?.let { format(it) } ?: "  --:--.---"

    while (true) {
        val snapshot = player.state.value
        if (snapshot.status == PlaybackStatus.Ended || snapshot.status == PlaybackStatus.Failed) break
        val stats = player.stats.value
        val position = player.position()

        // Asked for after playback is under way rather than straight after play(), because a seek out of
        // the middle of a file is the case worth watching: the device is running and every worker has to
        // be brought to a stop before the cursor moves.
        if (seekPending != null && position >= SEEK_AFTER) {
            val target = seekPending
            seekPending = null
            println()
            println("seeking to ${format(target)}")
            player.seek(target)
            println("  landed at  ${format(player.position())}")
            // Read one interval later rather than at once. The player completes the call and publishes
            // its next snapshot on the pass after that, so an immediate read reports the status the seek
            // itself put up rather than the one it resumed into.
            delay(STATS_INTERVAL)
            println("  status     ${player.state.value.status}")
            // Read after the seek returned, so what follows is playback from the landing and not the
            // tail of what was already in flight before it.
            basePosition = null
            continue
        }

        // Clock drift against real time. A constant offset at startup is priming latency and harmless. A
        // figure that grows means the clock is wrong, and over a long file that is the number to watch.
        var clockDrift = "     --"
        val elapsed = (AppleHostClock.nanos() - startedAt).nanoseconds
        if (basePosition == null && elapsed > 500.milliseconds) basePosition = position - elapsed
        basePosition?.let { base ->
            val drift = (position - base) - elapsed
            clockDrift = "${drift.inWholeMilliseconds.toString().padStart(5)} ms"
        }

        print(
            "\r  ${format(position)} / $total   clock $clockDrift   " +
                "a/v ${stats.avDrift.inWholeMilliseconds.toString().padStart(4)} ms   " +
                "submitted ${stats.submittedFrames}   dropped ${stats.droppedFramesLate}   " +
                "repeated ${stats.repeatedFrames}   " +
                "audio ${stats.audioQueueDepth.inWholeMilliseconds.toString().padStart(4)} ms   " +
                "underruns ${stats.audioUnderruns}   ",
        )
        delay(200)
    }

    // Statistics are published on a timer, so the last sample taken while playing is up to one interval
    // old and would report the file minus its final fraction of a second. One more interval, and the
    // summary is the whole file.
    delay(STATS_INTERVAL * 2)
    val snapshot = player.state.value
    val stats = player.stats.value
    println()
    println()
    if (snapshot.status == PlaybackStatus.Failed) {
        println("stopped playing $path")
        println("  ${snapshot.error?.message ?: "no error was retained, which is a bug"}")
    } else {
        println("done.")
    }
    printSummary(player, snapshot, stats, renderer, report)
}

/** The accounting, in the order a regression is usually spotted in. */
private fun printSummary(
    player: KitePlayer,
    snapshot: PlayerSnapshot,
    stats: PlaybackStats,
    renderer: CountingRenderer?,
    report: SessionReport,
) {
    println("  decoded          ${stats.decodedVideoFrames} video frames")
    if (snapshot.tracks.selectedVideo != null || stats.decodedVideoFrames > 0) {
        println("  submitted        ${stats.submittedFrames} frames")
        println("  headless         ${stats.headlessFrames} (no renderer was attached for these)")
        println("  dropped late     ${stats.droppedFramesLate}")
        println("  repeated         ${stats.repeatedFrames}")
        println("  final a/v drift  ${stats.avDrift.inWholeMilliseconds} ms")
    }
    renderer?.let {
        println("  renderer got     ${it.count} frames")
        println("  worst schedule   ${it.worstErrorMillis} ms from the requested time")
    }
    println("  audio underruns  ${stats.audioUnderruns}")
    println("  latency quality  ${stats.audioLatencyQuality}")
    println("  master clock     ${stats.masterClock}")
    println("  rebuffers        ${stats.rebuffers}")
    val total = snapshot.duration?.let { format(it) } ?: "  --:--.---"
    println("  played to        ${format(player.position())} of $total")
    println("  warnings         ${report.warnings}, printed above as they happened")
}

/**
 * Closes the player and waits for it to say it is idle.
 *
 * Close returns at once and the teardown is the player's own, bounded, on its own threads. A command
 * line tool that returned from `main` without waiting would take those threads down mid-teardown, which
 * is how a device is left running for the fraction of a second it takes the process to die.
 */
private fun closeAndWait(player: KitePlayer) {
    player.close()
    runBlocking {
        withTimeoutOrNull(5.seconds) {
            player.state.first { it.status == PlaybackStatus.Idle }
        } ?: println("the player did not finish closing within 5 s: ${player.state.value.error?.message}")
    }
}

/**
 * The player's event stream, printed and remembered.
 *
 * The negotiated audio format is announced during the open, before any caller could collect it if it
 * subscribed afterwards, which is why this subscribes undispatched: the collector is registered inside
 * the [start] call rather than whenever a dispatcher gets round to it.
 */
private class SessionReport {

    var negotiatedAudio: PlayerEvent.AudioFormatChanged? = null
        private set

    var warnings: Int = 0
        private set

    private var job: Job? = null

    /** Prints a degradation once, from whichever half of the pipeline noticed it, and counts it. */
    fun warn(message: String) {
        warnings++
        println("warning: $message")
    }

    fun start(player: KitePlayer) {
        if (job != null) return
        val scope = CoroutineScope(Dispatchers.Default)
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            player.events.collect { event ->
                when (event) {
                    is PlayerEvent.AudioFormatChanged -> negotiatedAudio = event
                    is PlayerEvent.Warning -> warn(event.warning.message)
                    // A failure is deliberately not printed from here. It is retained on the snapshot,
                    // and whoever made the call that failed reports it once, with the context of what it
                    // was doing. Printing it twice is how a one sentence error becomes three.
                    else -> Unit
                }
            }
        }
    }
}

/** How often the player samples its statistics for this run, and how long the summary waits for them. */
private val STATS_INTERVAL: Duration = 200.milliseconds

/** How far into the file `--seek` waits before it asks, so the seek comes out of running playback. */
private val SEEK_AFTER: Duration = 2.seconds

private fun format(duration: Duration): String {
    val totalMillis = duration.inWholeMilliseconds
    val minutes = totalMillis / 60_000
    val seconds = totalMillis / 1_000 % 60
    val millis = totalMillis % 1_000
    return "$minutes:${seconds.toString().padStart(2, '0')}.${millis.toString().padStart(3, '0')}"
}
