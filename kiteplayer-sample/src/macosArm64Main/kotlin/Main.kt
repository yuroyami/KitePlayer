package io.github.yuroyami.kiteplayer.sample

import io.github.yuroyami.kiteplayer.AudioPlayback
import io.github.yuroyami.kiteplayer.FrameDropPolicy
import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.VideoPlayback
import io.github.yuroyami.kiteplayer.ffmpeg.KiteCodecSource
import io.github.yuroyami.kiteplayer.ffmpeg.KiteCodecSourceFactory
import io.github.yuroyami.kiteplayer.ffmpeg.KiteCodecVideoFrame
import io.github.yuroyami.kiteplayer.ffmpeg.SoftwareConverter
import io.github.yuroyami.kiteplayer.ffmpeg.interleavedFloat
import io.github.yuroyami.kiteplayer.output.AppleHostClock
import io.github.yuroyami.kiteplayer.output.AppKitVideoRenderer
import io.github.yuroyami.kiteplayer.output.AppKitWindow
import io.github.yuroyami.kiteplayer.output.CoreAudioSink
import io.github.yuroyami.kiteplayer.spi.PlayerPacket
import io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Plays a file, with video and audio in sync.
 *
 * The parts are the player's parts:
 *
 * - `KiteCodecSource` demuxes, and one decoder per stream decodes, both through KiteCodec.
 * - `AudioPlayback` holds the audio, paces its decoder, and maintains the master clock.
 * - `VideoPlayback` decides when each frame should be shown, and whether to drop or repeat it.
 * - `CoreAudioSink` is the device, and it reports when what it is handed will be heard.
 *
 * The wiring below, meaning the channels between demuxing and decoding, is what `PlaybackCore` will
 * own once it is written. Everything it wires together already exists and is tested, so this file is
 * the proof that the pieces fit and the place the measured numbers are read off.
 *
 * Nothing here estimates a device latency or counts samples to work out the position. The position and
 * the drift both come from the clock, and the clock is anchored to the instant CoreAudio says a
 * specific frame becomes audible.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
fun main(args: Array<String>) {
    val path = args.firstOrNull { !it.startsWith("--") }
    if (path == null || args.contains("-h") || args.contains("--help")) {
        println("usage: kiteplayer <media file> [--window] [--no-video]")
        println()
        println("Plays the file and reports the position, the audio to video drift, and the frame")
        println("accounting, all taken from the engine's own clocks.")
        println()
        println("  --window    open a window and draw the video in it")
        println("  --no-video  play the audio track only")
        exitProcess(if (path == null) 2 else 0)
    }
    val videoEnabled = !args.contains("--no-video")
    val wantWindow = args.contains("--window")

    // Anything can fail here: a missing file, a permission error, bytes that are not media, a codec
    // this FFmpeg build does not have. All of them are the same thing to a user, so all of them get a
    // sentence rather than a stack trace.
    val source = try {
        runBlocking { KiteCodecSourceFactory().open(MediaItem(path)) as KiteCodecSource }
    } catch (failure: Throwable) {
        println("cannot play $path")
        println("  ${failure.message ?: failure::class.simpleName}")
        exitProcess(1)
    }

    val videoStream = if (videoEnabled) source.firstVideo else null
    val audioStream = source.firstAudio
    if (videoStream == null && audioStream == null) {
        println("cannot play $path")
        println("  nothing playable in it")
        source.close()
        exitProcess(1)
    }

    if (wantWindow && videoStream != null) {
        // AppKit owns the main thread and its run loop, so the window is built here and playback runs
        // on another thread. Doing it the other way round means either no events or no playback.
        val size = videoStream.videoSize
        val window = AppKitWindow(
            title = path.substringAfterLast('/'),
            width = (size?.displayWidth ?: 1280).coerceAtMost(1600),
            height = (size?.height ?: 720).coerceAtMost(1000),
        )
        val renderer = AppKitVideoRenderer(window) { frame ->
            SoftwareConverter.toRgba(frame as KiteCodecVideoFrame)
        }
        val sessionContext = newSingleThreadContext("kiteplayer-session")
        CoroutineScope(sessionContext).launch {
            try {
                runSession(path, source, videoStream, audioStream, renderer)
            } finally {
                println("  window drew       ${renderer.presentedFrames} frames")
                println("  superseded        ${renderer.supersededFrames} (renderer was the bottleneck)")
                println("  conversion failed ${renderer.failedFrames}")
                window.stop()
            }
        }
        window.runEventLoop()
        return
    }

    runBlocking {
        runSession(path, source, videoStream, audioStream, CountingRenderer(AppleHostClock))
    }
}

