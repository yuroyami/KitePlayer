package io.github.yuroyami.kiteplayer.network.dash

/**
 * The subtitle samples of a DASH presentation as cues (#402): TTML in MP4 (`stpp`) and WebVTT in
 * MP4 (`wvtt`), ISO/IEC 14496-30, whose segments FFmpeg's HLS reader cannot take, because it opens
 * every subtitle segment as WebVTT text.
 *
 * A sample holds the cues of its own span of the track's timeline. A `wvtt` sample holds one
 * `vttc` box for each cue it shows, whose `payl` box is the cue's text, or one `vtte` box when it
 * shows none. An `stpp` sample holds a whole TTML document. The guidelines count that document's
 * times from the start of the track, and some packagers count them from the start of the sample,
 * so a document whose cues all miss its sample's span is moved to the sample's start. Each cue is
 * cut to its sample's span, because a cue that spans two segments is repeated in both.
 */
internal object DashSubtitles {

    /** The cues of the media segment [segment] of the subtitle track that [init] describes, in its own timeline. */
    fun mp4Cues(init: ByteArray, segment: ByteArray): List<TimedCue> {
        val track = Fmp4.tracks(init).firstOrNull() ?: throw DashUnsupportedException("the subtitle initialization has no track")
        val out = ArrayList<TimedCue>()
        for (sample in Fmp4.samples(segment, track)) {
            val start = micros(sample.decodeTime + sample.compositionOffset, track.timescale)
            val end = micros(sample.decodeTime + sample.compositionOffset + sample.duration, track.timescale)
            val cues = when (track.sampleEntry) {
                "wvtt" -> wvttCues(sample.data, start, end)
                else -> ttmlCues(sample.data, start, end)
            }
            for (cue in cues) {
                val from = maxOf(cue.startMicros, start)
                val until = minOf(cue.endMicros, end)
                if (until > from) out += TimedCue(from, until, cue.text)
            }
        }
        return out
    }

    /** [cues] moved by [offsetMicros], from their track's timeline onto the picture's. */
    fun shift(cues: List<TimedCue>, offsetMicros: Long): List<TimedCue> =
        if (offsetMicros == 0L) cues else cues.map { TimedCue(it.startMicros + offsetMicros, it.endMicros + offsetMicros, it.text) }

    /** The TTML document of one `stpp` sample, from [start] to [end] of the track. */
    private fun ttmlCues(data: ByteArray, start: Long, end: Long): List<TimedCue> {
        // A sample may carry images after the document as subsamples; the document ends with </tt>.
        val all = data.decodeToString()
        val close = all.lastIndexOf("</tt>")
        val text = if (close >= 0) all.substring(0, close + 5) else all
        if (text.isBlank()) return emptyList()
        val cues = Ttml.cues(text)
        val ownSpan = cues.any { it.endMicros > start - TOLERANCE_MICROS && it.startMicros < end + TOLERANCE_MICROS }
        return if (ownSpan || cues.isEmpty()) cues else shift(cues, start)
    }

    /** The cues of one `wvtt` sample, each shown from [start] to [end]. */
    private fun wvttCues(data: ByteArray, start: Long, end: Long): List<TimedCue> =
        Fmp4.boxes(data, 0, data.size).filter { it.type == "vttc" }.mapNotNull { cue ->
            val payload = Fmp4.boxes(data, cue.dataStart, cue.end).firstOrNull { it.type == "payl" } ?: return@mapNotNull null
            val text = data.decodeToString(payload.dataStart, payload.end).trim()
            if (text.isEmpty()) null else TimedCue(start, end, text)
        }

    private fun micros(time: Long, timescale: Long): Long = time / timescale * 1_000_000 + time % timescale * 1_000_000 / timescale

    /** How far outside its sample's span a cue may start or end and still count as on the track's timeline. */
    private const val TOLERANCE_MICROS = 1_000_000L
}
