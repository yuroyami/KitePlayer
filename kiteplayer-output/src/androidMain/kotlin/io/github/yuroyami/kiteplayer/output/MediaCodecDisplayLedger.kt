package io.github.yuroyami.kiteplayer.output

import android.os.Build

/**
 * True where MediaCodec's rendered callback arrives for every frame the display showed. The
 * documentation of `MediaCodec.OnFrameRenderedListener` promises that from Android 14. Before it,
 * a shown frame can get no callback, so a missing callback proves nothing there.
 */
internal fun mediaCodecReportsEveryRender(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

/** What a renderer learns about one frame it released for display. */
internal interface MediaCodecDisplayReport {
    /** The display showed the frame at [atNanos], on the [System.nanoTime] clock. */
    fun displayed(atNanos: Long)

    /** The display never showed the frame: a newer one was shown in its place. */
    fun lost()
}

/**
 * Pairs MediaCodec's rendered callbacks with the frames released for display.
 *
 * Since Android 14 the platform sends one rendered callback for every frame the display showed, in
 * order, although late and sometimes in batches. So when a frame's callback arrives, a frame released
 * before it that got none was never shown: the compositor showed a newer frame in its place. Before
 * this, such a frame counted as presented and the loss was invisible (#139).
 *
 * The callbacks arrive on MediaCodec's own thread and the releases on the decode worker, so every
 * access holds the lock. The answers run outside it.
 */
internal class MediaCodecDisplayLedger {
    private val lock = Any()
    private val waiting = ArrayDeque<Pending>()

    private class Pending(val ptsUs: Long, val report: MediaCodecDisplayReport)

    fun released(ptsUs: Long, report: MediaCodecDisplayReport) = synchronized(lock) {
        waiting.addLast(Pending(ptsUs, report))
    }

    /** Answers one rendered callback: its frame was shown, and older unanswered ones were lost. */
    fun rendered(ptsUs: Long, atNanos: Long) {
        val lost = mutableListOf<MediaCodecDisplayReport>()
        val shown = synchronized(lock) {
            val index = waiting.indexOfFirst { it.ptsUs == ptsUs }
            // Not held: released before a flush, or already answered.
            if (index < 0) return
            repeat(index) { lost += waiting.removeFirst().report }
            waiting.removeFirst().report
        }
        lost.forEach(MediaCodecDisplayReport::lost)
        shown.displayed(atNanos)
    }

    /** A flush or a close. Frames still waiting get no answer, because nothing can prove either one. */
    fun clear() = synchronized(lock) {
        waiting.clear()
    }
}
