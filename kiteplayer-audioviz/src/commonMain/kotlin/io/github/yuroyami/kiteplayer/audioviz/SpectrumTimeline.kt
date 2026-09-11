package io.github.yuroyami.kiteplayer.audioviz

import kotlin.concurrent.Volatile

/**
 * Holds recent analyses so a drawing can pick the one that matches what is audible now.
 *
 * This exists because of where the samples are taken. A player analyses audio as it hands it to
 * the device, and the device holds a buffer, so the analysis is ready before the sound is heard.
 * Drawing it straight away puts the picture ahead of the music by the buffer's whole depth.
 *
 * Push every analysis here with the timestamp it carries, then ask [at] for the one matching the
 * player's reported position. The wait is spent in this queue rather than on screen.
 *
 * One thread pushes, any thread reads. Reference writes are atomic and the reader tolerates a
 * stale index, so no lock is needed.
 */
@AudioVizAuthoringApi
public class SpectrumTimeline(public val capacity: Int = 128) {

    init {
        require(capacity >= 2) { "capacity must be at least 2, was $capacity" }
    }

    private val slots = arrayOfNulls<SpectrumFrame>(capacity)

    @Volatile
    private var written = 0L

    public fun push(frame: SpectrumFrame) {
        slots[(written % capacity).toInt()] = frame
        written++
    }

    /**
     * The newest analysis that is not from the future, or null when the queue holds nothing old
     * enough. Frames older than the queue's depth are gone, which is fine: nothing draws them.
     */
    public fun at(ptsMicros: Long): SpectrumFrame? {
        val end = written
        var best: SpectrumFrame? = null
        var index = end - 1
        val oldest = maxOf(0L, end - capacity)
        while (index >= oldest) {
            val candidate = slots[(index % capacity).toInt()] ?: break
            if (candidate.ptsMicros <= ptsMicros) {
                best = candidate
                break
            }
            index--
        }
        return best
    }

    /**
     * The analysis that will be audible [seconds] from [ptsMicros].
     *
     * The queue exists because the analysis finishes before the sound is heard, and everything
     * still waiting in it is music the listener has not reached yet. That is a real look into the
     * future, not a prediction: a drawing can start to swell before a kick lands rather than
     * after it, which is what a person moving to music does.
     *
     * Falls back to the newest analysis when the queue does not reach that far.
     */
    public fun ahead(ptsMicros: Long, seconds: Float): SpectrumFrame? =
        at(ptsMicros + (seconds * 1_000_000f).toLong()) ?: newest()

    /**
     * Seconds until the next onset already sitting in the queue, or -1 when there is none.
     *
     * This is exact rather than predicted, because the audio it describes has been analysed and
     * simply has not been played yet.
     */
    public fun nextOnsetSeconds(ptsMicros: Long): Float {
        val end = written
        val oldest = maxOf(0L, end - capacity)
        var soonest = -1L
        var index = end - 1
        while (index >= oldest) {
            val candidate = slots[(index % capacity).toInt()] ?: break
            if (candidate.ptsMicros <= ptsMicros) break
            if (candidate.beat > 0f) soonest = candidate.ptsMicros
            index--
        }
        return if (soonest < 0) -1f else (soonest - ptsMicros) / 1_000_000f
    }

    /**
     * The analysis at [ptsMicros], blended between the two readings either side of it.
     *
     * The analyser produces about ninety four readings a second. A display running faster than that
     * shows some readings twice, and on a 120 or 144 Hz panel the bars visibly step. Blending the
     * two readings either side of the moment being drawn removes the steps. Null before the first
     * reading, and the newest reading when the moment is past the end of the queue.
     */
    public fun interpolated(ptsMicros: Long): SpectrumFrame? {
        val end = written
        val oldest = maxOf(0L, end - capacity)
        var before: SpectrumFrame? = null
        var after: SpectrumFrame? = null
        var index = end - 1
        while (index >= oldest) {
            val candidate = slots[(index % capacity).toInt()] ?: break
            if (candidate.ptsMicros <= ptsMicros) {
                before = candidate
                break
            }
            after = candidate
            index--
        }
        val earlier = before ?: return null
        val later = after ?: return earlier
        val span = (later.ptsMicros - earlier.ptsMicros).toFloat()
        if (span <= 0f) return earlier
        return earlier.blend(later, (ptsMicros - earlier.ptsMicros) / span)
    }

    /**
     * Interpolate the continuous readings, but deliver ALL drum events since the last display.
     * Polling a single analysis loses hits at 60 Hz and repeats them at 120 Hz. The interval is
     * open on the left, closed on the right; a seek only admits the newest 50 ms.
     */
    public fun sample(ptsMicros: Long, previousPtsMicros: Long): SpectrumFrame? {
        val frame = interpolated(ptsMicros) ?: return null
        val from = if (previousPtsMicros < 0 || previousPtsMicros > ptsMicros)
            ptsMicros - 50_000L else previousPtsMicros
        var beat = 0f
        var kick = 0f
        var snare = 0f
        var hat = 0f
        var strength = 0f
        var drop = false
        val end = written
        var index = end - 1
        val oldest = maxOf(0L, end - capacity)
        while (index >= oldest) {
            val candidate = slots[(index % capacity).toInt()] ?: break
            if (candidate.ptsMicros <= from) break
            if (candidate.ptsMicros <= ptsMicros) {
                beat = maxOf(beat, candidate.beat)
                kick = maxOf(kick, candidate.kick)
                snare = maxOf(snare, candidate.snare)
                hat = maxOf(hat, candidate.hat)
                strength = maxOf(strength, candidate.onsetStrength)
                drop = drop || candidate.drop
            }
            index--
        }
        return frame.withEvents(beat, kick, snare, hat, strength, drop)
    }

    /** The newest analysis whatever its timestamp. This is the unaligned picture. */
    public fun newest(): SpectrumFrame? =
        if (written == 0L) null else slots[((written - 1) % capacity).toInt()]

    public fun clear() {
        slots.fill(null)
        written = 0
    }
}
