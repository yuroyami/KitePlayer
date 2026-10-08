package io.github.yuroyami.kiteplayer.network.dash

import io.github.yuroyami.kiteplayer.subtitle.StyledSpan
import io.github.yuroyami.kiteplayer.subtitle.TtmlParser

/** One subtitle cue: its text, with `<i>`, `<b>` and `<u>` and line breaks, from [startMicros] until [endMicros]. */
internal class TimedCue(val startMicros: Long, val endMicros: Long, val text: String)

/**
 * TTML documents as cues (#402), for the subtitle sets of a DASH presentation that FFmpeg cannot
 * read: a sidecar TTML file, and the TTML document in each sample of an `stpp` track. The cues
 * reach the player as WebVTT, which the HLS path already plays.
 *
 * The document is read by kiteplayer-subtitles' [TtmlParser] (#492), which every TTML file the
 * player opens goes through. What is kept here is what a cue can carry through the player's packet
 * path: the text, its line breaks, and italic, bold and underline. Regions and the other styles do
 * not cross the WebVTT conversion.
 */
internal object Ttml {

    /** The cues of [xml], in time order, their times plus [offsetMicros]. */
    fun cues(xml: String, offsetMicros: Long = 0): List<TimedCue> = TtmlParser.read(xml).mapNotNull { cue ->
        val text = webVttText(cue.spans)
        if (text.isEmpty()) null else TimedCue(cue.startMicros + offsetMicros, cue.endMicros + offsetMicros, text)
    }

    /**
     * [spans] as WebVTT cue text: each run escaped and inside its own tags, because WebVTT cannot
     * turn italic off inside `<i>`. Neighbouring runs in the same look share their tags.
     */
    private fun webVttText(spans: List<StyledSpan>): String = buildString {
        var tags = emptyList<String>()
        var open = false
        fun close() {
            if (open) tags.asReversed().forEach { append("</").append(it).append('>') }
            open = false
        }
        for (span in spans) {
            val look = tagsOf(span)
            span.text.split('\n').forEachIndexed { index, part ->
                if (index > 0) {
                    close()
                    append('\n')
                }
                if (part.isEmpty()) return@forEachIndexed
                if (!open || look != tags) {
                    close()
                    tags = look
                    tags.forEach { append('<').append(it).append('>') }
                    open = true
                }
                append(escape(part))
            }
        }
        close()
    }

    private fun tagsOf(span: StyledSpan): List<String> = buildList {
        if (span.style.italic) add("i")
        if (span.style.bold) add("b")
        if (span.style.underline) add("u")
    }

    private fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

/** [cues] as one WebVTT document, which FFmpeg's WebVTT reader takes as one subtitle segment. */
internal fun webVtt(cues: List<TimedCue>): String = buildString {
    append("WEBVTT\n\n")
    for (cue in cues) {
        append(vttTime(cue.startMicros)).append(" --> ").append(vttTime(cue.endMicros)).append('\n')
        append(cue.text).append("\n\n")
    }
}

/** Microseconds as a WebVTT timestamp, `hh:mm:ss.ttt`, never negative. */
private fun vttTime(micros: Long): String {
    val millis = micros.coerceAtLeast(0) / 1000
    val hours = millis / 3_600_000
    val minutes = millis / 60_000 % 60
    val seconds = millis / 1000 % 60
    val fraction = millis % 1000
    return "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}:" +
        "${seconds.toString().padStart(2, '0')}.${fraction.toString().padStart(3, '0')}"
}
