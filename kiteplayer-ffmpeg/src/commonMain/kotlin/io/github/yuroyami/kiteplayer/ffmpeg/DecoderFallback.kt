package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.HwdecStatus
import io.github.yuroyami.kiteplayer.PlaybackError
import io.github.yuroyami.kiteplayer.PlaybackException
import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.github.yuroyami.kiteplayer.spi.PlayerPacket
import io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo
import io.github.yuroyami.kiteplayer.spi.VideoDecoder
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.atomicfu.atomic
import kotlin.coroutines.cancellation.CancellationException

/** Maximum compressed replay window held by one hardware decoder. */
internal const val DECODER_REPLAY_LIMIT_BYTES: Int = 16 * 1024 * 1024

/**
 * Opens the selected decoder and installs the replay driver only when policy permits fallback.
 *
 * [open] is deliberately the whole platform seam. Tests can make either decoder refuse, or return
 * a decoder whose send/receive schedule fails, without loading a codec process.
 */
internal suspend fun openDecoderWithFallback(
    stream: PlayerStreamInfo,
    selection: DecoderSelection,
    open: suspend (HardwareRoute?) -> VideoDecoder,
    copyPacket: (PlayerPacket) -> PlayerPacket,
    isKeyframe: (VideoFrame) -> Boolean,
    prepareReplay: () -> Unit = {},
    warn: (PlaybackWarning) -> Unit,
): VideoDecoder? {
    val route = selection.hardware
    if (route == null) {
        return if (selection.requiresHardware) null else open(null)
    }

    val hardware = try {
        open(route)
    } catch (failure: Throwable) {
        failure.rethrowCancellation()
        if (!selection.mayFallback) return null
        warn(
            PlaybackWarning.HardwareDecodeUnavailable(
                codec = stream.codec,
                reason = "hardware decoder refused to open: ${failure.failureDetail()}",
            ),
        )
        return open(null)
    }

    if (!selection.mayFallback) return hardware
    return ReplayFallbackDecoder(
        stream = stream,
        initial = hardware,
        openSoftware = { open(null) },
        copyPacket = copyPacket,
        isKeyframe = isKeyframe,
        prepareReplay = prepareReplay,
        warn = warn,
    )
}

/** One O(1) codec packet clone and the facts needed after the caller closes the original. */
private data class RetainedPacket(
    val packet: PlayerPacket,
    val bytes: Int,
    val isKeyframe: Boolean,
    /** The original packet's presentation timestamp, for flag-free boundary confirmation. */
    val pts: io.github.yuroyami.kiteplayer.Pts?,
)

