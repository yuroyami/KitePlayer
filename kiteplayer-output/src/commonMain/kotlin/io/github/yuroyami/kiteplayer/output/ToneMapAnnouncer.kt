package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.spi.RendererEvent
import kotlinx.atomicfu.atomic
import kotlin.time.TimeSource

/**
 * Says that a renderer tone mapped HDR, at most once a second while it keeps doing so.
 *
 * The engine turns the first announcement after an open shows its first frame into
 * `PlaybackWarning.HdrToneMapped` and ignores the rest. A renderer outlives an open, so an
 * announcement made once per renderer left every later HDR item on that renderer without its
 * warning.
 */
internal class ToneMapAnnouncer(
    private val nowMillis: () -> Long = monotonicMillis(),
    private val emit: (RendererEvent) -> Unit,
) {
    private val lastMillis = atomic(NEVER)

    /** Announces that a frame with the source transfer [transfer] was tone mapped. Safe from any thread. */
    fun announce(transfer: String) {
        val now = nowMillis()
        val last = lastMillis.value
        if (last != NEVER && now - last < INTERVAL_MILLIS) return
        if (lastMillis.compareAndSet(last, now)) emit(RendererEvent.ToneMapEngaged(transfer = transfer))
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE
        const val INTERVAL_MILLIS = 1_000L

        fun monotonicMillis(): () -> Long {
            val origin = TimeSource.Monotonic.markNow()
            return { origin.elapsedNow().inWholeMilliseconds }
        }
    }
}
