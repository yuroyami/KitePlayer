package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.CoreCommand
import io.github.yuroyami.kiteplayer.internal.PlaybackCore
import io.github.yuroyami.kiteplayer.internal.SeekResult
import io.github.yuroyami.kiteplayer.internal.platformPlaybackDispatchers
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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
 * demux, video decode, audio decode, audio feed, video schedule. This class is the outside of that.
 * Accepted state-changing commands are actor messages: awaited calls carry one reply each, while
 * fire-and-forget calls discard or omit theirs. The two close routes instead share one terminal result. After the actor
 * returns, its independent close finalizer alone publishes the terminal snapshot and result. Calling from
 * any thread or coroutine is safe and no two callers can race each other into a state neither asked for.
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
 * Shuffle, a gapless queue handoff and a secondary subtitle track are absent rather than stubbed;
 * MASTER_PLAN.md carries them with what each one needs.
 *
 * External subtitles, filter chains, the open-option escape hatch, chapters, the queue and frame
 * stepping were on this list and are all here now: [addExternalSubtitle], [MediaItem.videoFilter],
 * [MediaItem.openOptions], [chapterAt] with [seekToChapter], [openQueue] with [next] and
 * [previous], [stepFrame] and [setBalance]. A member that describes something still unbuilt says
 * so in its own documentation.
 */
public class KitePlayer internal constructor(private val core: PlaybackCore) : AutoCloseable {

    /** Everything about the player that changes rarely. Position is deliberately not in it. */
    public val state: StateFlow<PlayerSnapshot> = core.snapshots

    /** Position and buffered extent, republished on `PlayerConfig.progressInterval`. */
    public val progress: StateFlow<Progress> = core.progress

    /** Diagnostics, republished on `PlayerConfig.statsInterval`. */
    public val stats: StateFlow<PlaybackStats> = core.stats