private class ReplayFallbackDecoder(
    private val stream: PlayerStreamInfo,
    initial: VideoDecoder,
    private val openSoftware: suspend () -> VideoDecoder,
    private val copyPacket: (PlayerPacket) -> PlayerPacket,
    private val isKeyframe: (VideoFrame) -> Boolean,
    private val prepareReplay: () -> Unit,
    private val warn: (PlaybackWarning) -> Unit,
) : VideoDecoder {

    private var active: VideoDecoder? = initial
    private var usingHardware: Boolean = true

    /** Read by the player actor while the decoder worker may demote. */
    private val currentHardware = atomic(initial.hardware)

    override val hardware: HwdecStatus get() = currentHardware.value

    private val retained: MutableList<RetainedPacket> = mutableListOf()
    private var retainedBytes: Int = 0

    /** Index of the oldest accepted keyframe whose decoded keyframe has not appeared yet. */
    private var pendingBoundary: Int? = null
    private var hasConfirmedBoundary: Boolean = false

    /** Outputs handed to the caller since the confirmed keyframe, including that keyframe. */
    private var deliveredSinceBoundary: Long = 0

    /**
     * Every hardware output ever handed to the caller this epoch, confirmed or not. Zero is the
     * S2.b head-replay licence: an hwaccel can accept its attach and refuse the very first send
     * (VideoToolbox on a stream it dislikes), and with nothing delivered the retained window is
     * the COMPLETE history since open or flush, so software replay from the head loses nothing.
     */
    private var deliveredThisEpoch: Long = 0

    /** Outputs still to discard when replay needs later packets before reproducing the old output. */
    private var replaySuppressRemaining: Long = 0
    private val replayOutputs: ArrayDeque<VideoFrame> = ArrayDeque()
    private val hardwareOutputs: ArrayDeque<VideoFrame> = ArrayDeque()

    private var hardwareDrainAccepted: Boolean = false
    private var currentGeneration: Generation = Generation.Initial
    private var warned: Boolean = false
    private var closed: Boolean = false

    override suspend fun send(packet: PlayerPacket?): Boolean {
        checkOpen()

        // PlaybackCore sends the drain marker once and deliberately ignores the Boolean. Resolve
        // decoder backpressure here, retaining each output for the following receive calls.
        if (packet == null) return sendDrain()

        // A false send means the caller owns and retries the same packet. Preserve that contract when
        // replay already found output: hand those frames out before accepting anything new.
        if (!usingHardware && replayOutputs.isNotEmpty()) return false

        if (usingHardware && shouldRetain(packet)) {
            val bytes = packet.sizeBytes.coerceAtLeast(0)
            if (retainedBytes.toLong() + bytes.toLong() > DECODER_REPLAY_LIMIT_BYTES.toLong()) {
                demote(
                    reason = "compressed replay window would exceed $DECODER_REPLAY_LIMIT_BYTES bytes",
                    failure = null,
                    replayDrain = hardwareDrainAccepted,
                )
                if (replayOutputs.isNotEmpty()) return false
                return sendSoftware(packet)
            }
        }

        if (!usingHardware) return sendSoftware(packet)

        val clone = if (shouldRetain(packet)) copyPacket(packet) else null
        val accepted = try {
            requireActive().send(packet)
        } catch (failure: Throwable) {
            clone.closeQuietly()
            failure.rethrowCancellation()
            demote(
                reason = "hardware send failed: ${failure.failureDetail()}",
                failure = failure,
                replayDrain = hardwareDrainAccepted,
            )
            if (replayOutputs.isNotEmpty()) return false
            return sendSoftware(packet)
        }

        if (!accepted) {
            clone.closeQuietly()
            return false
        }

        if (clone != null) {
            retainAccepted(packet, clone)
        }
        return true
    }

    override suspend fun receive(): VideoFrame? {
        checkOpen()
        hardwareOutputs.removeFirstOrNull()?.let { frame ->
            accountHardwareOutput(frame)
            return frame
        }
        if (!usingHardware) return receiveSoftware()

        val frame = try {
            requireActive().receive()
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            demote(
                reason = "hardware receive failed: ${failure.failureDetail()}",
                failure = failure,
                replayDrain = hardwareDrainAccepted,
            )
            return receiveSoftware()
        } ?: return null

        accountHardwareOutput(frame)
        return frame
    }

    override val isDrained: Boolean
        get() {
            val drained = !closed && replayOutputs.isEmpty() && hardwareOutputs.isEmpty() &&
                (active?.isDrained == true)
            if (drained && replaySuppressRemaining != 0L) failTerminal(replayShortageFailure())
            return drained
        }

    override suspend fun flush(newGeneration: Generation) {
        checkOpen()
        var closeFailure: Throwable? = closeRetainedAndReplayOutputs()
        resetReplayState()
        try {
            requireActive().flush(newGeneration)
            currentGeneration = newGeneration
        } catch (failure: Throwable) {
            closeFailure = closeFailure ?: failure
        }
        closeFailure?.let { throw it }
    }

    override fun close() {
        if (closed) return
        closed = true
        val activeToClose = active
        active = null
        var first: Throwable? = closeRetainedAndReplayOutputs()
        try {
            activeToClose?.close()
        } catch (failure: Throwable) {
            first = first ?: failure
        }
        resetReplayState()
        first?.let { throw it }
    }

    private fun shouldRetain(packet: PlayerPacket): Boolean = retained.isNotEmpty() || packet.isKeyframe

    private fun retainAccepted(original: PlayerPacket, clone: PlayerPacket) {
        val entry = RetainedPacket(
            packet = clone,
            bytes = original.sizeBytes.coerceAtLeast(0),
            isKeyframe = original.isKeyframe,
            pts = original.pts,
        )
        retained += entry
        retainedBytes += entry.bytes
        if (entry.isKeyframe && pendingBoundary == null) pendingBoundary = retained.lastIndex
    }

    private fun accountHardwareOutput(frame: VideoFrame) {
        deliveredThisEpoch += 1
        val boundary = pendingBoundary
        if (boundary != null && confirmsBoundary(frame, retained.getOrNull(boundary))) {
            // The decoded keyframe, not merely its accepted packet, makes the candidate safe. Delayed
            // B-frames observed before this point belonged to the old ordinal window.
            closeRetainedPrefix(boundary)
            hasConfirmedBoundary = true
            deliveredSinceBoundary = 1
            pendingBoundary = retained
                .indexOfFirstFrom(startIndex = 1) { it.isKeyframe }
                .takeIf { it >= 0 }
        } else if (hasConfirmedBoundary) {
            deliveredSinceBoundary += 1
        }
    }

    /**
     * True when [frame] proves the boundary keyframe was decoded.
     *
     * The flag is the first-class proof. The timestamp is the fallback for decoders that never
     * set it: FFmpeg's mediacodec wrapper marks no output frame as a keyframe, which without
     * this made the window grow until the 16 MiB cap failed EVERY Android playback longer than
     * that with no seek in between (found by the 17.4.6 A3 measured run; the earlier smokes
     * seeked mid-run, which resets the window, and dodged it). A decoded output at or past the
     * boundary packet's presentation time cannot exist unless the boundary keyframe itself was
     * decoded: frames later in presentation reference it, and frames decoded before it sit
     * earlier in presentation, including open-GOP leading frames. A boundary packet without a
     * timestamp keeps flag-only confirmation, which is exactly the old behaviour.
     */
    private fun confirmsBoundary(frame: VideoFrame, boundary: RetainedPacket?): Boolean {
        if (isKeyframe(frame)) return true
        val boundaryPts = boundary?.pts ?: return false
        return frame.pts >= boundaryPts
    }

    private suspend fun demote(reason: String, failure: Throwable?, replayDrain: Boolean) {
        // Terminal only when outputs already crossed to the caller WITHOUT a confirmed keyframe
        // boundary: replaying then risks duplicating them. With nothing delivered this epoch the
        // window is the complete history and the head replay below is exact (the S2.b licence).
        if (!hasConfirmedBoundary && deliveredThisEpoch > 0) {
            failTerminal(
                PlaybackException(
                    PlaybackError.DecoderFailed(
                        codec = stream.codec,
                        detail = "$reason; outputs were delivered but no replay keyframe was confirmed",
                        cause = failure,
                    ),
                ),
            )
        }

        // Close the failed hardware owner before opening its replacement. Retained packet owners stay
        // alive until replay finishes (or reopening fails).
        closeActiveQuietly()
        usingHardware = false
        currentHardware.value = HwdecStatus.Software

        // Frames pulled only to satisfy hardware drain backpressure have never crossed the decoder
        // boundary. Drop those owners before replay recreates their ordinals; exposing both copies
        // would duplicate the drain tail.
        closeHardwareOutputsQuietly()
        try {
            warnOnce(reason)
        } catch (warningFailure: Throwable) {
            failTerminal(warningFailure)
        }

        val software = try {
            val opened = openSoftware()
            try {
                opened.flush(currentGeneration)
                prepareReplay()
            } catch (flushFailure: Throwable) {
                opened.closeQuietly()
                throw flushFailure
            }
            opened
        } catch (openFailure: Throwable) {
            if (openFailure is CancellationException) failTerminal(openFailure)
            failTerminal(
                PlaybackException(
                    PlaybackError.DecoderFailed(
                        codec = stream.codec,
                        detail = "$reason; software fallback refused to open: ${openFailure.failureDetail()}",
                        cause = openFailure,
                    ),
                ),
            )
        }
        active = software
        replaySuppressRemaining = deliveredSinceBoundary

        try {
            replayInto(software, replayDrain)
        } catch (replayFailure: Throwable) {
            if (replayFailure is CancellationException) failTerminal(replayFailure)
            failTerminal(
                PlaybackException(
                    PlaybackError.DecoderFailed(
                        codec = stream.codec,
                        detail = "$reason; software replay failed: ${replayFailure.failureDetail()}",
                        cause = replayFailure,
                    ),
                ),
            )
        } finally {
            closeRetainedQuietly()
        }

        if (replayDrain && replaySuppressRemaining != 0L) {
            failTerminal(
                PlaybackException(
                    PlaybackError.DecoderFailed(
                        codec = stream.codec,
                        detail = "$reason; replay drained before reproducing " +
                            "$replaySuppressRemaining previously delivered output(s)",
                        cause = failure,
                    ),
                ),
            )
        }
    }

    private suspend fun replayInto(software: VideoDecoder, replayDrain: Boolean) {
        retained.forEach { entry ->
            while (!software.send(entry.packet)) {
                val frame = software.receive()
                    ?: error("software replay backpressure produced no output")
                acceptReplayOutput(frame)
            }
            drainAvailableReplayOutputs(software)
        }

        if (replayDrain) {
            while (!software.send(null)) {
                val frame = software.receive()
                    ?: error("software replay drain backpressure produced no output")
                acceptReplayOutput(frame)
            }
            drainAvailableReplayOutputs(software)
        }
    }

    private suspend fun drainAvailableReplayOutputs(software: VideoDecoder) {
        while (true) {
            val frame = software.receive() ?: return
            acceptReplayOutput(frame)
        }
    }

    private fun acceptReplayOutput(frame: VideoFrame) {
        if (replaySuppressRemaining > 0) {
            replaySuppressRemaining -= 1
            frame.close()
        } else {
            replayOutputs.addLast(frame)
        }
    }

    private suspend fun sendSoftware(packet: PlayerPacket): Boolean {
        val decoder = requireActive()
        if (replaySuppressRemaining == 0L) return decoder.send(packet)

        // A decoder may apply backpressure before replay has reproduced every old output. Those
        // outputs are internal suppression work: consume and close them here, retrying the same
        // caller-owned packet. Expose false only once an output exists that the caller may receive.
        while (true) {
            if (decoder.send(packet)) return true
            val frame = decoder.receive()
                ?: error("software backpressure while finishing replay produced no output")
            acceptReplayOutput(frame)
            if (replayOutputs.isNotEmpty()) return false
        }
    }

    private suspend fun sendDrain(): Boolean {
        if (!usingHardware) {
            drainSoftwareThroughBackpressure()
            return true
        }
        while (true) {
            val accepted = try {
                requireActive().send(null)
            } catch (failure: Throwable) {
                failure.rethrowCancellation()
                demote(
                    reason = "hardware drain send failed: ${failure.failureDetail()}",
                    failure = failure,
                    replayDrain = false,
                )
                drainSoftwareThroughBackpressure()
                return true
            }
            if (accepted) {
                hardwareDrainAccepted = true
                return true
            }
            val frame = try {
                requireActive().receive()
            } catch (failure: Throwable) {
                failure.rethrowCancellation()
                demote(
                    reason = "hardware receive failed during drain: ${failure.failureDetail()}",
                    failure = failure,
                    replayDrain = false,
                )
                drainSoftwareThroughBackpressure()
                return true
            } ?: error("hardware drain backpressure produced no output")
            hardwareOutputs.addLast(frame)
        }
    }

    private suspend fun drainSoftwareThroughBackpressure() {
        val decoder = requireActive()
        while (!decoder.send(null)) {
            val frame = decoder.receive()
                ?: error("software drain backpressure produced no output")
            acceptReplayOutput(frame)
        }
    }

    private suspend fun receiveSoftware(): VideoFrame? {
        replayOutputs.removeFirstOrNull()?.let { return it }
        while (true) {
            val decoder = requireActive()
            val frame = decoder.receive()
            if (frame == null) {
                if (decoder.isDrained && replaySuppressRemaining != 0L) {
                    failTerminal(replayShortageFailure())
                }
                return null
            }
            if (replaySuppressRemaining == 0L) return frame
            replaySuppressRemaining -= 1
            frame.close()
        }
    }

    private fun replayShortageFailure(): PlaybackException = PlaybackException(
        PlaybackError.DecoderFailed(
            codec = stream.codec,
            detail = "software replay drained before reproducing " +
                "$replaySuppressRemaining previously delivered output(s)",
        ),
    )

    private fun closeRetainedPrefix(endExclusive: Int) {
        repeat(endExclusive) {
            val removed = retained.removeAt(0)
            retainedBytes -= removed.bytes
            removed.packet.close()
        }
    }

    private fun warnOnce(reason: String) {
        if (warned) return
        warned = true
        warn(PlaybackWarning.HardwareDecodeUnavailable(stream.codec, reason))
    }

    private fun failTerminal(failure: Throwable): Nothing {
        closeActiveQuietly()
        closeRetainedQuietly()
        closeReplayOutputsQuietly()
        closed = true
        throw failure
    }

    private fun closeActiveQuietly() {
        val decoder = active
        active = null
        try {
            decoder?.close()
        } catch (_: Throwable) {
            // Preserve the decode/reopen failure that made this cleanup necessary.
        }
    }

    private fun closeRetainedAndReplayOutputs(): Throwable? {
        var first: Throwable? = null
        retained.forEach { entry ->
            try {
                entry.packet.close()
            } catch (failure: Throwable) {
                first = first ?: failure
            }
        }
        retained.clear()
        retainedBytes = 0
        while (replayOutputs.isNotEmpty()) {
            try {
                replayOutputs.removeFirst().close()
            } catch (failure: Throwable) {
                first = first ?: failure
            }
        }
        while (hardwareOutputs.isNotEmpty()) {
            try {
                hardwareOutputs.removeFirst().close()
            } catch (failure: Throwable) {
                first = first ?: failure
            }
        }
        return first
    }

    private fun closeRetainedQuietly() {
        retained.forEach { it.packet.closeQuietly() }
        retained.clear()
        retainedBytes = 0
    }

    private fun closeReplayOutputsQuietly() {
        while (replayOutputs.isNotEmpty()) replayOutputs.removeFirst().closeQuietly()
        closeHardwareOutputsQuietly()
    }

    private fun closeHardwareOutputsQuietly() {
        while (hardwareOutputs.isNotEmpty()) hardwareOutputs.removeFirst().closeQuietly()
    }

    private fun resetReplayState() {
        retainedBytes = 0
        pendingBoundary = null
        hasConfirmedBoundary = false
        deliveredSinceBoundary = 0
        deliveredThisEpoch = 0
        replaySuppressRemaining = 0
        hardwareDrainAccepted = false
    }

    private fun checkOpen() {
        check(!closed) { "decoder is closed" }
    }

    private fun requireActive(): VideoDecoder = checkNotNull(active) { "decoder has no active owner" }
}

private inline fun <T> List<T>.indexOfFirstFrom(startIndex: Int, predicate: (T) -> Boolean): Int {
    for (index in startIndex until size) if (predicate(this[index])) return index
    return -1
}

private fun AutoCloseable?.closeQuietly() {
    try {
        this?.close()
    } catch (_: Throwable) {
        // Cleanup must not hide the operation that failed.
    }
}

private fun Throwable.failureDetail(): String = message?.takeIf { it.isNotBlank() }
    ?: this::class.simpleName
    ?: "unknown failure"

private fun Throwable.rethrowCancellation() {
    if (this is CancellationException) throw this
}
