package io.github.yuroyami.kiteplayer.audioviz.viz.shader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * The note-name ruler across the middle of Musical Spectrum, laid out as ShowCQTBar's
 * `axis-1920x48.png`, which the page stretched over the full width at the axis height.
 *
 * In the image's own pixels: rows 8 and 9 and rows 38 and 39 are white lines, each with a round dot
 * of radius 3 at the middle of every semitone (every 16 pixels, the first at x = 8); rows 10 to 37
 * are black at 30 percent; the letter of every natural note is centred on its semitone in rows 16 to
 * 28, and C, D, F, G and A of octaves 0 to 9 carry a small octave digit in rows 23 to 32 over the
 * semitone to their right. E and B have no room for one, and neither do C10 and D10 at the edge.
 * Rows 0 to 5 and 42 to 47 are clear, so the colour line shows through them.
 *
 * The page's letters are a bold typeface; these are strokes of the same size and weight.
 */
internal class NoteAxis {
    private val letters = Path()
    private val digits = Path()
    private val dots = Path()
    private var builtFor = Size.Zero
    private var builtTop = Float.NaN
    private var scaleY = 1f

    /** Draws the ruler over the band from [top], [height] pixels tall, across the whole width. */
    fun DrawScope.drawAxis(top: Float, height: Float) {
        if (size.width <= 0f || height <= 0f) return
        if (size != builtFor || top != builtTop || height / IMAGE_ROWS != scaleY) build(size.width, top, height)
        val y = scaleY
        drawRect(BAND, Offset(0f, top + 10f * y), Size(size.width, 28f * y))
        drawRect(Color.White, Offset(0f, top + 8f * y), Size(size.width, 2f * y))
        drawRect(Color.White, Offset(0f, top + 38f * y), Size(size.width, 2f * y))
        drawPath(dots, Color.White)
        drawPath(letters, Color.White, style = Stroke(LETTER_STROKE * y, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(digits, Color.White, style = Stroke(DIGIT_STROKE * y, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    private fun build(width: Float, top: Float, height: Float) {
        builtFor = Size(width, top + height)
        builtTop = top
        scaleY = height / IMAGE_ROWS
        val semitone = width / SEMITONES
        // The image's x runs over 1920 pixels, 16 to a semitone.
        val scaleX = semitone / 16f
        letters.reset()
        digits.reset()
        dots.reset()
        for (index in 0 until SEMITONES) {
            val start = index * semitone
            val middle = start + 8f * scaleX
            val radius = 3f * scaleY
            dots.addOval(Rect(Offset(middle, top + 9f * scaleY), radius))
            dots.addOval(Rect(Offset(middle, top + 39f * scaleY), radius))
            // Semitone 0 is E0, so pitch class 4, where C is 0.
            val pitch = (index + 4) % 12
            val octave = (index + 4) / 12
            val letter = LETTERS[pitch] ?: continue
            glyph(letters, letter, start + 2f * scaleX, top + 16f * scaleY, 12f * scaleX, 13f * scaleY, LETTER_STROKE * scaleY)
            if (letter != 'E' && letter != 'B' && octave <= 9) {
                glyph(digits, '0' + octave, start + 17f * scaleX, top + 23f * scaleY, 8f * scaleX, 10f * scaleY, DIGIT_STROKE * scaleY)
            }
        }
    }

    /** Adds [char] as strokes inside the box at [left], [top], inset by half a stroke. */
    private fun glyph(into: Path, char: Char, left: Float, top: Float, width: Float, height: Float, stroke: Float) {
        val inset = stroke * 0.5f
        val x0 = left + inset
        val y0 = top + inset
        val w = (width - stroke).coerceAtLeast(0.5f)
        val h = (height - stroke).coerceAtLeast(0.5f)
        fun x(u: Float) = x0 + u * w
        fun y(v: Float) = y0 + v * h
        fun move(u: Float, v: Float) = into.moveTo(x(u), y(v))
        fun line(u: Float, v: Float) = into.lineTo(x(u), y(v))
        fun box(l: Float, t: Float, r: Float, b: Float) = Rect(x(l), y(t), x(r), y(b))
        fun arc(l: Float, t: Float, r: Float, b: Float, start: Float, sweep: Float, jump: Boolean = false) =
            into.arcTo(box(l, t, r, b), start, sweep, jump)
        when (char) {
            'C' -> arc(0f, 0f, 1f, 1f, -45f, -270f, true)
            'D' -> { move(0f, 0f); line(0f, 1f); line(0.45f, 1f); arc(-0.1f, 0f, 1f, 1f, 90f, -180f); line(0f, 0f) }
            'E' -> { move(1f, 0f); line(0f, 0f); line(0f, 1f); line(1f, 1f); move(0f, 0.5f); line(0.8f, 0.5f) }
            'F' -> { move(1f, 0f); line(0f, 0f); line(0f, 1f); move(0f, 0.5f); line(0.8f, 0.5f) }
            'G' -> { arc(0f, 0f, 1f, 1f, -40f, -320f, true); line(0.55f, 0.5f) }
            'A' -> { move(0f, 1f); line(0.5f, 0f); line(1f, 1f); move(0.2f, 0.64f); line(0.8f, 0.64f) }
            'B' -> {
                move(0f, 0.48f); line(0f, 0f); line(0.5f, 0f); arc(0.1f, 0f, 0.9f, 0.48f, -90f, 180f); line(0f, 0.48f)
                line(0.55f, 0.48f); arc(0.1f, 0.48f, 1f, 1f, -90f, 180f); line(0f, 1f); line(0f, 0.48f)
            }
            '0' -> into.addOval(box(0f, 0f, 1f, 1f))
            '1' -> { move(0.15f, 0.22f); line(0.55f, 0f); line(0.55f, 1f); move(0.15f, 1f); line(0.95f, 1f) }
            '2' -> { arc(0f, 0f, 1f, 0.56f, 180f, 180f, true); line(0f, 1f); line(1f, 1f) }
            '3' -> { arc(0.05f, 0f, 0.95f, 0.5f, 205f, 245f, true); arc(0f, 0.5f, 1f, 1f, -90f, 245f) }
            '4' -> { move(0.72f, 1f); line(0.72f, 0f); line(0f, 0.7f); line(1f, 0.7f) }
            '5' -> { move(0.92f, 0f); line(0.12f, 0f); line(0.05f, 0.45f); arc(0f, 0.36f, 1f, 1f, 235f, 260f) }
            '6' -> { move(0.85f, 0.02f); into.quadraticTo(x(0f), y(0.1f), x(0f), y(0.7f)); into.addOval(box(0f, 0.4f, 1f, 1f)) }
            '7' -> { move(0f, 0f); line(1f, 0f); line(0.35f, 1f) }
            '8' -> { into.addOval(box(0.08f, 0f, 0.92f, 0.46f)); into.addOval(box(0f, 0.46f, 1f, 1f)) }
            '9' -> { into.addOval(box(0f, 0f, 1f, 0.6f)); move(1f, 0.3f); into.quadraticTo(x(1f), y(0.9f), x(0.15f), y(0.98f)) }
        }
    }

    private companion object {
        /** Ten octaves of twelve semitones. */
        const val SEMITONES = 120

        /** The height of the page's axis image. */
        const val IMAGE_ROWS = 48f

        const val LETTER_STROKE = 2.6f
        const val DIGIT_STROKE = 1.7f

        /** Black at alpha 76 of 255, as in the image. */
        val BAND = Color(0f, 0f, 0f, 76f / 255f)

        /** The natural notes by pitch class, C at 0. */
        val LETTERS: Array<Char?> = arrayOf('C', null, 'D', null, 'E', 'F', null, 'G', null, 'A', null, 'B')
    }
}