    /**
     * The subtitle cues showing right now, in draw order. Empty when none are.
     *
     * Its own flow rather than a field on [state], because it changes on every cue edge, which on a
     * dense track is several times a second: a consumer watching the track list or the status would
     * otherwise be woken at that rate for a value it never reads.
     *
     * This is what the renderer is drawing, published from the same decision, so the two cannot
     * disagree. It is for an application that wants to draw its own subtitles, read them aloud, log
     * them, or show them somewhere other than over the video.
     */
    public val subtitleCues: StateFlow<List<io.github.yuroyami.kiteplayer.subtitle.SubtitleCue>> =
        core.subtitleCues

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
     * @throws IllegalStateException when nothing is open, or when the seek had to be aborted
     *         because the pipeline could not be brought to a safe boundary in time. An aborted
     *         seek changed nothing: playback continues at the old position.
     * @throws UnsupportedOperationException when the source is not seekable.
     */
    public suspend fun seek(to: Duration, mode: SeekMode = SeekMode.Precise) {
        val result = core.seek(Pts.ofDuration(validPosition(to, "seek")), mode)
        if (result is SeekResult.Rejected) throw IllegalStateException(result.reason)
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
     * @throws IllegalStateException after terminal close has been requested.
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
     * Sets the playback rate as a multiplier of real time, within [SPEED_MIN] to [SPEED_MAX].
     *
     * Real, on both halves. Audio runs through a pitch-synchronous tempo stage, so 2x is twice
     * as fast at the same pitch rather than a chipmunk; the video schedule divides its frame
     * durations by the rate; and the shared clock extrapolates at the rate, so audio, video and
     * the reported position agree at every speed, including for video-only media.
     *
     * A change while media is playing re-anchors through an internal precise seek at the current
     * position, which sounds like the small rebuffer it is. That is also why a live change on an
     * UNSEEKABLE source is refused rather than half-applied: without a seek there is no boundary
     * at which the queued audio at the old rate ends and the new rate begins. On such sources
     * set the speed before [open].
     *
     * @param value finite, within [SPEED_MIN] to [SPEED_MAX].
     * @throws IllegalArgumentException outside that range. Below and above it, time-stretch
     *         splices dominate the signal, and a player that pretends otherwise is lying.
     *
     * A live change on an unseekable source is refused and the refusal is published as a
     * [PlaybackWarning.CommandRefused] on [events] and the warning history: this member does
     * not wait for the engine, so a throw could never reach its caller.
     */
    public fun setSpeed(value: Double) {
        require(value.isFinite() && value >= SPEED_MIN && value <= SPEED_MAX) {
            "speed must be within $SPEED_MIN..$SPEED_MAX, was $value"
        }
        core.post(CoreCommand.SetSpeed(value, CompletableDeferred()))
    }

    /**
     * Chooses whether [setSpeed] keeps pitch.
     *
     * True, the default, runs the tempo stage: 2x sounds like faster speech at its own pitch.
     * False folds the rate into the resampler instead, which is cheaper by a whole
     * time-stretch pass and shifts pitch with the rate, exactly mpv's
     * `audio-pitch-correction=no`: 2x sounds a whole octave up. Musicians slowing a piece down
     * to transcribe it often want the false setting; everyone else wants the default.
     *
     * Seeded from [AudioConfig.preservePitch]. A live change away from 1.0 speed rides the
     * same internal precise seek a speed change does, for the same epoch reason, and is refused
     * the same way on an unseekable source; at 1.0 the two mechanisms are the same bypass and
     * the change is free. Published as [PlayerSnapshot.preservePitch].
     */
    public fun setPreservePitch(value: Boolean) {
        core.post(CoreCommand.SetPreservePitch(value, CompletableDeferred()))
    }

    /**
     * Sets the volume, from silence at 0 to unity at 1, and up to
     * [AudioConfig.volumeCeiling] when that has been raised to allow a boost.
     *
     * Applied as one multiply per sample as frames leave the ring, over a short ramp, so a change
     * never clicks and is audible within one device period. Above unity each sample is folded
     * through a saturator rather than allowed to clip, so a boosted loud passage compresses
     * instead of squaring off; at or below unity nothing is folded.
     *
     * The ceiling is 1 by default, which refuses every boost: amplifying without being asked is
     * not a player's decision. Raise [AudioConfig.volumeCeiling] to offer one.
     *
     * @throws IllegalArgumentException when [value] is not finite or is outside 0 to the ceiling.
     */
    public fun setVolume(value: Float) {
        val ceiling = core.volumeCeiling
        require(value.isFinite() && value >= 0f && value <= ceiling) {
            "volume must be between 0 and $ceiling, was $value"
        }
        core.post(CoreCommand.SetVolume(value, CompletableDeferred()))
    }

    /**
     * Sets the stereo balance: -1 is hard left, 0 is centre, 1 is hard right.
     *
     * An attenuation of the channel being turned away from, never a boost of the other, so a
     * balanced track can never be louder than the same track centred. Only the first two channels
     * move; panning a centre or surround channel is a different feature.
     *
     * A change is heard after the audio already buffered has drained, which is the ring's depth:
     * at least 200 ms, and longer on Android where the device's own buffer sets it. That is the
     * cost of applying it on the way IN, and it is the right trade here because a balance is set
     * once and left, unlike the volume, which lives on the ring's read side precisely so that it
     * is heard within one device period.
     *
     * @throws IllegalArgumentException when [value] is not finite or is outside -1 to 1.
     */
    public fun setBalance(value: Float) {
        require(value.isFinite() && value >= -1f && value <= 1f) {
            "balance must be between -1 and 1, was $value"
        }
        core.post(CoreCommand.SetBalance(value, CompletableDeferred()))
    }

    /**
     * Parks or resumes video decoding in place, without reopening anything.
     *
     * Parked, video packets are discarded before the decoder and the picture freezes on the last
     * frame; audio keeps playing and subtitles keep timing, because the container is still being
     * read. This is what an application going to the background wants: the alternative,
     * deselecting the video track, reopens the container and seeks back, which on a network source
     * is a fresh request nobody asked for.
     *
     * Resumed, decoding restarts at a keyframe. On a seekable source the engine seeks precisely to
     * where playback already is, so the picture returns at the right frame rather than at whatever
     * the container keys next; an unseekable source waits for that next keyframe.
     *
     * Parked frames are not counted as drops. A drop means the engine could not keep up, and this
     * is a decision.
     */
    public fun setVideoEnabled(enabled: Boolean) {
        core.post(CoreCommand.SetVideoEnabled(enabled, CompletableDeferred()))
    }

    /**
     * Stops playback later, fading the sound down first. Null cancels an armed timer.
     *
     * The fade is the engine's own and rides the ring's gain, so it never touches the volume the
     * user set: the published value is unchanged throughout, and the level is restored the moment
     * the player pauses. That last part is what every hand-written sleep timer gets wrong, and it
     * is why the next play is not silent.
     *
     * [SleepTimer.After] counts wall time while playback is advancing, so a player paused for an
     * hour does not sleep through its own timer. [SleepTimer.At] fires at a media position.
     * [SleepTimer.EndOfItem] fires when the current item ends and does not advance a queue past it.
     *
     * @throws IllegalArgumentException when [fade] is negative, or an [SleepTimer.After] is not in the future.
     */
    public fun setSleepTimer(timer: SleepTimer?, fade: Duration = DEFAULT_SLEEP_FADE) {
        core.post(CoreCommand.SetSleepTimer(timer, fade, CompletableDeferred()))
    }

    /**
     * Sets the ten-band equaliser. [EqualizerSettings.Flat] turns it off.
     *
     * Flat is not just neutral, it is free: the filters are skipped entirely rather than run with
     * coefficients that happen to be the identity, so a player nobody has equalised pays nothing.
     *
     * A change swaps coefficients at the next buffer. The one buffer of transient that costs is
     * inaudible, and interpolating twenty coefficients per sample to avoid it would cost more than
     * the filter does.
     */
    public fun setEqualizer(settings: EqualizerSettings) {
        core.post(CoreCommand.SetEqualizer(settings, CompletableDeferred()))
    }

    /** Silences the sound without losing the [setVolume] setting. Ramped the same way. */
    public fun setMuted(value: Boolean) {
        core.post(CoreCommand.SetMuted(value, CompletableDeferred()))
    }

    /**
     * Sets what happens at the end of the media.
     *
     * [LoopMode.All] repeats the open queue (S4.e), wrapping from the last item to the first.
     * With a queue of one, or plain [open]-ed media, it repeats the current item exactly like
     * [LoopMode.One], which is what a whole queue of one means.
     */
    public fun setLoop(mode: LoopMode) {
        core.post(CoreCommand.SetLoop(mode, CompletableDeferred()))
    }

    /**
     * Plays the queue in a shuffled order, or puts it back in the order it was given.
     *
     * Shuffle is an order OVER the queue, not a reorder OF it. The items in
     * [PlayerSnapshot.queue] never move, so the list an application shows stays the list someone
     * built, and turning shuffle off needs nothing put back. What moves is
     * [PlayerSnapshot.queueOrder], which [next], [previous] and the advance at the end of an item
     * all follow.
     *
     * Turning it on leaves the item that is playing playing, and makes it first in the new order.
     * An item added later joins the order somewhere after the one playing rather than always
     * last. [seed] makes the order reproducible, which is what a test or a shared session needs.
     */
    public fun setShuffle(enabled: Boolean, seed: Long? = null) {
        core.post(CoreCommand.SetShuffle(enabled, seed, CompletableDeferred()))
    }

    /**
     * Arms or clears the A-B loop: while armed, playback that reaches [b] jumps straight back to
     * [a] and keeps going, which is how a phrase is practised and a scene is studied frame by
     * frame. Independent of [setLoop]; while armed, the A-B loop owns the end of the region and
     * the end of the media both.
     *
     * With [b] null the loop runs from [a] to the end of the media and wraps back to [a]. Both
     * null clears the loop. The armed points are published as [PlayerSnapshot.abLoopA] and
     * [PlayerSnapshot.abLoopB], and they belong to the player, not the media: like [setSpeed]
     * they survive seeks and the next [open].
     *
     * The jump back is an ordinary precise seek, so the loop needs a seekable source; arming it
     * while an unseekable one plays is refused.
     *
     * @throws IllegalArgumentException when a point is negative, when [b] alone is given, or
     *         when [b] is not after [a].
     */
    public fun setAbLoop(a: Duration?, b: Duration? = null) {
        require(a != null || b == null) { "an A-B loop needs its A; B alone is not a loop" }
        require(a == null || a >= Duration.ZERO) { "A must not be negative, was $a" }
        require(a == null || b == null || b > a) { "B must be after A: A=$a B=$b" }
        core.post(CoreCommand.SetAbLoop(a, b, CompletableDeferred()))
    }

    /**
     * Sets how the picture occupies its surface: whole picture letterboxed ([VideoScale.Fit],
     * the default), viewport filled with the overhang cropped ([VideoScale.Fill]), or both axes
     * stretched independently ([VideoScale.Stretch]).
     *
     * Legal at any time, renderer attached or not; the mode belongs to the player and every
     * renderer is told it on attach. Pixel aspect and container rotation are honoured in every
     * mode. The current mode is published as [PlayerSnapshot.videoScale].
     */
    public fun setVideoScale(mode: VideoScale) {
        core.post(CoreCommand.SetVideoScale(mode, CompletableDeferred()))
    }

    /**
     * Sets the live picture controls: brightness, contrast, saturation and hue, mpv's `eq`.
     *
     * Legal at any time, renderer attached or not, exactly like [setVideoScale]: the value
     * belongs to the player, every renderer is told it on attach, and a change lands on the very
     * next drawn frame with no decoder or pipeline work at all. Pass
     * [VideoAdjustments.Identity] to reset. Published as [PlayerSnapshot.videoAdjustments].
     *
     * @throws IllegalArgumentException outside the documented ranges: brightness -1..1,
     *         contrast 0..2, saturation 0..2, hue -180..180, all finite.
     */
    public fun setVideoAdjustments(value: VideoAdjustments) {
        require(value.brightness.isFinite() && value.brightness in -1f..1f) {
            "brightness must be within -1..1, was ${value.brightness}"
        }
        require(value.contrast.isFinite() && value.contrast in 0f..2f) {
            "contrast must be within 0..2, was ${value.contrast}"
        }
        require(value.saturation.isFinite() && value.saturation in 0f..2f) {
            "saturation must be within 0..2, was ${value.saturation}"
        }
        require(value.hueDegrees.isFinite() && value.hueDegrees in -180f..180f) {
            "hue must be within -180..180 degrees, was ${value.hueDegrees}"
        }
        core.post(CoreCommand.SetVideoAdjustments(value, CompletableDeferred()))
    }

    /**
     * Sets how much work the renderer spends on the picture beyond decoding it correctly (17.21):
     * dithering, debanding, and which kernel resamples the frame.
     *
     * Delivered exactly like [setVideoAdjustments]: the value belongs to the player, every renderer
     * is told it on attach, and a change lands on the next drawn frame with no pipeline work. A
     * renderer honours what it can and ignores the rest, so this never fails and never blocks.
     * [RenderQuality.Off] reproduces the pre-17.21 picture byte for byte.
     *
     * @throws IllegalArgumentException on a non-finite or negative debanding parameter.
     */
    public fun setRenderQuality(value: RenderQuality) {
        require(value.debandThreshold.isFinite() && value.debandThreshold >= 0f) {
            "the deband threshold must be finite and not negative, was ${value.debandThreshold}"
        }
        require(value.debandRange.isFinite() && value.debandRange > 0f) {
            "the deband range must be finite and above zero, was ${value.debandRange}"
        }
        require(value.debandGrain.isFinite() && value.debandGrain >= 0f) {
            "the deband grain must be finite and not negative, was ${value.debandGrain}"
        }
        core.post(CoreCommand.SetRenderQuality(value, CompletableDeferred()))
    }

    /**
     * Sets the framing controls: a forced display aspect, magnification, and pan, mpv's
     * `video-aspect-override`, `video-zoom` and `video-pan-x`/`-y`.
     *
     * Folded into the same one-pass geometry [setVideoScale] drives, so it costs nothing per
     * frame and composes with every scale mode: the aspect override reshapes the content, the
     * mode fits it, the zoom scales the fitted rectangle about its centre, and the pan moves it
     * by a fraction of its own drawn size. Pass [VideoTransform.Identity] to reset. Published as
     * [PlayerSnapshot.videoTransform].
     *
     * @throws IllegalArgumentException outside the documented ranges: aspect 0.1..10 (or null),
     *         zoom 0.25..4, pan -1..1 on each axis, all finite.
     */
    public fun setVideoTransform(value: VideoTransform) {
        val aspect = value.aspectOverride
        require(aspect == null || (aspect.isFinite() && aspect in 0.1f..10f)) {
            "aspectOverride must be within 0.1..10 or null, was $aspect"
        }
        require(value.zoom.isFinite() && value.zoom in 0.25f..4f) {
            "zoom must be within 0.25..4, was ${value.zoom}"
        }
        require(value.panX.isFinite() && value.panX in -1f..1f) { "panX must be within -1..1, was ${value.panX}" }
        require(value.panY.isFinite() && value.panY in -1f..1f) { "panY must be within -1..1, was ${value.panY}" }
        core.post(CoreCommand.SetVideoTransform(value, CompletableDeferred()))
    }

    /**
     * Shifts subtitle timing by [value]. Positive shows cues later. Applies to the cues already
     * on screen at the next pass, no reopen and no reselection.
     */
    public fun setSubtitleDelay(value: Duration) {
        core.post(CoreCommand.SetSubtitleDelay(value, CompletableDeferred()))
    }

    /**
     * Scales subtitle text over the authored size. The active cues re-rasterise at the new size
     * immediately; 1.0 is the authored size.
     *
     * @throws IllegalArgumentException unless finite and greater than zero.
     */
    public fun setSubtitleScale(value: Float) {
        require(value.isFinite() && value > 0f) { "subtitle scale must be finite and positive, was $value" }
        core.post(CoreCommand.SetSubtitleScale(value, CompletableDeferred()))
    }

    /**
     * Overrides the authored subtitle style with the viewer's own, or clears the override.
     *
     * Fields set on the override replace the authored value on every span; null fields keep the
     * author's. Applies to the text showing now, not just to the next cue. Bitmap subtitles are
     * untouched. [PlayerSnapshot.subtitleStyle] reports what is set.
     */
    public fun setSubtitleStyle(override: io.github.yuroyami.kiteplayer.subtitle.SubtitleStyleOverride?) {
        core.post(CoreCommand.SetSubtitleStyle(override, CompletableDeferred()))
    }

    /**
     * Moves the subtitles up the screen: [value] is where the implicit bottom stack anchors, as
     * a fraction of the viewport height, mpv's `sub-pos` over 100. 1.0, the default, is the
     * ordinary bottom edge; 0.9 lifts the stack a tenth of the screen, which is what a viewer
     * with a player bar over their subtitles wants. Explicitly positioned cues are the author's
     * word and do not move. The active cues re-rasterise immediately; published as
     * [PlayerSnapshot.subtitlePosition].
     *
     * @throws IllegalArgumentException unless finite and within 0.1 to 1.
     */
    public fun setSubtitlePosition(value: Float) {
        require(value.isFinite() && value in 0.1f..1f) {
            "subtitle position must be within 0.1..1, was $value"
        }
        core.post(CoreCommand.SetSubtitlePosition(value, CompletableDeferred()))
    }

    /**
     * Compensates for sound that reaches the ear late: with a positive [value] every video frame
     * is presented that much earlier, which is the whole of what a Bluetooth latency slider
     * needs. The audio samples are never touched, so the change is instant and free, and the
     * picture walks over smoothly within a frame or two rather than jumping.
     */
    public fun setAudioDelay(value: Duration) {
        core.post(CoreCommand.SetAudioDelay(value, CompletableDeferred()))
    }

    /**
     * Loads a subtitle FILE during playback, appends it to [PlayerSnapshot.tracks] as an
     * external track, selects it, and returns its id once it is really showing.
     *
     * SubRip and WebVTT, the same text path the open-time route reads.
     *
     * When a container subtitle stream is already timing cues, the swap needs the same reopen
     * [selectTrack] needs, and this call waits for it. It used to return the id as soon as the file
     * parsed, so a caller held an id for a track whose selection had not run and might still fail
     * the player. A selection that does not apply takes the appended track back
     * out of [PlayerSnapshot.tracks] rather than leaving a row nothing can show.
     *
     * @throws IllegalStateException when nothing is open, or when the selection was replaced or
     *         torn down before it applied.
     * @throws IllegalArgumentException when the file cannot be read, cannot be parsed, or
     *         parses to no cues; the message says which.
     * @throws PlaybackException when the reopen the selection needed failed.
     */
    public suspend fun addExternalSubtitle(source: SubtitleSource): TrackId =
        core.addExternalSubtitle(source)

    /**
     * Opens [items] as the queue, starting at [startIndex], and returns paused on its first frame
     * exactly like [open] (S4.e).
     *
     * At each item's end the next opens and playback continues; [LoopMode.All] wraps the end back
     * to the start. [PlayerEvent.Ended] still fires per item, and the snapshot carries the queue
     * and the moving [PlayerSnapshot.queueIndex]. A plain [open] replaces the queue with the one
     * item it names.
     *
     * @throws IllegalArgumentException for an empty list or a start index outside it.
     * @throws PlaybackException when the starting item cannot be opened, exactly like [open].
     */
    public suspend fun openQueue(items: List<MediaItem>, startIndex: Int = 0) {
        core.openQueue(items, startIndex)
    }

    /**
     * Opens the next queue item, keeping the play or pause intent (S4.e).
     *
     * @throws IllegalStateException with no queue, or at the last item unless [LoopMode.All]
     *         makes the ends meet.
     */
    public suspend fun next() {
        core.queueNext()
    }

    /**
     * Opens the previous queue item, keeping the play or pause intent (S4.e).
     *
     * @throws IllegalStateException with no queue, or at the first item unless [LoopMode.All]
     *         makes the ends meet.
     */
    public suspend fun previous() {
        core.queuePrevious()
    }

    /**
     * Inserts [items] at [index], or at the end when [index] is null.
     *
     * The item that is playing keeps playing and is not reopened. [PlayerSnapshot.queueIndex]
     * follows it, so it moves by [items] size when the insert lands in front of it.
     *
     * A plain [open] counts as a queue of one here: the first edit writes that queue down rather
     * than refusing, so adding a second item never has to reopen the first.
     *
     * @throws IllegalArgumentException for an empty list, or an index outside 0..queue size. That
     *         upper bound is inclusive, because one past the end is where an append goes.
     * @throws IllegalStateException when nothing is open at all.
     */
    public suspend fun addToQueue(items: List<MediaItem>, index: Int? = null) {
        core.addToQueue(items, index)
    }

    /** Inserts one item. See the list overload for everything else. */
    public suspend fun addToQueue(item: MediaItem, index: Int? = null) {
        core.addToQueue(listOf(item), index)
    }

    /**
     * Removes the item at [index].
     *
     * Removing anything other than the playing item touches nothing but the list and the cursor.
     * Removing the PLAYING item opens whatever followed it, keeping the play or pause intent;
     * [LoopMode.All] wraps back to the front when it was the last one. With nothing to play after
     * it the player stops, and the rest of the queue stays where it is with the cursor resting on
     * its last item.
     *
     * @throws IllegalArgumentException for an index outside the queue.
     * @throws IllegalStateException when nothing is open at all.
     * @throws PlaybackException when the item that took its place could not be opened.
     */
    public suspend fun removeFromQueue(index: Int) {
        core.removeFromQueue(index)
    }

    /**
     * Moves the item at [from] so that it sits at [to] in the new order.
     *
     * The playing item stays the playing item wherever it lands, and nothing is reopened, so this
     * is what a drag in a playlist should call.
     *
     * @throws IllegalArgumentException for an index outside the queue.
     * @throws IllegalStateException when nothing is open at all.
     */
    public suspend fun moveInQueue(from: Int, to: Int) {
        core.moveInQueue(from, to)
    }

    /**
     * Removes every item except the one playing, which is left at index 0 and is not reopened.
     *
     * @throws IllegalStateException when nothing is open at all.
     */
    public suspend fun clearQueue() {
        core.clearQueue()
    }

    /**
     * Steps a PAUSED player forward by exactly one frame and returns with it on screen (S4.e).
     *
     * One DECODED frame, and not one nominal frame period: the decoder has already filled the queue
     * ahead of the paused picture, so the schedule releases the next frame of the media whatever
     * its timestamp. That is what makes it exact on variable frame rate, on B-frames, on repeated
     * timestamps and on a container whose declared frame rate is simply wrong, all of which the old
     * seek-by-average-period step got wrong. It needs no seek, so it works on a
     * source that cannot seek, and it repeats no decoding, so holding the key down is cheap.
     *
     * @throws IllegalStateException when nothing is open, while playing (a playing player is
     *         already stepping sixty times a second), or when no frame arrived, which at the end of
     *         the media means there is no next frame.
     * @throws UnsupportedOperationException with no selected video track.
     */
    public suspend fun stepFrame() {
        core.stepFrame()
    }

    /**
     * Returns the newest presented frame as an owned, software-readable copy (S4.e): the
     * documented use of [io.github.yuroyami.kiteplayer.spi.SoftwareReadableFrame].
     *
     * Playing, the copy is taken from the very next frame the schedule presents; paused, the
     * current frame is re-presented through a precise seek and copied at the same boundary. The
     * copy is taken before the renderer ever owns the frame, so it is always coherent.
     *
     * @throws IllegalStateException when nothing is open.
     * @throws UnsupportedOperationException with no selected video track, when a paused source
     *         cannot seek, or when the presented frame is hardware-opaque with no readable
     *         planes (the direct MediaCodec tier; the software and download paths both capture).
     */
    public suspend fun captureFrame(): CapturedFrame = core.captureFrame()

    /**
     * The chapter whose span holds [position], or null before the first chapter or in media with
     * no chapter table (S4.e). Pure over the published snapshot; pair it with [position] for the
     * chapter now playing.
     */
    public fun chapterAt(position: Duration): Chapter? =
        state.value.chapters.chapterHolding(position.inWholeMicroseconds)

    /**
     * Seeks to the start of chapter [index] and returns when its first frame is on screen, with
     * [seek]'s exact contract (S4.e).
     *
     * @throws IllegalArgumentException when [index] is outside the chapter table, including for
     *         media with no chapters.
     */
    public suspend fun seekToChapter(index: Int) {
        val chapters = state.value.chapters
        require(index in chapters.indices) {
            "chapter $index does not exist: this media has ${chapters.size} chapter(s)"
        }
        seek(chapters[index].start)
    }

    /**
     * Seeks to the start of the chapter after the one holding the current position, with [seek]'s
     * contract. Does nothing at the last chapter, and in media with no chapter table.
     */
    public suspend fun nextChapter() {
        val here = position()
        val next = state.value.chapters.filter { it.start > here }.minByOrNull { it.start } ?: return
        seek(next.start)
    }

    /**
     * Seeks to the start of the current chapter, or of the previous one when less than three
     * seconds into the current one: the rule every music player has, so a second press means
     * "the one before". At the first chapter both readings restart it. In a gap between chapters
     * the last chapter to have started counts as the current one. Does nothing in media with no
     * chapter table.
     */
    public suspend fun previousChapter() {
        val here = position()
        val chapters = state.value.chapters
        val current = chapters.filter { it.start <= here }.maxByOrNull { it.start } ?: return
        val target = if (here - current.start < CHAPTER_RESTART_WINDOW) {
            chapters.filter { it.start < current.start }.maxByOrNull { it.start } ?: current
        } else {
            current
        }
        seek(target.start)
    }

    /**
     * Positions to announce with [PlayerEvent.MarkerReached] as playback crosses them. Sorted by
     * the engine and replaced wholesale, so an empty list clears them. They belong to the player
     * rather than to the item: a new item starts with every marker armed.
     */
    public fun setMarkers(markers: List<Marker>) {
        core.post(CoreCommand.SetMarkers(markers, CompletableDeferred()))
    }

    /**
     * Where playback is, as one value: the queue, the item, the position and every setting the
     * player holds. Store it however you like and hand it back to [restore]. A player that opened
     * a single item reports a queue of one; a player with nothing open reports an empty queue and
     * an index of -1, which [restore] refuses.
     */
    public fun memento(): PlayerMemento {
        val snapshot = state.value
        val tracks = snapshot.tracks
        val queue = snapshot.queue.ifEmpty { listOfNotNull(snapshot.media) }
        val index = if (snapshot.queue.isEmpty()) queue.lastIndex else snapshot.queueIndex
        return PlayerMemento(
            queue = queue,
            queueIndex = index,
            position = position(),
            speed = snapshot.speed,
            preservePitch = snapshot.preservePitch,
            volume = snapshot.volume,
            muted = snapshot.muted,
            loop = snapshot.loop,
            shuffle = snapshot.shuffle,
            subtitleDelay = snapshot.subtitleDelay,
            audioDelay = snapshot.audioDelay,
            audioLanguage = tracks.selectedAudio?.let { tracks.find(it) }?.language,
            subtitleLanguage = tracks.selectedSubtitle?.let { tracks.find(it) }?.language,
            subtitlesOff = tracks.selectedSubtitle == null && tracks.subtitles.isNotEmpty(),
        )
    }

    /**
     * Takes the player back to a [memento]: opens its queue at its index, applies every setting,
     * seeks to its position, then picks the audio and subtitle tracks by language where the new
     * container has them. Ends paused, like every open. A track the memento names and the
     * container lacks leaves the container's own choice in place.
     *
     * @throws IllegalArgumentException when the memento's queue is empty or its index is outside it.
     */
    public suspend fun restore(memento: PlayerMemento) {
        require(memento.queue.isNotEmpty()) { "a memento with no queue has nowhere to go back to" }
        require(memento.queueIndex in memento.queue.indices) {
            "queue index ${memento.queueIndex} is outside a queue of ${memento.queue.size}"
        }
        openQueue(memento.queue, memento.queueIndex)
        setSpeed(memento.speed)
        setPreservePitch(memento.preservePitch)
        setVolume(memento.volume)
        setMuted(memento.muted)
        setLoop(memento.loop)
        setShuffle(memento.shuffle)
        setSubtitleDelay(memento.subtitleDelay)
        setAudioDelay(memento.audioDelay)
        if (memento.position > Duration.ZERO) seek(memento.position)

        val tracks = state.value.tracks
        memento.audioLanguage?.let { language ->
            val wanted = tracks.audio.firstOrNull { it.language == language } ?: return@let
            if (tracks.selectedAudio != wanted.id) selectTrack(TrackKind.Audio, wanted.id)
        }
        if (memento.subtitlesOff) {
            if (tracks.selectedSubtitle != null) selectTrack(TrackKind.Subtitle, null)
        } else {
            memento.subtitleLanguage?.let { language ->
                val wanted = tracks.subtitles.firstOrNull { it.language == language } ?: return@let
                if (tracks.selectedSubtitle != wanted.id) selectTrack(TrackKind.Subtitle, wanted.id)
            }
        }
    }

    /**
     * Selects a track, or deselects the kind entirely with a null [track], and says what happened.
     *
     * Switching a CONTAINER track reopens the container and seeks back to where playback was,
     * because the demuxer permits its stream selection to be set once before the first read, so
     * that path needs a seekable source. Selecting an EXTERNAL subtitle track (S4.e, a negative
     * [TrackId] from [MediaItem.externalSubtitles]) while no container subtitle stream is
     * selected is an in-place cue-table swap: no reopen, no seek, any source. Seamless container
     * switching is on the roadmap in MASTER_PLAN.md.
     *
     * Selections of DIFFERENT kinds made close together are merged into one reopen, so setting the
     * audio track and then the subtitle track costs one rebuild and both are applied. Two requests
     * for the SAME kind cannot both be honoured, and the earlier one returns
     * [TrackChange.Superseded] rather than the success it used to report. Read the
     * result when it matters; ignore it when your application only ever selects from one place.
     *
     * @return [TrackChange.Applied] when this request is the live selection,
     *         [TrackChange.Superseded] when a later request for the same kind replaced it, and
     *         [TrackChange.Discarded] when a stop, a close or a new open ended it first.
     * @throws PlaybackException when the reopen itself failed: the media or the device broke.
     * @throws IllegalStateException when nothing is open.
     * @throws IllegalArgumentException when [track] is not a track of [kind] in the current media.
     * @throws UnsupportedOperationException for a container switch on a source that cannot seek,
     *         and for a container subtitle track when the backend decodes no subtitle format.
     */
    public suspend fun selectTrack(kind: TrackKind, track: TrackId?): TrackChange =
        core.selectTrack(kind, track)

    /**
     * Shows a second subtitle track at the top of the picture, or clears it with null.
     *
     * The secondary track's cues are forced to the top, so the two tracks never sit on each
     * other; the primary stays where its author put it. External tracks (negative ids) are
     * allowed on either slot. Selecting the track that already fills the other slot throws
     * [IllegalArgumentException]. [Tracks.selectedSecondarySubtitle] reports the selection.
     */
    public suspend fun selectSecondarySubtitle(track: TrackId?): TrackChange =
        core.selectSecondarySubtitle(track)

    /**
     * Attaches a renderer, or replaces the one attached. Legal at any time, including while playing.
     *
     * Video decoding never depends on a renderer existing. With none attached the schedule still paces
     * and releases frames, counting them as `PlaybackStats.headlessFrames`, so detaching costs the picture
     * and nothing else.
     *
     * Replacing a renderer the active video decoder is coupled to (its factory came from that
     * renderer) rebuilds the video path against the new renderer at the current position, keeping
     * the play state; on a source that cannot seek the rebuild is refused with a
     * `PlaybackWarning.CommandRefused` and playback continues without a usable picture.
     * Re-attaching the same renderer object never rebuilds. A replacement while not playing
     * repaints one frame so the new renderer is not blank.
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
        core.post(CoreCommand.DetachRenderer(null, CompletableDeferred()))
    }

    /**
     * Detaches [expected] only while it is still the attached renderer; a stale call is a no-op.
     * This is the safe form for presentation code whose teardown can race a newer attach.
     */
    public fun detachRenderer(expected: VideoRenderer) {
        core.post(CoreCommand.DetachRenderer(expected, CompletableDeferred()))
    }

    /**
     * Requests terminal close. Idempotent, and returns at once without proving teardown completed.
     *
     * The request immediately rejects later commands and starts the same close observed by
     * [closeAndAwait]. Every outstanding call is completed, the workers are stopped, the decoders and the
     * device are closed on their owning threads, and those threads are released, but none of that is a
     * postcondition of this non-suspending return. Use [closeAndAwait] when completion or failure matters.
     */
    override fun close() {
        core.close()
    }

    /**
     * Closes the player and returns only after the session actor and teardown have completed.
     *
     * Concurrent calls and [close] share one terminal result. Caller cancellation remains a
     * `CancellationException` and stops only that caller's wait; it never cancels the independently owned
     * teardown. The worker-ownership join is deliberately non-cancellable and can outlive the close
     * deadline when native code wedges, in which case a caller may ultimately have to terminate the
     * process.
     *
     * @throws PlaybackException with [PlaybackError.RuntimeCompromised] for every compromised outcome
     *         the core can report instead of a completed close.
     */
    public suspend fun closeAndAwait() {
        core.closeAndAwait()
    }

    /**
     * Everything a bug report needs, in one string (S4.d): the resolved configuration, the
     * backends by name, tracks and selections, the three published snapshots, the KD artifacts
     * attached to the session, and the bounded warning history. Safe from any thread at any
     * moment, including after a failure, which is when it is usually wanted.
     */
    public fun diagnosticsDump(): String = core.diagnosticsDump()

    /**
     * The last warnings this player emitted, oldest first, capped (S4.d).
     *
     * [events] replays nothing to a late collector, and a bug report is exactly a late
     * collector: this history is how a warning that happened before anyone listened still
     * reaches the person debugging. The cap keeps a long session's memory bounded; the newest
     * warnings always survive.
     */
    public fun warningHistory(): List<TimedWarning> = core.warningHistory()

    /**
     * The dump plus a platform block, with every path trimmed to its basename (S4.e): what a
     * user pastes into a bug report without leaking their filesystem.
     */
    public fun supportBundle(): String = buildString {
        appendLine("KitePlayer support bundle")
        appendLine("platform    ${io.github.yuroyami.kiteplayer.internal.playerPlatformName}")
        appendLine("kotlin      ${KotlinVersion.CURRENT}")
        append(core.diagnosticsDump(redactPaths = true))
    }



    private fun validPosition(to: Duration, name: String): Duration {
        require(to.isFinite() && to >= Duration.ZERO) {
            "$name needs a finite position at or after zero, was $to"
        }
        return to
    }

    public companion object {

        /** The slowest [setSpeed] accepts. One value with [SPEED_MAX], stated once for UIs to read. */
        public const val SPEED_MIN: Double = 0.25

        /** The fastest [setSpeed] accepts. */
        public const val SPEED_MAX: Double = 4.0

        /** How long [setSleepTimer] fades for when the caller does not say. */
        public val DEFAULT_SLEEP_FADE: Duration = kotlin.time.Duration.parse("10s")
        /**
         * Builds a player from [config].
         *
         * The backends in [PlayerConfig.backends] are resolved here, and nothing is discovered: Kotlin's
         * native targets have no classpath service lookup, so a missing backend is a typed configuration
         * error rather than a reflective search that would fail differently on every platform. On macOS the
         * explicit pair is `KiteFFmpegMediaBackend()` from `kiteplayer-ffmpeg` and `AppleOutputBackend` from
         * `kiteplayer-output`; the engine never names either, which is what keeps it free of any platform.
         *
         * The player owns six threads from here until terminal close completes, one for the session actor
         * and one for each worker, because that is the confinement every contract inside the engine is
         * written against. [close] requests that work; [closeAndAwait] proves its completion.
         *
         * @throws PlaybackException with [PlaybackError.ConfigurationInvalid] when no media backend or no
         *         output backend was supplied.
         */
        public fun create(config: PlayerConfig = PlayerConfig()): KitePlayer {
            val backend = config.backends.backend ?: throw PlaybackException(
                PlaybackError.ConfigurationInvalid(
                    "no media backend was supplied in PlayerConfig.backends.backend, and there is nothing " +
                        "to discover one with. On macOS pass KiteFFmpegMediaBackend() from kiteplayer-ffmpeg",
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

/** How far into a chapter "previous" still means the one before rather than a restart. */
private val CHAPTER_RESTART_WINDOW: Duration = 3.seconds
