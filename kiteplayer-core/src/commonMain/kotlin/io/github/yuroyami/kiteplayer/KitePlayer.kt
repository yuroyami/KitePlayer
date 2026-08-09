package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.CoreCommand
import io.github.yuroyami.kiteplayer.internal.PlaybackCore
import io.github.yuroyami.kiteplayer.internal.platformPlaybackDispatchers
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

/**
 * The player.
 *
 * One media item at a time, played through a backend that is passed in rather than discovered. Everything
 * about what is happening is read from the four flows below; everything a caller can ask for is one of
 * the calls below. There is nothing else, and that is the point: a member here means the engine implements
 * it, and a feature that is not implemented is absent rather than present and ignored.
 *
 * ### What it is made of
 *
 * The state and the decisions live in one session actor on its own thread, with five workers on theirs:
 * demux, video decode, audio decode, audio feed, video schedule. This class is the outside of that. Every
 * call here is a message to the actor, so calling from any thread or any coroutine is safe and no two
 * callers can race each other into a state neither asked for.
 *
 * ### State against events
 *
 * [state], [progress] and [stats] are state: they conflate, and a collector that misses an intermediate
 * value still ends up correct. [events] is for occurrences that are the information themselves. Nothing a
 * caller must count is delivered as an event, and a failure is on [state] as well as in [events], because
 * a collector that subscribes after the failure would otherwise never learn of it.
 *
 * ### Errors
 *
 * A suspending call that fails throws [PlaybackException], which carries the typed [PlaybackError].
 * Cancelling the caller's coroutine stays a `CancellationException` and is never turned into a playback
 * failure. A call made in the wrong order, or with a value outside its documented range, throws
 * [IllegalStateException], [IllegalArgumentException] or [UnsupportedOperationException] instead: those
 * are mistakes in the calling code, not failures of the media or the device, and telling them apart is
 * what lets an application decide whether to show the user anything.
 *
 * ### What is not here
 *
 * External subtitles, filter chains, an advanced option escape hatch, chapters, a playlist or queue,
 * frame stepping and stereo balance are all absent rather than stubbed. The configuration members that
 * describe them carry a marker in their own documentation pointing at the roadmap in KPKMP.md section 11.
 */
public class KitePlayer internal constructor(private val core: PlaybackCore) : AutoCloseable {

    /** Everything about the player that changes rarely. Position is deliberately not in it. */
    public val state: StateFlow<PlayerSnapshot> = core.snapshots

    /** Position and buffered extent, republished on `PlayerConfig.progressInterval`. */
    public val progress: StateFlow<Progress> = core.progress

    /** Diagnostics, republished on `PlayerConfig.statsInterval`. */
    public val stats: StateFlow<PlaybackStats> = core.stats

    /** Warnings, failures and the occurrences worth naming. Replays nothing to a late collector. */
    public val events: SharedFlow<PlayerEvent> = core.events

    /**
     * The position now, without waiting for the next [progress] sample.
     *
     * Read from a value the session actor publishes on every pass, so it costs one atomic read and is
     * never more than the loop's own wake floor out of date. This is what a seek bar being dragged reads;
     * [progress] is what a label bound to a screen collects.
     */
    public fun position(): Duration = core.position()

    /**
     * Opens [media] and returns once the first frame is on screen and the player is paused on it.
     *
     * Legal from Idle, Ended and Failed. From anything else it throws: replacing what is playing needs an
     * explicit [stop] first, so that a caller cannot half replace a session by accident. Cancelling this
     * call leaves the player Idle rather than half open.
     *
     * @throws PlaybackException when the media cannot be reached, is not media, holds nothing this build
     *         can decode, or no audio device could be opened for a file with no video to fall back on.
     */
    public suspend fun open(media: MediaItem) {
        core.open(media)
    }

    /**
     * Asks for playback, and returns at once.
     *
     * Idempotent, and remembered rather than refused while an open or a seek is still running: playback
     * starts as soon as every selected stream can supply it. A caller that wants to know when that
     * happened watches [state].
     */
    public fun play() {
        core.play()
    }

    /**
     * Asks for a pause, and returns at once.
     *
     * The engine freezes its clocks only after the device is quiet and its last anchor has been consumed,
     * so resuming does not jump.
     */
    public fun pause() {
        core.pause()
    }

