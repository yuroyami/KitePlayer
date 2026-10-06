package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.future.future
import kotlinx.coroutines.launch
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import kotlin.time.Duration.Companion.milliseconds

/**
 * A [KitePlayer] for a Java app, on the JVM and Android (#394).
 *
 * The player speaks in coroutines and flows, and a Java app can call neither. This adds what Java
 * lacks, beside the Kotlin API rather than instead of it:
 *
 * - [addListener] and [removeListener], for the state, the progress, the events and the warnings,
 *   called on an [Executor] the app names. An Android app passes its main executor.
 * - A [CompletableFuture] version of every call that suspends, named with `Async`. Cancelling the
 *   future cancels the call, as cancelling the coroutine does in Kotlin.
 * - Millisecond versions of the calls that take or give a [kotlin.time.Duration], which Java cannot
 *   call by name.
 *
 * Everything else is on [player], whose other calls Java can make directly: `play()`, `pause()`,
 * `setVolume(float)` and the rest. The data the player publishes has Java names for the values a
 * player screen reads: [Progress.positionMillis], [PlayerSnapshot.durationMillis],
 * [PlayerEvent.SeekCompleted.landedAtMillis] and [Tracks.selectedTrack]. Tracks are named by their
 * [TrackInfo] here, because Java cannot read or make a [TrackId].
 *
 * ```java
 * KitePlayerJava player = KitePlayerJava.create();
 * player.addListener(listener, ContextCompat.getMainExecutor(context));
 * player.openAsync(new MediaItemBuilder("https://example.com/movie.mp4").build())
 *       .thenRun(() -> player.getPlayer().play());
 * ```
 *
 * This holds no state of the player's own. Every listener call comes from collecting the player's
 * flows, and every future from launching its suspending call, so a future completes exactly once,
 * with the answer the call gives.
 */
