package io.github.yuroyami.kiteplayer.subtitle

/**
 * The Kotlin ASS dialogue tier (KPKMP 17.12 M2): SubStation Alpha (.ssa) and Advanced
 * SubStation Alpha (.ass) parsed in pure commonMain onto the engine's own cue model.
 *
 * What it maps, per the register: styles and the dialogue-grade override subset. Fonts,
 * colours, outline and shadow, positioning (`\pos`, and `\move`'s start point), alignment
 * (`\an` and legacy `\a`), margins, bold, italic, underline, strike-out, and basic fades
 * (`\fad`). Line breaks (`\N`, `\n`, `\h`), style resets (`\r`) and wrap style (`\q`) are
 * honoured; karaoke timing tags are stripped with their text kept; vector drawings (`\p`)
 * are dropped whole. Everything beyond that (rotation, shear, clipping, animated `\t`,
 * `\fade`'s seven-argument form) is phase L's libass module, and unknown override tags are
 * ignored rather than shown.
 *
 * Two entries, one grammar. [parse] takes a whole document, the external-file case.
 * [trackParser] takes just the header (a container's codec extradata carries exactly that)
 * and returns a parser for the per-packet event lines FFmpeg-normalised containers ship.
 */
public object AssParser {

    /** Parses a whole .ass/.ssa document into timed cues, event order preserved. */
    public fun parse(text: String): List<SubtitleCue> {
        val document = AssDocument(text)
        return document.dialogueLines.mapNotNull { fields ->
            val start = parseAssTime(fields["start"] ?: return@mapNotNull null) ?: return@mapNotNull null
            val end = parseAssTime(fields["end"] ?: return@mapNotNull null) ?: return@mapNotNull null
            if (end <= start) return@mapNotNull null
            document.buildCue(fields, start, end)
        }
    }

    /**
     * Builds a per-event parser from an ASS HEADER (everything before [Events]' Dialogue
     * lines; a Matroska ass track's codec extradata is exactly this). Malformed headers
     * yield a parser with default styles rather than failing: an unstyled subtitle beats none.
     */
    public fun trackParser(header: String): AssTrackParser = AssTrackParser(AssDocument(header))
}

/** Parses the FFmpeg-normalised embedded event form against one track's parsed header. */
public class AssTrackParser internal constructor(private val document: AssDocument) {

    /**
     * One embedded event: `ReadOrder,Layer,Style,Name,MarginL,MarginR,MarginV,Effect,Text`.
     * Timing comes from the packet, which is where containers keep it. Returns null for a
     * line with no visible text (a drawing, an empty event, a malformed payload).
     */
    public fun parseEvent(line: String, startMicros: Long, endMicros: Long): SubtitleCue.Text? {
        val parts = line.split(',', limit = 9)
        if (parts.size < 9) return null
        val fields = mapOf(
            "layer" to parts[1],
            "style" to parts[2],
            "marginl" to parts[4],
            "marginr" to parts[5],
            "marginv" to parts[6],
            "text" to parts[8],
        )
        return document.buildCue(fields, startMicros, endMicros)
    }
}

/** One parsed ASS style, already in the cue model's vocabulary. */
internal class AssStyle(
    val style: CueStyle,
    val alignment: CueAlignment,
    val marginL: Int,
    val marginR: Int,
    val marginV: Int,
)

/** The parsed header plus the raw Dialogue lines when the text carried an [Events] section. */
internal class AssDocument(text: String) {

    var playResX: Int = 0
        private set
    var playResY: Int = 0
        private set
    private val styles = mutableMapOf<String, AssStyle>()
    val dialogueLines = mutableListOf<Map<String, String>>()

    private val defaultStyle = AssStyle(
        style = CueStyle(),
        alignment = CueAlignment.BottomCenter,
        marginL = 0, marginR = 0, marginV = 0,
    )

