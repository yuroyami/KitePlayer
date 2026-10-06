package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.github.yuroyami.kiteplayer.SourceRefusal
import kotlinx.coroutines.delay
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The reader of a file that is still being written (#430), over [inner], which answers with the
 * file's current size.
 *
 * At what looks like the end, it waits [POLL] and reads again, and it answers the end once
 * [endsAfter] has passed with no new byte, as mpv's `appending://` protocol does. It waits only
 * while [waitAtEnd] is set, which the source sets while it reads packets. An open's probing and a
 * seek's search read up to the end too, and there the end the current size gives is the right
 * answer, so a wait there would only make them seconds slower each.
 *
 * The wait is a suspension, so the bridge's interrupt ends it, and a seek, a stop or a close does
 * not wait behind it.
 */
internal class GrowingMediaIo(private val inner: MediaIo, private val endsAfter: Duration) : MediaIo {
    /** True while the end is a moment to wait for more rather than the end. */
    @Volatile
    var waitAtEnd: Boolean = false

    private var position = 0L

    /** Where a whole wait last found nothing new: FFmpeg reads there again, and the end stands. */
    private var endedAt = -1L

    /** The size at the open, from which the source's length follows the file. */
    val sizeAtOpen: Long? = inner.size

    override val size: Long? get() = inner.size
    override val seekable: Boolean get() = inner.seekable
    override val location: String? get() = inner.location
    override val contentType: String? get() = inner.contentType

    override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
        val first = inner.read(into, offset, length)
        if (first != -1 || !waitAtEnd || position == endedAt) return first.also { if (it > 0) position += it }
        var waited = Duration.ZERO
        while (waited < endsAfter) {
            delay(POLL)
            waited += POLL
            // A reader that keeps its end, as one over a network does, reads on after a seek.
            if (inner.seekable) inner.seek(position)
            val count = inner.read(into, offset, length)
            if (count > 0) {
                position += count
                return count
            }
        }
        endedAt = position
        return -1
    }

    override suspend fun seek(position: Long) {
        inner.seek(position)
        this.position = position
    }

    override fun setWarningSink(sink: (PlaybackWarning) -> Unit) = inner.setWarningSink(sink)

    override suspend fun openRelated(uri: String): MediaIo? = inner.openRelated(uri)

    override fun networkBitsPerSecond(): Long? = inner.networkBitsPerSecond()

    override fun takeTags(): Map<String, String>? = inner.takeTags()

    override fun takeRefusal(): SourceRefusal? = inner.takeRefusal()

    override fun close() = inner.close()

    companion object {
        /** How often the end is read again while the file may still grow. */
        val POLL: Duration = 100.milliseconds
    }
}