public class KitePlayerJava(
    /** The player itself, for every call that Java can make on it directly. */
    public val player: KitePlayer,
) : AutoCloseable {

    private val root = SupervisorJob()
    private val calls = CoroutineScope(root + Dispatchers.Default)
    private val registrations = ConcurrentHashMap<KitePlayerListener, Job>()

    // ---- Listeners ------------------------------------------------------------------------------

    /**
     * Calls [listener] on [executor] with the state, the progress, the events and the warnings,
     * until [removeListener]. Adding a listener that is already added does nothing.
     *
     * Each event that happens after this returns reaches the listener, and none from before it,
     * because the player replays no event. Pass a serial executor, such as Android's main
     * executor, for the calls of one method to arrive in order.
     *
     * The events are taken from the player at once and queued for [executor], so a listener whose
     * executor is busy delays only itself, never the player or another listener. An exception a
     * method throws is not caught: it reaches the executor's thread, and that listener gets
     * nothing more.
     */
    public fun addListener(listener: KitePlayerListener, executor: Executor) {
        val registration = Job(root)
        if (registrations.putIfAbsent(listener, registration) != null) {
            registration.cancel()
            return
        }
        registration.invokeOnCompletion { registrations.remove(listener, registration) }
        // Subscribed before this returns, so no event after it can be missed. The lossless feed, and
        // not `events`, whose shared buffer drops an event for every collector once any one of them
        // falls behind, so a slow Kotlin collector elsewhere in the app cannot cost this listener
        // one (#414). This side only ever queues.
        val events = Channel<PlayerEvent>(Channel.UNLIMITED)
        CoroutineScope(registration + Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
            player.losslessEvents.collect { events.trySend(it) }
        }
        val deliver = CoroutineScope(registration + executor.asCoroutineDispatcher())
        deliver.launch { player.state.collect { listener.onState(it) } }
        deliver.launch { player.progress.collect { listener.onProgress(it) } }
        deliver.launch {
            for (event in events) {
                if (event is PlayerEvent.Warning) listener.onWarning(event.warning) else listener.onEvent(event)
            }
        }
    }

    /**
     * Stops calling [listener]. Calls already handed to its executor are dropped; one that has
     * already begun on another thread may still finish.
     */
    public fun removeListener(listener: KitePlayerListener) {
        registrations.remove(listener)?.cancel()
    }

    // ---- Futures --------------------------------------------------------------------------------

    /** `KitePlayer.open`. Completes once [media] is open and paused on its first frame. */
    public fun openAsync(media: MediaItem): CompletableFuture<Void?> = launchCall { player.open(media) }

    /** `KitePlayer.seek` to [positionMillis] with [mode]. Completes once the player has landed there. */
    @JvmOverloads
    public fun seekAsync(positionMillis: Long, mode: SeekMode = SeekMode.Precise): CompletableFuture<Void?> =
        launchCall { player.seek(positionMillis.milliseconds, mode) }

    /** `KitePlayer.stop`. */
    public fun stopAsync(): CompletableFuture<Void?> = launchCall { player.stop() }

    /** `KitePlayer.closeAndAwait`. Completes once the player has closed and released everything. */
    public fun closeAsync(): CompletableFuture<Void?> = launchCall { player.closeAndAwait() }

    /** `KitePlayer.awaitClose`. Completes once the player is asked to close, by anyone. */
    public fun awaitCloseAsync(): CompletableFuture<Void?> = launchCall { player.awaitClose() }

    /** `KitePlayer.selectTrack`: [track] of [kind], or none of that kind for null. */
    public fun selectTrackAsync(kind: TrackKind, track: TrackInfo?): CompletableFuture<TrackChange> =
        calls.future { player.selectTrack(kind, track?.id) }

    /** `KitePlayer.selectSecondarySubtitle`: [track], or none for null. */
    public fun selectSecondarySubtitleAsync(track: TrackInfo?): CompletableFuture<TrackChange> =
        calls.future { player.selectSecondarySubtitle(track?.id) }

    /** `KitePlayer.selectVariant`: the variant with [index], or the automatic choice for null. */
    public fun selectVariantAsync(index: Int?): CompletableFuture<Void?> = launchCall { player.selectVariant(index) }

    /** `KitePlayer.selectProgram`: the channel numbered [number], or the automatic choice for null. */
    public fun selectProgramAsync(number: Int?): CompletableFuture<Void?> = launchCall { player.selectProgram(number) }

    /**
     * `KitePlayer.addExternalSubtitle`. Completes once the file's track shows, selected; it is then
     * the subtitle track of `Tracks.selectedTrack`.
     */
    public fun addExternalSubtitleAsync(source: SubtitleSource): CompletableFuture<Void?> =
        launchCall { player.addExternalSubtitle(source) }

    /**
     * `KitePlayer.reloadExternalSubtitle`: reads the file of [track] again, in [encoding], or by the
     * guess for null. Completes once the track holds the new reading, in the same place and with the
     * same selection.
     */
    @JvmOverloads
    public fun reloadExternalSubtitleAsync(track: TrackInfo, encoding: String? = null): CompletableFuture<Void?> =
        launchCall { player.reloadExternalSubtitle(track.id, encoding) }

    /** `KitePlayer.openQueue`. */
    @JvmOverloads
    public fun openQueueAsync(items: List<MediaItem>, startIndex: Int = 0): CompletableFuture<Void?> {
        // Copied on the caller's thread before the call runs on another one, so a caller that
        // reuses its list as soon as this returns changes nothing in the queue (#409).
        val owned = items.toList()
        return launchCall { player.openQueue(owned, startIndex) }
    }

    /** `KitePlayer.next`. */
    public fun nextAsync(): CompletableFuture<Void?> = launchCall { player.next() }

    /** `KitePlayer.previous`. */
    public fun previousAsync(): CompletableFuture<Void?> = launchCall { player.previous() }

    /** `KitePlayer.addToQueue` with [items], at [index] or at the end for null. */
    @JvmOverloads
    public fun addToQueueAsync(items: List<MediaItem>, index: Int? = null): CompletableFuture<Void?> {
        // Copied here for the reason openQueueAsync gives (#409).
        val owned = items.toList()
        return launchCall { player.addToQueue(owned, index) }
    }

    /** `KitePlayer.addToQueue` with [item], at [index] or at the end for null. */
    @JvmOverloads
    public fun addToQueueAsync(item: MediaItem, index: Int? = null): CompletableFuture<Void?> =
        launchCall { player.addToQueue(item, index) }

    /** `KitePlayer.removeFromQueue`. */
    public fun removeFromQueueAsync(index: Int): CompletableFuture<Void?> = launchCall { player.removeFromQueue(index) }

    /** `KitePlayer.moveInQueue`. */
    public fun moveInQueueAsync(from: Int, to: Int): CompletableFuture<Void?> = launchCall { player.moveInQueue(from, to) }

    /** `KitePlayer.clearQueue`. */
    public fun clearQueueAsync(): CompletableFuture<Void?> = launchCall { player.clearQueue() }

    /** `KitePlayer.stepFrame`. */
    @JvmOverloads
    public fun stepFrameAsync(direction: StepDirection = StepDirection.Forward): CompletableFuture<Void?> =
        launchCall { player.stepFrame(direction) }

    /** `KitePlayer.captureFrame`. */
    @JvmOverloads
    public fun captureFrameAsync(withSubtitles: Boolean = false): CompletableFuture<CapturedFrame> =
        calls.future { player.captureFrame(withSubtitles) }

    /** `KitePlayer.startRecording`. */
    public fun startRecordingAsync(path: String): CompletableFuture<Void?> = launchCall { player.startRecording(path) }

    /** `KitePlayer.stopRecording`. */
    public fun stopRecordingAsync(): CompletableFuture<Void?> = launchCall { player.stopRecording() }

    /** `KitePlayer.seekToChapter`. */
    public fun seekToChapterAsync(index: Int): CompletableFuture<Void?> = launchCall { player.seekToChapter(index) }

    /** `KitePlayer.nextChapter`. */
    public fun nextChapterAsync(): CompletableFuture<Void?> = launchCall { player.nextChapter() }

    /** `KitePlayer.previousChapter`. */
    public fun previousChapterAsync(): CompletableFuture<Void?> = launchCall { player.previousChapter() }

    /** `KitePlayer.restore`, with a memento from `KitePlayer.memento()`. */
    public fun restoreAsync(memento: PlayerMemento): CompletableFuture<Void?> = launchCall { player.restore(memento) }

    /** `KitePlayer.inspect`. */
    public fun inspectAsync(media: MediaItem): CompletableFuture<MediaInspection> =
        calls.future { player.inspect(media) }

    /** `KitePlayer.attachRendererAndAwait`. */
    public fun attachRendererAsync(renderer: VideoRenderer): CompletableFuture<Void?> =
        launchCall { player.attachRendererAndAwait(renderer) }

    /**
     * `KitePlayer.scanAudio`: decodes the sound of [track] of [media], or of the track an open would
     * pick for null, from [fromMillis] to [untilMillis], and hands it to [sink]. Null bounds scan
     * from the start or to the end.
     */
    @JvmOverloads
    public fun scanAudioAsync(
        media: MediaItem,
        track: TrackInfo?,
        sink: AudioBlockSink,
        fromMillis: Long? = null,
        untilMillis: Long? = null,
    ): CompletableFuture<AudioScanResult> = calls.future {
        val range = if (fromMillis == null && untilMillis == null) {
            null
        } else {
            AudioScanRange(fromMillis?.let { Pts(it * 1_000) }, untilMillis?.let { Pts(it * 1_000) })
        }
        player.scanAudio(media, track?.id, range) { pts, interleaved, frames, format ->
            sink.onAudio(pts.micros, interleaved, frames, format)
        }
    }

    /** What [scanAudioAsync] hands the decoded sound to, on a background thread. */
    public fun interface AudioBlockSink {
        /**
         * [interleaved] holds [frames] sample frames starting at [ptsMicros] and is borrowed: copy
         * whatever outlives the call. The scan waits while this runs.
         */
        public fun onAudio(ptsMicros: Long, interleaved: FloatArray, frames: Int, format: AudioFormat)
    }

    // ---- Milliseconds ---------------------------------------------------------------------------

    /** `KitePlayer.position`, in milliseconds. */
    public fun positionMillis(): Long = player.position().inWholeMilliseconds

    /** `KitePlayer.requestSeek` to [positionMillis]. Returns at once. */
    @JvmOverloads
    public fun requestSeek(positionMillis: Long, mode: SeekMode = SeekMode.KeyframeThenRefine) {
        player.requestSeek(positionMillis.milliseconds, mode)
    }

    /** `KitePlayer.setAudioDelay`, in milliseconds. */
    public fun setAudioDelay(millis: Long) {
        player.setAudioDelay(millis.milliseconds)
    }

    /** `KitePlayer.setSubtitleDelay`, in milliseconds. */
    public fun setSubtitleDelay(millis: Long) {
        player.setSubtitleDelay(millis.milliseconds)
    }

    /**
     * `KitePlayer.setSleepTimer`, fading for [fadeMillis]. Make the timer with [sleepAfter],
     * [sleepAt] or `SleepTimer.EndOfItem.INSTANCE`, or pass null to clear it.
     */
    @JvmOverloads
    public fun setSleepTimer(timer: SleepTimer?, fadeMillis: Long = KitePlayer.DEFAULT_SLEEP_FADE.inWholeMilliseconds) {
        player.setSleepTimer(timer, fadeMillis.milliseconds)
    }

    /** `KitePlayer.setAbLoop` between [aMillis] and [bMillis]. Null for A clears the loop. */
    @JvmOverloads
    public fun setAbLoop(aMillis: Long?, bMillis: Long? = null) {
        player.setAbLoop(aMillis?.milliseconds, bMillis?.milliseconds)
    }

    /** `KitePlayer.chapterAt` the position [positionMillis]. */
    public fun chapterAt(positionMillis: Long): Chapter? = player.chapterAt(positionMillis.milliseconds)

    /**
     * `KitePlayer.close`: asks the player to close and returns at once. [closeAsync] completes when
     * it has. Listeners stay until [removeListener], so they hear the closing state.
     */
    override fun close() {
        player.close()
    }

    /** Launches [call] as a future that completes with null, as a Java `CompletableFuture<Void>` does. */
    private fun launchCall(call: suspend () -> Unit): CompletableFuture<Void?> = calls.future {
        call()
        null
    }

    public companion object {
        /** A player on this platform's default stack, as `KitePlayer()` builds it. */
        @JvmStatic
        @JvmOverloads
        public fun create(config: PlayerConfig = PlayerConfig()): KitePlayerJava = KitePlayerJava(KitePlayer(config))

        /** Whether [create] can build a player here, as `KitePlayer.isAvailable` says. */
        @JvmStatic
        public fun isAvailable(): Boolean = KitePlayer.isAvailable

        /** Why [create] can or cannot build a player here, as `KitePlayer.availability` says. */
        @JvmStatic
        public fun availability(): KitePlayerAvailability = KitePlayer.availability

        /** A sleep timer that stops playback [millis] from when it is set. */
        @JvmStatic
        public fun sleepAfter(millis: Long): SleepTimer = SleepTimer.After(millis.milliseconds)

        /** A sleep timer that stops playback when it reaches [positionMillis]. */
        @JvmStatic
        public fun sleepAt(positionMillis: Long): SleepTimer = SleepTimer.At(positionMillis.milliseconds)
    }
}
