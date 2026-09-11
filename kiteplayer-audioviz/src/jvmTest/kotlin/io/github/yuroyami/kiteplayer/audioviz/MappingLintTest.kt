package io.github.yuroyami.kiteplayer.audioviz

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Reads the drawings as text and refuses four mistakes that make drawings look alike.
 *
 * None of them is visible in a screenshot. They only show up when you play two different songs and notice the
 * picture did not change, which is not something a normal test run does. So they are caught here,
 * in the source, where they are obvious.
 */
class MappingLintTest {

    private val root = File("src/commonMain/kotlin/io/github/yuroyami/kiteplayer/audioviz/viz/presets")
    private val shaders = File("src/commonMain/kotlin/io/github/yuroyami/kiteplayer/audioviz/viz/shader")

    private class Complaint(val file: String, val line: Int, val text: String, val why: String) {
        override fun toString(): String = "$file:$line  $why\n      ${text.trim()}"
    }

    @Test
    fun noDrawingRunsOnTheWallClock() {
        // The strongest signal a viewer has for how busy the music is, is how fast things move.
        // A rate built from the wall clock cannot answer that. Drawings use the music clock, which
        // slows in quiet passages, or ask for a paced rate, or lock to the bar.
        val complaints = scan { line ->
            if (Regex("""state\.timeSeconds\s*\*""").containsMatchIn(line)) {
                "a speed taken from the wall clock. Use state.musicTime, state.paced() or a MusicClock"
            } else {
                null
            }
        }
        assertTrue(complaints.isEmpty(), "motion that ignores the music:\n" + complaints.joinToString("\n"))
    }

    @Test
    fun noDrawingComparesAudioToAFixedNumber() {
        // "Draw it if the band is above 0.35" is right for exactly one song. Every other song is
        // either always above it or never reaches it. Rank within the frame instead, with
        // state.percentile, and the same share of the picture is drawn whatever plays.
        // How sure we are of the tempo or the key is not a loudness. It is already a fixed scale
        // that means the same thing on every song, so comparing it to a number is right.
        val certainty = Regex("""(beatConfidence|keyConfidence)\s*(?:<|>|<=|>=)""")
        val audio = """(?:energy|value|strength|loud|nodeEnergy\[\w+\]|state\.energy|state\.drive|state\.mood|state\.frame\.\w+)"""
        val pattern = Regex("""$audio\s*(?:<|>|<=|>=)\s*(\d*\.\d+)f""")
        val complaints = scan { line ->
            if (certainty.containsMatchIn(line)) return@scan null
            val found = pattern.find(line) ?: return@scan null
            val threshold = found.groupValues[1].toFloat()
            // Very small numbers are "is this worth drawing at all" guards against zero, not
            // decisions about loudness, and they behave the same on every song.
            if (threshold < NEGLIGIBLE) null
            else "a fixed level of $threshold to compare audio against. Use state.percentile() instead"
        }
        assertTrue(
            complaints.isEmpty(),
            "levels that are right for one song and wrong for the next:\n" + complaints.joinToString("\n"),
        )
    }

    @Test
    fun nothingIsSpawnedPerFrameRatherThanPerSecond() {
        // A count worked out per frame doubles on a 120 Hz display and halves on a 30 Hz one. Rates
        // are per second, multiplied by the time the frame took.
        val complaints = scan { line ->
            if (!line.contains("repeat(")) return@scan null
            if (!Regex("""(state\.(energy|drive|mood)|state\.frame\.\w+)""").containsMatchIn(line)) return@scan null
            if (line.contains("deltaSeconds")) return@scan null
            if (ALLOWED_BURSTS.any { line.contains(it) }) return@scan null
            "a count taken from the music with no frame time in it. Multiply a per second rate by deltaSeconds"
        }
        assertTrue(
            complaints.isEmpty(),
            "spawning that changes with the refresh rate:\n" + complaints.joinToString("\n"),
        )
    }

    @Test
    fun noPositionIsTheClockTimesAChangingSpeed() {
        // Position equals time times speed only while the speed never changes. When the speed follows
        // the music, every change moves the position by the change times all the time so far, and a
        // few minutes into a song the picture lurches on every flicker of loudness. Add up speed
        // times frame time instead.
        val clockTimesLive = Regex(
            """(?:musicTime|timeSeconds|uMusicTime|uTime)\s*\*\s*(?:[\d.]+f?\s*\*\s*)?\([^)]*\b(?:drive|energy|mood|uDrive|uEnergy|uMood|uBass|uLevel)\b""",
        )
        val check: (String) -> String? = { line ->
            if (clockTimesLive.containsMatchIn(line)) "a clock multiplied by a speed that changes. Add up speed times frame time" else null
        }
        val complaints = scan(root, check) + scan(shaders, check)
        assertTrue(complaints.isEmpty(), "positions that jump when the music changes:\n" + complaints.joinToString("\n"))
    }

    private fun scan(check: (String) -> String?): List<Complaint> = scan(root, check)

    private fun scan(folder: File, check: (String) -> String?): List<Complaint> {
        val files = folder.listFiles { file: File -> file.name.endsWith(".kt") }
        assertTrue(files != null && files.isNotEmpty(), "no drawings found at ${folder.absolutePath}")
        val complaints = ArrayList<Complaint>()
        for (file in files.sortedBy { it.name }) {
            file.readLines().forEachIndexed { index, line ->
                val trimmed = line.trimStart()
                // Comments explain the rules, so they are allowed to mention them.
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@forEachIndexed
                check(line)?.let { complaints += Complaint(file.name, index + 1, line, it) }
            }
        }
        return complaints
    }

    private companion object {
        /** Below this a comparison is a guard against nothing, not a judgement about loudness. */
        const val NEGLIGIBLE = 0.02f

        /**
         * Bursts allowed to be counted per frame rather than per second.
         *
         * These fire on a drum hit, and a drum hit is one event however often the screen redraws,
         * so tying the size of the burst to the frame time would make a kick weaker on a fast
         * display. The steady trickle beside them is still per second.
         */
        val ALLOWED_BURSTS = listOf("burst + steady")
    }
}