    /**
     * Seeks and returns when the target frame is on screen, or when a later request replaced this one.
     *
     * A replaced request is not a failure: the position moved, just not to where this call asked for.
     * Concurrent callers all complete exactly once. [PlayerEvent.SeekCompleted] carries where the seek
     * landed, which for [SeekMode.Precise] is the first frame at or after the target.
     *
     * @param to a finite position at or after zero. Past the end of the media it is clamped to the end.
     * @throws IllegalArgumentException when [to] is infinite or negative.
     * @throws IllegalStateException when nothing is open.
     * @throws UnsupportedOperationException when the source is not seekable.
     */
    public suspend fun seek(to: Duration, mode: SeekMode = SeekMode.Precise) {
        core.seek(Pts.ofDuration(validPosition(to, "seek")), mode)
    }

    /**
     * Seeks without waiting, coalescing requests that arrive faster than the pipeline can serve them.
     *
     * This is what a seek bar being dragged calls sixty times a second. Requests merge by the rules the
     * engine documents, and one that is superseded is dropped rather than queued, so dragging costs one
     * flush cycle and not sixty. Nothing is thrown when the source cannot seek: a fire-and-forget call has
     * nobody to throw to, so it is ignored. Use [seek] when the answer matters.
     *
     * @param to a finite position at or after zero.
     * @throws IllegalArgumentException when [to] is infinite or negative.
     */
    public fun seekLater(to: Duration, mode: SeekMode = SeekMode.KeyframeThenRefine) {
        core.seekLater(Pts.ofDuration(validPosition(to, "seekLater")), mode)
    }

    /**
     * Stops playback, tears the session down and returns to Idle.
     *
     * Preempts an open, a seek or a drain that is still running. Idempotent.
     */
    public suspend fun stop() {
        core.stop()
    }

    /**
     * Sets the playback rate as a multiplier of real time.
     *
     * **While an audio track is selected the only accepted value is 1.0.** Anything else throws, because
     * there is no tempo stage: the samples would still reach the device at the device's own rate, so the
     * sound would play at normal speed while the clock and every frame timed against it ran at another. A
     * player that accepts the value and does nothing with it is worse than one that refuses, because the
     * caller cannot tell which it got. Video-only playback accepts any rate. Real speed control with pitch
     * preserved is on the roadmap in KPKMP.md section 11.
     *
     * @param value finite and greater than zero.
     * @throws IllegalArgumentException when [value] is not finite and positive. Infinity passes a plain
     *         positivity test, which is why the check is explicit.
     * @throws UnsupportedOperationException when an audio track is selected and [value] is not 1.0.
     */
    public fun setSpeed(value: Double) {
        require(value.isFinite() && value > 0.0) { "speed must be finite and positive, was $value" }
        if (value != 1.0 && state.value.tracks.selectedAudio != null) {
            throw UnsupportedOperationException(
                "audio playback runs at 1.0 only: there is no tempo stage, so a rate of $value would move " +
                    "the clock without moving the sound. Select no audio track to change the rate; see " +
                    "KPKMP.md section 11",
            )
        }
        core.post(CoreCommand.SetSpeed(value, CompletableDeferred()))
    }

    /**
     * Sets the volume, from silence at 0 to unity at 1.
     *
     * Applied by the audio pipeline's gain stage as one multiply per sample over a short ramp, so a change
     * never clicks. Above unity is amplification and is refused rather than clipped.
     *
     * @throws IllegalArgumentException when [value] is not finite or is outside 0 to 1.
     */
    public fun setVolume(value: Float) {
        require(value.isFinite() && value >= 0f && value <= 1f) { "volume must be between 0 and 1, was $value" }
        core.post(CoreCommand.SetVolume(value, CompletableDeferred()))
    }

    /** Silences the sound without losing the [setVolume] setting. Ramped the same way. */
    public fun setMuted(value: Boolean) {
        core.post(CoreCommand.SetMuted(value, CompletableDeferred()))
    }

    /**
     * Sets what happens at the end of the media.
     *
     * @throws IllegalArgumentException for [LoopMode.All], which repeats a queue. There is no queue and no
     *         playlist, so it is refused rather than quietly behaving like [LoopMode.Off]: a caller can
     *         then tell the difference between the mode it asked for and the mode it got. See KPKMP.md
     *         section 11.
     */
    public fun setLoop(mode: LoopMode) {
        require(mode != LoopMode.All) {
            "LoopMode.All repeats a queue and there is no queue; see KPKMP.md section 11"
        }
        core.post(CoreCommand.SetLoop(mode, CompletableDeferred()))
    }

