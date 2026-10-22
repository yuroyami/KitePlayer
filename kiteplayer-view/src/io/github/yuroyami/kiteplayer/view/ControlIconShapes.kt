package io.github.yuroyami.kiteplayer.view

/** One step of an icon's outline, on a 24 unit grid with the origin at the top left. */
internal sealed interface IconStep {
    class Move(val x: Float, val y: Float) : IconStep
    class Line(val x: Float, val y: Float) : IconStep
    data object Close : IconStep

    /**
     * A new piece of outline along a circle. Angles are in degrees from the positive x axis and
     * grow clockwise on screen. A [sweep] of 360 is the whole circle.
     */
    class Arc(val cx: Float, val cy: Float, val radius: Float, val start: Float, val sweep: Float) : IconStep
}

/** One outline of an icon: filled, or stroked 2 units wide with round ends. */
internal class IconPath(val stroked: Boolean, val evenOdd: Boolean, val steps: List<IconStep>)

/**
 * The icons of the default controls of the native views, as outlines each toolkit turns into its
 * own path. They are the shapes of `ControlIcons` in `kiteplayer-compose-ui`, so the controls look
 * the same everywhere and no module needs an icon library.
 */
internal object ControlIconShapes {
    val Play = icon { filled { polygon(8f, 5f, 19f, 12f, 8f, 19f) } }

    val Pause = icon {
        filled {
            rect(6f, 5f, 4f, 14f)
            rect(14f, 5f, 4f, 14f)
        }
    }

    val Previous = icon {
        filled {
            rect(6f, 6f, 2f, 12f)
            polygon(18f, 6f, 9.5f, 12f, 18f, 18f)
        }
    }

    val Next = icon {
        filled {
            polygon(6f, 6f, 14.5f, 12f, 6f, 18f)
            rect(16f, 6f, 2f, 12f)
        }
    }

    val Volume = icon {
        filled { speaker() }
        stroked {
            // The two waves: circles through (15, 9)..(15, 15) and (17, 5.5)..(17, 18.5).
            arc(13.197f, 12f, 3.5f, -59f, 118f)
            arc(14.402f, 12f, 7f, -68.2f, 136.4f)
        }
    }

    val Muted = icon {
        filled { speaker() }
        stroked {
            line(15.5f, 9f, 21.5f, 15f)
            line(21.5f, 9f, 15.5f, 15f)
        }
    }

    /** A note of music, for the audio tracks. */
    val Audio = icon {
        filled {
            rect(13f, 4f, 2f, 12f)
            polygon(15f, 4f, 20f, 6f, 20f, 9f, 15f, 7f)
            arc(11f, 16f, 3.5f, 0f, 360f)
        }
    }

    /** A screen with two lines of text. */
    val Subtitles = icon {
        filled(evenOdd = true) {
            rect(2f, 5f, 20f, 14f)
            rect(4f, 7f, 16f, 10f)
        }
        filled {
            rect(6f, 13f, 5f, 2f)
            rect(12f, 13f, 6f, 2f)
            rect(6f, 9.5f, 8f, 2f)
        }
    }

    /** Three sliders, for the qualities. */
    val Quality = icon {
        stroked {
            line(4f, 7f, 20f, 7f)
            line(4f, 12f, 20f, 12f)
            line(4f, 17f, 20f, 17f)
        }
        filled {
            rect(14f, 4.5f, 3f, 5f)
            rect(7f, 9.5f, 3f, 5f)
            rect(11f, 14.5f, 3f, 5f)
        }
    }

    /** A dial with its needle, for the speeds. */
    val Speed = icon {
        stroked {
            arc(12f, 17f, 8f, 180f, 180f)
            line(12f, 15f, 16f, 9f)
        }
        filled { arc(12f, 15f, 2f, 0f, 360f) }
    }

    val FullScreen = icon {
        stroked {
            corner(4f, 9f, 4f, 4f, 9f, 4f)
            corner(15f, 4f, 20f, 4f, 20f, 9f)
            corner(20f, 15f, 20f, 20f, 15f, 20f)
            corner(9f, 20f, 4f, 20f, 4f, 15f)
        }
    }

    val PictureInPicture = icon {
        filled(evenOdd = true) {
            rect(2f, 4f, 20f, 16f)
            rect(4f, 6f, 16f, 12f)
        }
        filled { rect(11f, 11f, 7f, 5f) }
    }

    /** The side of the grid every icon is drawn on. */
    const val GRID = 24f

    /** The width of a stroked outline, in grid units. */
    const val STROKE = 2f
}

internal class IconBuilder {
    val paths = mutableListOf<IconPath>()

    fun filled(evenOdd: Boolean = false, shape: OutlineBuilder.() -> Unit) {
        paths += IconPath(stroked = false, evenOdd = evenOdd, steps = OutlineBuilder().apply(shape).steps)
    }

    fun stroked(shape: OutlineBuilder.() -> Unit) {
        paths += IconPath(stroked = true, evenOdd = false, steps = OutlineBuilder().apply(shape).steps)
    }
}

internal class OutlineBuilder {
    val steps = mutableListOf<IconStep>()

    fun polygon(vararg points: Float) {
        steps += IconStep.Move(points[0], points[1])
        for (i in 2 until points.size step 2) steps += IconStep.Line(points[i], points[i + 1])
        steps += IconStep.Close
    }

    fun rect(x: Float, y: Float, width: Float, height: Float) = polygon(x, y, x + width, y, x + width, y + height, x, y + height)

    fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
        steps += IconStep.Move(x1, y1)
        steps += IconStep.Line(x2, y2)
    }

    /** Two lines that meet at the middle point. */
    fun corner(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        steps += IconStep.Move(x1, y1)
        steps += IconStep.Line(x2, y2)
        steps += IconStep.Line(x3, y3)
    }

    fun arc(cx: Float, cy: Float, radius: Float, start: Float, sweep: Float) {
        steps += IconStep.Arc(cx, cy, radius, start, sweep)
    }

    fun speaker() = polygon(3f, 9f, 7f, 9f, 12f, 4.5f, 12f, 19.5f, 7f, 15f, 3f, 15f)
}

private fun icon(build: IconBuilder.() -> Unit): List<IconPath> = IconBuilder().apply(build).paths