    init {
        var section = ""
        var styleFormat: List<String> = emptyList()
        var eventFormat: List<String> = emptyList()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim().removePrefix("﻿").trim()
            if (line.isEmpty() || line.startsWith(";")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length - 1).trim().lowercase()
                continue
            }
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            when (section) {
                // Only the script's own resolution is taken. `Collisions:` is deliberately not
                // read: a script asking for Reverse stacking gets the one fixed order, because
                // honouring it is a layout change in every rasterizer, not a parse change here.
                "script info" -> when (key) {
                    "playresx" -> playResX = value.toIntOrNull() ?: playResX
                    "playresy" -> playResY = value.toIntOrNull() ?: playResY
                }
                "v4+ styles", "v4 styles", "v4++ styles" -> when (key) {
                    "format" -> styleFormat = value.split(',').map { it.trim().lowercase() }
                    "style" -> parseStyle(value, styleFormat, legacy = section == "v4 styles")
                }
                "events" -> when (key) {
                    "format" -> eventFormat = value.split(',').map { it.trim().lowercase() }
                    "dialogue" -> {
                        val format = eventFormat.ifEmpty { DEFAULT_EVENT_FORMAT }
                        val parts = value.split(',', limit = format.size)
                        if (parts.size == format.size) {
                            dialogueLines += format.indices.associate { format[it] to parts[it].trim() }
                                .plus("text" to parts.last())
                        }
                    }
                }
            }
        }
        // ASS defaults when the script declares only one axis, or neither (the SSA default).
        if (playResX <= 0 && playResY <= 0) { playResX = 384; playResY = 288 }
        else if (playResX <= 0) playResX = playResY * 4 / 3
        else if (playResY <= 0) playResY = playResX * 3 / 4
    }

    private fun parseStyle(value: String, format: List<String>, legacy: Boolean) {
        if (format.isEmpty()) return
        val parts = value.split(',', limit = format.size)
        if (parts.size != format.size) return
        val f = format.indices.associate { format[it] to parts[it].trim() }
        val name = f["name"] ?: return
        val alignmentRaw = f["alignment"]?.toIntOrNull() ?: 2
        val alignment = if (legacy) legacyAlignment(alignmentRaw) else numpadAlignment(alignmentRaw)
        styles[name.lowercase()] = AssStyle(
            style = CueStyle(
                fontFamily = f["fontname"]?.takeIf { it.isNotEmpty() },
                fontSizePx = f["fontsize"]?.toFloatOrNull(),
                bold = assBool(f["bold"]),
                italic = assBool(f["italic"]),
                underline = assBool(f["underline"]),
                strikeThrough = assBool(f["strikeout"]),
                primaryColor = assColor(f["primarycolour"]) ?: 0xFFFFFFFF.toInt(),
                outlineColor = assColor(f["outlinecolour"] ?: f["tertiarycolour"]) ?: 0xFF000000.toInt(),
                shadowColor = assColor(f["backcolour"]) ?: 0x80000000.toInt(),
                outlineWidthPx = f["outline"]?.toFloatOrNull() ?: 2f,
                shadowOffsetPx = f["shadow"]?.toFloatOrNull() ?: 1f,
            ),
            alignment = alignment,
            marginL = f["marginl"]?.toIntOrNull() ?: 0,
            marginR = f["marginr"]?.toIntOrNull() ?: 0,
            marginV = f["marginv"]?.toIntOrNull() ?: 0,
        )
    }

    /** Builds one cue from an event's fields; shared by the document and embedded forms. */
    fun buildCue(fields: Map<String, String>, startMicros: Long, endMicros: Long): SubtitleCue.Text? {
        val base = styles[fields["style"]?.trim()?.lowercase()] ?: defaultStyle
        val parsed = parseOverrideText(fields["text"] ?: return null, base, styles, defaultStyle)
        if (parsed.spans.isEmpty() || parsed.spans.all { it.text.isBlank() }) return null

        val marginL = fields["marginl"]?.toIntOrNull()?.takeIf { it > 0 } ?: base.marginL
        val marginR = fields["marginr"]?.toIntOrNull()?.takeIf { it > 0 } ?: base.marginR
        val marginV = fields["marginv"]?.toIntOrNull()?.takeIf { it > 0 } ?: base.marginV
        val layout = CueLayout(
            alignment = parsed.alignment ?: base.alignment,
            marginLeft = if (marginL > 0) marginL.toFloat() / playResX else 0.05f,
            marginRight = if (marginR > 0) marginR.toFloat() / playResX else 0.05f,
            marginVertical = if (marginV > 0) marginV.toFloat() / playResY else 0.05f,
            positionX = parsed.posX?.let { it / playResX },
            positionY = parsed.posY?.let { it / playResY },
            authoredHeight = playResY,
            wrap = parsed.wrap,
            fadeInMicros = parsed.fadeInMillis * 1000L,
            fadeOutMicros = parsed.fadeOutMillis * 1000L,
        )
        return SubtitleCue.Text(
            startMicros = startMicros,
            endMicros = endMicros,
            spans = parsed.spans,
            layout = layout,
            layer = fields["layer"]?.toIntOrNull() ?: 0,
        )
    }

    private companion object {
        val DEFAULT_EVENT_FORMAT = listOf(
            "layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text",
        )
    }
}

