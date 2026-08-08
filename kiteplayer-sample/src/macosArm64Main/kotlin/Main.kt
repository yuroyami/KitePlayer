package io.github.yuroyami.kiteplayer.sample

import io.github.yuroyami.kiteplayer.AudioPlayback
import io.github.yuroyami.kiteplayer.ffmpeg.FFmpegAudioReader
import io.github.yuroyami.kiteplayer.output.AppleHostClock
import io.github.yuroyami.kiteplayer.output.CoreAudioSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Plays a file's audio.
 *
 * Three parts, and they are the three parts of the real player:
 *
 * - `FFmpegAudioReader` decodes, through KiteCodec.
 * - `AudioPlayback` holds the audio, paces the decoder, and maintains the clock.
 * - `CoreAudioSink` is the device. It pulls, and it reports when what it is given will be heard.
 *
 * Nothing here estimates a latency or counts samples to work out the position. The position printed
 * below comes from the clock, and the clock is anchored to the instant the device says a specific
 * frame becomes audible.
 */
fun main(args: Array<String>) {
    val path = args.firstOrNull()
    if (path == null || path == "-h" || path == "--help") {
        println("usage: kiteplayer <media file>")
        println()
        println("Plays the file's audio track and reports the position from the audio clock.")
        exitProcess(if (path == null) 2 else 0)
    }

    runBlocking {
        // Anything can fail here: a missing file, a permission error, bytes that are not media, a
        // container with no audio, a codec this FFmpeg build does not have. All of them are the same
        // thing to a user, so all of them get a sentence rather than a stack trace.
        val reader = try {
            FFmpegAudioReader.open(path)
        } catch (failure: Throwable) {
            println("cannot play $path")
            println("  ${failure.message ?: failure::class.simpleName}")
            exitProcess(1)
        }

        val sink = CoreAudioSink(AppleHostClock)
        val playback = AudioPlayback(
            sink = sink,
            clock = AppleHostClock,
            onWarning = { warning -> println("warning: ${warning.message}") },
        )

        try {
            val negotiated = playback.open(reader.format)

            println("file      $path")
            println("codec     ${reader.codec}, ${reader.sourceChannels} channel(s)")
            println("device    ${negotiated.sampleRate} Hz, ${negotiated.channels} channel(s), " +
                "${negotiated.sampleFormat}, latency ${playback.latencyQuality}")
            println("duration  ${reader.duration?.let { format(it) } ?: "unknown"}")
            println()

            var decodedChunks = 0L
            var decodedFrames = 0L
            var decodeFailure: Throwable? = null

            val feeder = launch(Dispatchers.Default) {
                try {
                    reader.chunks().collect { chunk ->
                        playback.submit(chunk.pts, chunk.interleaved, chunk.frames)
                        decodedChunks++
                        decodedFrames += chunk.frames
                    }
                } catch (failure: Throwable) {
                    decodeFailure = failure
                } finally {
                    // The decoder is done, so trailing silence is the end of the file and not an
                    // underrun. Saying so here rather than after the buffer empties is the difference
                    // between a clean report and a misleading one.
                    playback.endOfStream()
                }
            }

            // Fill before starting the device. Starting one with nothing to play is an immediate
            // underrun and an audible click, so the engine does the same thing.
            val primeTarget = 150.milliseconds
            while (playback.buffered < primeTarget && feeder.isActive) delay(5)

            playback.play()
            println("playing. press ctrl-c to stop.")

            // Baseline for the drift figure below. Taken after play() so it measures the clock's
            // behaviour during playback rather than how long opening the file took.
            val playStartedNanos = AppleHostClock.nanos()
            var basePosition: Duration? = null

            while (feeder.isActive || playback.buffered > Duration.ZERO) {
                val position = playback.position()
                val positionText = position?.let { format(it.asDuration) } ?: "  --:--.---"
                val total = reader.duration?.let { format(it) } ?: "  --:--.---"

                // Drift is what actually matters: how far the audio clock has moved compared with how
                // far real time has. A constant offset at startup is priming latency and harmless; a
                // figure that grows is a broken clock, and it is the thing to watch over a long file.
                var driftText = "     --"
                if (position != null) {
                    val elapsedWall = (AppleHostClock.nanos() - playStartedNanos).nanoseconds
                    if (basePosition == null && elapsedWall > 500.milliseconds) {
                        basePosition = position.asDuration - elapsedWall
                    }
                    basePosition?.let { base ->
                        val drift = (position.asDuration - base) - elapsedWall
                        driftText = "${drift.inWholeMilliseconds.toString().padStart(5)} ms"
                    }
                }

                print(
                    "\r  $positionText / $total   " +
                        "buffered ${playback.buffered.inWholeMilliseconds.toString().padStart(4)} ms   " +
                        "drift $driftText   " +
                        "underruns ${playback.underruns}   ",
                )
                delay(100)
            }

            playback.drain()
            println()
            println()
            println("done. decoded $decodedChunks chunks, $decodedFrames sample frames.")
            println("underruns: ${playback.underruns}")
            decodeFailure?.let { println("decode ended with: ${it.message}") }
        } finally {
            playback.close()
            reader.close()
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
