package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.spi.RendererEvent
import kotlinx.atomicfu.atomic
import kotlin.time.TimeSource

/**
 * Says what a renderer does with HDR: that it tone mapped it, or that it showed it as HDR. It
 * repeats a report at most once a second while it holds, and reports a change at once.
 *
 * The engine turns the first tone map report after an open shows its first frame into
 * `PlaybackWarning.HdrToneMapped`, and every report into the snapshot's dynamic range. A renderer
 * outlives an open, so a report made once per renderer left every later HDR item on that renderer
 * without its warning.
 */
internal class HdrAnnouncer(
    private val nowMillis: () -> Long = monotonicMillis(),
    private val emit: (RendererEvent) -> Unit,
) {
    private val lastMillis = atomic(NEVER)
    private val lastKind = atomic(NONE)

    /** Announces that a frame with the source transfer [transfer] was tone mapped. Safe from any thread. */
    fun announce(transfer: String) = say(TONE_MAPPED) { RendererEvent.ToneMapEngaged(transfer = transfer) }

    /** Announces that a frame with the source transfer [transfer] was shown as HDR, with [headroom]. */
    fun announceShown(transfer: String, headroom: Float) = say(SHOWN) { RendererEvent.HdrShown(transfer, headroom) }

    private inline fun say(kind: Int, event: () -> RendererEvent) {
        val now = nowMillis()
        val last = lastMillis.value
        if (lastKind.value == kind && last != NEVER && now - last < INTERVAL_MILLIS) return
        if (lastMillis.compareAndSet(last, now)) {
            lastKind.value = kind
            emit(event())
        }
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE
        const val INTERVAL_MILLIS = 1_000L
        const val NONE = 0
        const val TONE_MAPPED = 1
        const val SHOWN = 2

        fun monotonicMillis(): () -> Long {
            val origin = TimeSource.Monotonic.markNow()
            return { origin.elapsedNow().inWholeMilliseconds }
        }
    }
}