private class ParsedText(
    val spans: List<StyledSpan>,
    val alignment: CueAlignment?,
    val posX: Float?,
    val posY: Float?,
    val wrap: CueWrap,
    val fadeInMillis: Long,
    val fadeOutMillis: Long,
)

/** Walks one event's text, applying override blocks to produce styled spans. */
private fun parseOverrideText(
    text: String,
    base: AssStyle,
    styles: Map<String, AssStyle>,
    defaultStyle: AssStyle,
): ParsedText {
    val spans = mutableListOf<StyledSpan>()
    val current = StringBuilder()
    var style = base.style
    var alignment: CueAlignment? = null
    var posX: Float? = null
    var posY: Float? = null
    var wrap = CueWrap.Balanced
    var fadeIn = 0L
    var fadeOut = 0L
    var drawing = false

    fun flushSpan() {
        if (current.isNotEmpty()) {
            spans += StyledSpan(current.toString(), style)
            current.clear()
        }
    }

    var i = 0
    while (i < text.length) {
        val ch = text[i]
        when {
            ch == '{' -> {
                val close = text.indexOf('}', i + 1)
                if (close < 0) { i = text.length; break }
                val block = text.substring(i + 1, close)
                // Only blocks that carry override tags are consumed; a plain {note} is shown
                // by every player as nothing, so it is dropped the same way here.
                var tagAt = block.indexOf('\\')
                while (tagAt >= 0) {
                    val tagEnd = block.indexOf('\\', tagAt + 1).let { if (it < 0) block.length else it }
                    val tag = block.substring(tagAt + 1, tagEnd).trim()
                    var newStyle = style
                    when {
                        tag.startsWith("an") -> tag.drop(2).toIntOrNull()?.let { alignment = numpadAlignment(it) }
                        tag.startsWith("a") && tag.getOrNull(1)?.isDigit() == true ->
                            tag.drop(1).toIntOrNull()?.let { alignment = legacyAlignment(it) }
                        tag.startsWith("pos(") -> parsePair(tag, "pos(")?.let { (x, y) -> posX = x; posY = y }
                        tag.startsWith("move(") -> parsePair(tag, "move(")?.let { (x, y) ->
                            // The dialogue tier shows the start point; motion is phase L's.
                            posX = x; posY = y
                        }
                        tag.startsWith("fad(") -> parsePair(tag, "fad(")?.let { (a, b) ->
                            fadeIn = a.toLong(); fadeOut = b.toLong()
                        }
                        tag.startsWith("fn") -> newStyle = newStyle.copy(fontFamily = tag.drop(2).takeIf { it.isNotEmpty() })
                        tag.startsWith("fs") && tag.getOrNull(2)?.isDigit() == true ->
                            tag.drop(2).toFloatOrNull()?.let { newStyle = newStyle.copy(fontSizePx = it) }
                        tag.startsWith("1c") || (tag.startsWith("c") && !tag.startsWith("clip")) -> {
                            val v = tag.substringAfter("c")
                            assColor(v)?.let { newStyle = newStyle.copy(primaryColor = it) }
                        }
                        tag.startsWith("3c") -> assColor(tag.drop(2))?.let { newStyle = newStyle.copy(outlineColor = it) }
                        tag.startsWith("4c") -> assColor(tag.drop(2))?.let { newStyle = newStyle.copy(shadowColor = it) }
                        tag.startsWith("bord") -> tag.drop(4).toFloatOrNull()?.let { newStyle = newStyle.copy(outlineWidthPx = it) }
                        tag.startsWith("shad") -> tag.drop(4).toFloatOrNull()?.let { newStyle = newStyle.copy(shadowOffsetPx = it) }
                        tag == "b1" -> newStyle = newStyle.copy(bold = true)
                        tag == "b0" -> newStyle = newStyle.copy(bold = false)
                        tag == "i1" -> newStyle = newStyle.copy(italic = true)
                        tag == "i0" -> newStyle = newStyle.copy(italic = false)
                        tag == "u1" -> newStyle = newStyle.copy(underline = true)
                        tag == "u0" -> newStyle = newStyle.copy(underline = false)
                        tag == "s1" -> newStyle = newStyle.copy(strikeThrough = true)
                        tag == "s0" -> newStyle = newStyle.copy(strikeThrough = false)
                        tag.startsWith("q") -> when (tag.drop(1).toIntOrNull()) {
                            2 -> wrap = CueWrap.Never
                            else -> wrap = CueWrap.Balanced
                        }
                        tag.startsWith("p") && tag.getOrNull(1)?.isDigit() == true ->
                            drawing = (tag.drop(1).toIntOrNull() ?: 0) > 0
                        tag.startsWith("r") -> {
                            val named = tag.drop(1).trim()
                            newStyle = if (named.isEmpty()) base.style
                            else (styles[named.lowercase()] ?: defaultStyle).style
                        }
                        // Karaoke and everything else: unknown tags are ignored, text kept.
                    }
                    if (newStyle != style) {
                        flushSpan()
                        style = newStyle
                    }
                    tagAt = if (tagEnd >= block.length) -1 else tagEnd
                }
                i = close + 1
            }
            ch == '\\' && i + 1 < text.length -> {
                when (text[i + 1]) {
                    'N', 'n' -> { if (!drawing) current.append('\n'); i += 2 }
                    'h' -> { if (!drawing) current.append(' '); i += 2 }
                    else -> { if (!drawing) current.append(ch); i++ }
                }
            }
            else -> { if (!drawing) current.append(ch); i++ }
        }
    }
    flushSpan()
    return ParsedText(spans, alignment, posX, posY, wrap, fadeIn, fadeOut)
}