/**
 * Plays [source] to completion, reporting as it goes.
 *
 * Everything here runs off the main thread, so it works the same whether the frames end up in a window
 * or in a counter.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
private suspend fun runSession(
    path: String,
    source: KiteCodecSource,
    videoStream: PlayerStreamInfo?,
    audioStream: PlayerStreamInfo?,
    videoRenderer: VideoRenderer,
) {
    coroutineScope {
        val sink = CoreAudioSink(AppleHostClock)
        val audio = AudioPlayback(sink, AppleHostClock, onWarning = { println("warning: ${it.message}") })
        val video = if (videoStream != null) {
            VideoPlayback(
                renderer = videoRenderer,
                clock = AppleHostClock,
                containerFrameRate = videoStream.frameRate,
                timestampsMayJump = source.timestampsMayJump,
                dropPolicy = FrameDropPolicy.LateOnly,
            )
        } else {
            null
        }

        // One dispatcher per worker. A decoder context must be touched by one thread only, which is
        // what libavcodec expects, and the demuxer cursor is single threaded for the same reason.
        val demuxContext = newSingleThreadContext("kiteplayer-demux")
        val videoContext = newSingleThreadContext("kiteplayer-video")
        val audioContext = newSingleThreadContext("kiteplayer-audio")

        try {
            println("file      $path")
            videoStream?.let {
                val fps = it.frameRate?.let { r -> ((r * 1000).toInt() / 1000.0).toString() } ?: "?"
                println("video     ${it.codec}, ${it.videoSize?.width}x${it.videoSize?.height}, $fps fps")
            }
            audioStream?.let { println("audio     ${it.codec}, ${it.sampleRate} Hz, ${it.channels} channel(s)") }
            println("duration  ${source.duration?.let { format(it.asDuration) } ?: "unknown"}")

            if (audioStream != null) {
                val decoderFormat = source.audioDecoderFactories().first().create(audioStream)!!
                    .let { probe -> probe.outputFormat.also { probe.close() } }
                val negotiated = audio.open(decoderFormat)
                println(
                    "device    ${negotiated.sampleRate} Hz, ${negotiated.channels} channel(s), " +
                        "latency ${audio.latencyQuality}",
                )
            }
            println()

            source.selectStreams(setOfNotNull(videoStream?.index, audioStream?.index))

            // Bounded, so the demuxer cannot read the whole file into memory, and separate per stream,
            // so a full video queue never stops audio from being read. That is the interleaving
            // deadlock every player hits once. See KITEPLAYER.md section 11.4.
            val videoPackets = Channel<PlayerPacket>(capacity = 64)
            val audioPackets = Channel<PlayerPacket>(capacity = 64)

            val demux = launch(demuxContext) {
                try {
                    while (true) {
                        val packet = source.readPacket() ?: break
                        when (packet.streamIndex) {
                            videoStream?.index -> videoPackets.send(packet)
                            audioStream?.index -> audioPackets.send(packet)
                            else -> packet.close()
                        }
                    }
                } finally {
                    videoPackets.close()
                    audioPackets.close()
                }
            }

            var decodedVideo = 0L
            val videoDecode = if (videoStream != null && video != null) {
                launch(videoContext) {
                    val decoder = source.videoDecoderFactories().first()
                        .create(videoStream, HwdecPolicy.Auto)!!
                    try {
                        for (packet in videoPackets) {
                            // False means the decoder is full and the packet was NOT consumed, so it
                            // is offered again after draining rather than discarded.
                            while (!decoder.send(packet, Generation.Initial)) {
                                val frame = decoder.receive() ?: break
                                decodedVideo++
                                video.submit(frame)
                            }
                            packet.close()
                            while (true) {
                                val frame = decoder.receive() ?: break
                                decodedVideo++
                                video.submit(frame)
                            }
                        }
                        decoder.send(null, Generation.Initial)
                        while (true) {
                            val frame = decoder.receive() ?: break
                            decodedVideo++
                            video.submit(frame)
                        }
                    } finally {
                        decoder.close()
                    }
                }
            } else {
                null
            }

            var decodedAudio = 0L
            val audioDecode = if (audioStream != null) {
                launch(audioContext) {
                    val decoder = source.audioDecoderFactories().first().create(audioStream)!!
                    try {
                        for (packet in audioPackets) {
                            while (!decoder.send(packet, Generation.Initial)) {
                                val buffer = decoder.receive() ?: break
                                audio.submit(buffer.pts, buffer.interleavedFloat(), buffer.frameCount)
                                buffer.close()
                                decodedAudio++
                            }
                            packet.close()
                            while (true) {
                                val buffer = decoder.receive() ?: break
                                audio.submit(buffer.pts, buffer.interleavedFloat(), buffer.frameCount)
                                buffer.close()
                                decodedAudio++
                            }
                        }
                        decoder.send(null, Generation.Initial)
                        while (true) {
                            val buffer = decoder.receive() ?: break
                            audio.submit(buffer.pts, buffer.interleavedFloat(), buffer.frameCount)
                            buffer.close()
                            decodedAudio++
                        }
                    } finally {
                        decoder.close()
                        // The decoder is done, so trailing silence is the end of the file and not an
                        // underrun. Saying so here rather than after the buffer empties is the
                        // difference between a clean report and a misleading one.
                        audio.endOfStream()
                    }
                }
            } else {
                null
            }

            // Fill before starting. A device started with nothing to play underruns immediately and
            // clicks, which is why the engine primes first too.
            if (audioStream != null) {
                while (audio.buffered < 150.milliseconds && audioDecode?.isActive == true) delay(5)
                audio.play()
            }

            // The presentation loop: ask the master clock what time it is, and let VideoPlayback decide
            // what to do with the next frame.
            val present = if (video != null) {
                launch(Dispatchers.Default) {
                    while (isActive) {
                        val wait = video.tick(audio.position())
                        if (wait > Duration.ZERO) delay(wait)
                    }
                }
            } else {
                null
            }

            println("playing. press ctrl-c to stop.")
            val startedAt = AppleHostClock.nanos()
            var basePosition: Duration? = null

            while (
                demux.isActive || audioDecode?.isActive == true || videoDecode?.isActive == true ||
                audio.buffered > Duration.ZERO || (video?.queuedFrames ?: 0) > 0
            ) {
                val position = audio.position() ?: video?.position()
                val positionText = position?.let { format(it.asDuration) } ?: "  --:--.---"
                val total = source.duration?.let { format(it.asDuration) } ?: "  --:--.---"

                // Clock drift against real time. A constant offset at startup is priming latency and
                // harmless. A figure that grows means the clock is wrong, and over a long file that is
                // the number to watch.
                var clockDrift = "     --"
                if (position != null) {
                    val elapsed = (AppleHostClock.nanos() - startedAt).nanoseconds
                    if (basePosition == null && elapsed > 500.milliseconds) {
                        basePosition = position.asDuration - elapsed
                    }
                    basePosition?.let { base ->
                        val d = (position.asDuration - base) - elapsed
                        clockDrift = "${d.inWholeMilliseconds.toString().padStart(5)} ms"
                    }
                }

                val videoPart = video?.let {
                    "a/v ${it.drift.inWholeMilliseconds.toString().padStart(4)} ms   " +
                        "shown ${it.presentedFrames}   dropped ${it.droppedFrames}   " +
                        "repeated ${it.repeatedFrames}   "
                } ?: ""

                print(
                    "\r  $positionText / $total   clock $clockDrift   $videoPart" +
                        "audio ${audio.buffered.inWholeMilliseconds.toString().padStart(4)} ms   " +
                        "underruns ${audio.underruns}   ",
                )
                delay(200)
            }

            present?.cancel()
            audio.drain()

            println()
            println()
            println("done.")
            println("  decoded          $decodedVideo video frames, $decodedAudio audio buffers")
            video?.let {
                println("  presented        ${it.presentedFrames} frames")
                println("  dropped late     ${it.droppedFrames}")
                println("  repeated         ${it.repeatedFrames}")
                println("  final a/v drift  ${it.drift.inWholeMilliseconds} ms")
            }
            (videoRenderer as? CountingRenderer)?.let {
                println("  renderer got     ${it.count} frames")
                println("  worst schedule   ${it.worstErrorMillis} ms from the requested time")
            }
            println("  audio underruns  ${audio.underruns}")
        } finally {
            audio.close()
            video?.close()
            source.close()
            demuxContext.close()
            videoContext.close()
            audioContext.close()
        }
    }
}

private fun format(duration: Duration): String {
    val totalMillis = duration.inWholeMilliseconds
    val minutes = totalMillis / 60_000
    val seconds = totalMillis / 1_000 % 60
    val millis = totalMillis % 1_000
    return "$minutes:${seconds.toString().padStart(2, '0')}.${millis.toString().padStart(3, '0')}"
}