    /**
     * Selects a track, or deselects the kind entirely with a null [track].
     *
     * Video and audio only. Switching reopens the container and seeks back to where playback was, because
     * the demuxer permits its stream selection to be set once before the first read, so it is legal only
     * while a media item is open and only when the source reports itself seekable. Seamless switching is
     * on the roadmap in KPKMP.md section 11.
     *
     * @throws IllegalStateException when nothing is open.
     * @throws UnsupportedOperationException for [TrackKind.Subtitle], which no decoder in this build can
     *         produce, and for a source that cannot seek.
     */
    public suspend fun selectTrack(kind: TrackKind, track: TrackId?) {
        core.selectTrack(kind, track)
    }

    /**
     * Attaches a renderer, or replaces the one attached. Legal at any time, including while playing.
     *
     * Video decoding never depends on a renderer existing. With none attached the schedule still paces
     * and releases frames, counting them as `PlaybackStats.headlessFrames`, so detaching costs the picture
     * and nothing else.
     *
     * The call returns as soon as the request is queued. The engine parks its scheduler before it swaps
     * renderers, so no submission for the old one is outstanding once the swap has happened, but this call
     * does not wait for that moment. A caller that must know its renderer is idle closes the player, or
     * relies on the renderer's own close being safe against a submission in flight, which the one in
     * `kiteplayer-output` is.
     */
    public fun attachRenderer(renderer: VideoRenderer) {
        core.post(CoreCommand.AttachRenderer(renderer, CompletableDeferred()))
    }

    /** Detaches the current renderer. Playback continues without a picture. See [attachRenderer]. */
    public fun detachRenderer() {
        core.post(CoreCommand.DetachRenderer(CompletableDeferred()))
    }

    /**
     * Closes the player. Terminal, idempotent, and returns at once.
     *
     * Every outstanding call is completed, the workers are stopped, the decoders and the device are closed
     * on the threads that own them, and those threads are released. The teardown itself is bounded: a
     * native call that has wedged cannot be killed from inside the process, so a teardown that exceeds its
     * deadline reports [PlaybackError.RuntimeCompromised] through [events] and [state] rather than
     * pretending to have succeeded.
     */
    override fun close() {
        core.close()
    }

    private fun validPosition(to: Duration, name: String): Duration {
        require(to.isFinite() && to >= Duration.ZERO) {
            "$name needs a finite position at or after zero, was $to"
        }
        return to
    }

    public companion object {
        /**
         * Builds a player from [config].
         *
         * The backends in [PlayerConfig.backends] are resolved here, and nothing is discovered: Kotlin's
         * native targets have no classpath service lookup, so a missing backend is a typed configuration
         * error rather than a reflective search that would fail differently on every platform. On macOS the
         * explicit pair is `KiteCodecMediaBackend()` from `kiteplayer-ffmpeg` and `AppleOutputBackend` from
         * `kiteplayer-output`; the engine never names either, which is what keeps it free of any platform.
         *
         * The player owns six threads from here until [close], one for the session actor and one for each
         * worker, because that is the confinement every contract inside the engine is written against.
         *
         * @throws PlaybackException with [PlaybackError.ConfigurationInvalid] when no media backend or no
         *         output backend was supplied.
         */
        public fun create(config: PlayerConfig = PlayerConfig()): KitePlayer {
            val backend = config.backends.backend ?: throw PlaybackException(
                PlaybackError.ConfigurationInvalid(
                    "no media backend was supplied in PlayerConfig.backends.backend, and there is nothing " +
                        "to discover one with. On macOS pass KiteCodecMediaBackend() from kiteplayer-ffmpeg",
                ),
            )
            val output = config.backends.output ?: throw PlaybackException(
                PlaybackError.ConfigurationInvalid(
                    "no output backend was supplied in PlayerConfig.backends.output, so there is no clock " +
                        "and no audio device. On macOS pass AppleOutputBackend from kiteplayer-output",
                ),
            )
            return KitePlayer(
                PlaybackCore(
                    config = config,
                    backend = backend,
                    output = output,
                    dispatchers = platformPlaybackDispatchers(),
                ),
            )
        }
    }
}