/** Two comma-separated numbers out of `name(x,y[,...])`. */
private fun parsePair(tag: String, prefix: String): Pair<Float, Float>? {
    val body = tag.removePrefix(prefix).substringBefore(')')
    val parts = body.split(',')
    if (parts.size < 2) return null
    val x = parts[0].trim().toFloatOrNull() ?: return null
    val y = parts[1].trim().toFloatOrNull() ?: return null
    return x to y
}

/** ASS `&HAABBGGRR&` (alpha inverted, blue first) to the cue model's ARGB. */
internal fun assColor(raw: String?): Int? {
    if (raw.isNullOrEmpty()) return null
    val trimmed = raw.trim()
    // Hex ONLY behind the &H prefix: a bare "16777215" is SSA's decimal form, and reading it
    // as hex silently miscolours every legacy script.
    val value = if (trimmed.startsWith("&") || trimmed.startsWith("H", ignoreCase = true)) {
        trimmed.removePrefix("&").removePrefix("H").removePrefix("h").trimEnd('&').toLongOrNull(16)
    } else {
        trimmed.toLongOrNull()
    } ?: return null
    val blue = (value shr 16 and 0xFF).toInt()
    val green = (value shr 8 and 0xFF).toInt()
    val red = (value and 0xFF).toInt()
    val alpha = 255 - (value shr 24 and 0xFF).toInt()
    return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
}

/** ASS booleans: -1 (and any nonzero) is true, 0 false. */
private fun assBool(raw: String?): Boolean = (raw?.toIntOrNull() ?: 0) != 0

/** V4+ numpad alignment: 1..3 bottom, 4..6 middle, 7..9 top, left to right within each row. */
internal fun numpadAlignment(value: Int): CueAlignment = when (value) {
    1 -> CueAlignment.BottomLeft
    2 -> CueAlignment.BottomCenter
    3 -> CueAlignment.BottomRight
    4 -> CueAlignment.MiddleLeft
    5 -> CueAlignment.MiddleCenter
    6 -> CueAlignment.MiddleRight
    7 -> CueAlignment.TopLeft
    8 -> CueAlignment.TopCenter
    9 -> CueAlignment.TopRight
    else -> CueAlignment.BottomCenter
}

/** Legacy SSA `\a`: 1..3 bottom, +4 top, +8 middle, same left/centre/right order. */
internal fun legacyAlignment(value: Int): CueAlignment = when (value) {
    1 -> CueAlignment.BottomLeft
    2 -> CueAlignment.BottomCenter
    3 -> CueAlignment.BottomRight
    5 -> CueAlignment.TopLeft
    6 -> CueAlignment.TopCenter
    7 -> CueAlignment.TopRight
    9 -> CueAlignment.MiddleLeft
    10 -> CueAlignment.MiddleCenter
    11 -> CueAlignment.MiddleRight
    else -> CueAlignment.BottomCenter
}

/** `H:MM:SS.CC` (centiseconds) to microseconds, tolerant of extra digits. */
internal fun parseAssTime(raw: String): Long? {
    val parts = raw.trim().split(':')
    if (parts.size != 3) return null
    val hours = parts[0].toLongOrNull() ?: return null
    val minutes = parts[1].toLongOrNull() ?: return null
    val secondsParts = parts[2].split('.')
    val seconds = secondsParts[0].toLongOrNull() ?: return null
    val centis = secondsParts.getOrNull(1)?.padEnd(2, '0')?.take(2)?.toLongOrNull() ?: 0L
    return ((hours * 3600 + minutes * 60 + seconds) * 100 + centis) * 10_000
}
