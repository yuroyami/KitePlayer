package io.github.yuroyami.kiteplayer.libass

/**
 * Typesetting-heavy scripts, each exercising what the Kotlin dialogue tier cannot draw: motion,
 * animated transforms, rotation, clipping, karaoke fills, fades, vector drawings, layered signs.
 *
 * Every script is complete, so it can be opened whole AND streamed event by event in the Matroska
 * form, which is the comparison the reference tests make. The header is shared so the styles the
 * events name resolve the same way on both paths.
 */
internal object TypesetCorpus {

    val header: String = """
        [Script Info]
        ScriptType: v4.00+
        PlayResX: 640
        PlayResY: 360
        WrapStyle: 0

        [V4+ Styles]
        Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
        Style: Default,Helvetica,36,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,0,2,10,10,10,1
        Style: Sign,Helvetica,48,&H0000FF00,&H000000FF,&H00000000,&H00000000,-1,0,0,0,100,100,0,0,1,3,0,8,10,10,10,1
        Style: Karaoke,Helvetica,40,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,1,2,10,10,40,1

        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
    """.trimIndent() + "\n"

    /** Name to the Dialogue lines, in file order. */
    val scripts: Map<String, List<String>> = mapOf(
        "moving-sign" to listOf(
            "Dialogue: 0,0:00:01.00,0:00:05.00,Sign,,0,0,0,,{\\move(40,40,560,40)}Moving sign",
            "Dialogue: 1,0:00:01.00,0:00:05.00,Default,,0,0,0,,Static line at the bottom",
        ),
        "transform-and-rotation" to listOf(
            "Dialogue: 0,0:00:01.00,0:00:05.00,Sign,,0,0,0,,{\\pos(320,180)\\t(\\frz360)\\t(\\c&HFF0000&)}Turning and tinting",
            "Dialogue: 0,0:00:01.50,0:00:04.50,Default,,0,0,0,,{\\frx30\\fry20\\fscx120}Skewed",
        ),
        "karaoke-fill" to listOf(
            "Dialogue: 0,0:00:01.00,0:00:05.00,Karaoke,,0,0,0,,{\\k100}Kara{\\k100}oke {\\k100}fill {\\k100}line",
            "Dialogue: 0,0:00:01.00,0:00:05.00,Karaoke,,0,0,0,,{\\an8\\kf200}Sweep {\\kf200}fill",
        ),
        "clip-fade-and-drawing" to listOf(
            "Dialogue: 0,0:00:01.00,0:00:05.00,Default,,0,0,0,,{\\clip(100,100,540,260)\\fad(500,500)}Clipped and fading text",
            "Dialogue: 0,0:00:01.00,0:00:05.00,Sign,,0,0,0,,{\\pos(320,300)\\p1\\c&H0000FF&}m 0 0 l 100 0 100 40 0 40{\\p0}",
            "Dialogue: 2,0:00:02.00,0:00:04.00,Sign,,0,0,0,,{\\pos(320,120)\\bord6\\blur2\\3c&HFFFF00&}Layered over",
        ),
    )

    /** One event as a Matroska packet: `ReadOrder,Layer,Style,Name,MarginL,MarginR,MarginV,Effect,Text`. */
    class Chunk(val readOrder: Int, val startMillis: Long, val durationMillis: Long, val payload: String)

    /** Turns a script's Dialogue lines into the packets a demuxer would hand out. */
    fun chunks(lines: List<String>): List<Chunk> = lines.mapIndexed { index, line ->
        val fields = line.removePrefix("Dialogue: ").split(',', limit = 10)
        val start = assTimeMillis(fields[1])
        val end = assTimeMillis(fields[2])
        val rest = (listOf(fields[0]) + fields.drop(3)).joinToString(",")
        Chunk(index, start, end - start, "$index,$rest")
    }

    private fun assTimeMillis(text: String): Long {
        val (h, m, s) = text.trim().split(':')
        val seconds = s.toDouble()
        return h.toLong() * 3_600_000 + m.toLong() * 60_000 + (seconds * 1000).toLong()
    }
}
