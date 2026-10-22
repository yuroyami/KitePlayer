package io.github.yuroyami.kiteplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The viewer's turn and mirrors (#428), folded into one mirror and one turn by
 * [VideoTransform.orient]. For every orientation a file can ask for and every one the viewer can
 * add, the folded pair moves the corners of the picture exactly where the steps, one after another,
 * move them: the file's mirror and turn, then the viewer's mirrors, then the viewer's turn.
 */
class VideoTransformOrientTest {

    private data class Point(val x: Int, val y: Int)

    private fun Point.mirroredLeftRight() = Point(-x, y)
    private fun Point.mirroredTopBottom() = Point(x, -y)

    /** A clockwise turn on screen, where y grows downward. */
    private fun Point.turned(degrees: Int): Point {
        var p = this
        repeat(((degrees % 360) + 360) % 360 / 90) { p = Point(-p.y, p.x) }
        return p
    }

    private val corners = listOf(Point(2, 1), Point(-2, 1), Point(2, -1), Point(-2, -1))

    @Test
    fun theFoldedPairMovesThePictureAsTheStepsDo() {
        for (fileTurn in listOf(0, 90, 180, 270)) for (fileMirror in listOf(false, true)) {
            for (turn in listOf(0, 90, 180, 270)) for (h in listOf(false, true)) for (v in listOf(false, true)) {
                val transform = VideoTransform(rotationDegrees = turn, mirrorHorizontal = h, mirrorVertical = v)
                val folded = transform.orient(fileTurn, fileMirror)
                for (corner in corners) {
                    var stepwise = if (fileMirror) corner.mirroredLeftRight() else corner
                    stepwise = stepwise.turned(fileTurn)
                    if (h) stepwise = stepwise.mirroredLeftRight()
                    if (v) stepwise = stepwise.mirroredTopBottom()
                    stepwise = stepwise.turned(turn)
                    val once = (if (folded.mirrored) corner.mirroredLeftRight() else corner).turned(folded.rotationDegrees)
                    assertEquals(stepwise, once, "file $fileTurn mirrored $fileMirror, viewer $transform folded to $folded")
                }
            }
        }
    }

    @Test
    fun theCasesTheIssueNames() {
        // A mirrored frame mirrored again by the viewer draws unmirrored.
        assertEquals(PictureOrientation(0, false), VideoTransform(mirrorHorizontal = true).orient(0, true))
        // A portrait clip with no tag stands up after one quarter turn, and swaps its sides.
        val stood = VideoTransform(rotationDegrees = 90).orient(0, false)
        assertEquals(PictureOrientation(90, false), stood)
        assertEquals(true, stood.isQuarterTurn)
        // The neutral value leaves the file's own, and a turn that is no quarter turn counts as none.
        assertEquals(PictureOrientation(270, true), VideoTransform.Identity.orient(-90, true))
        assertEquals(PictureOrientation(0, false), VideoTransform.Identity.orient(45, false))
        assertEquals(true, VideoTransform(mirrorVertical = true).turnsOrMirrors && !VideoTransform(mirrorVertical = true).isIdentity)
    }
}
