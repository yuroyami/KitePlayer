package io.github.yuroyami.kiteplayer.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The controls' icons, drawn here on a 24 unit grid in white and tinted where they are used, so the
 * module needs no icon library.
 */
internal object ControlIcons {
    val Play: ImageVector by lazy {
        icon("Play") {
            filled {
                moveTo(8f, 5f)
                lineTo(19f, 12f)
                lineTo(8f, 19f)
                close()
            }
        }
    }

    val Pause: ImageVector by lazy {
        icon("Pause") {
            filled {
                rect(6f, 5f, 4f, 14f)
                rect(14f, 5f, 4f, 14f)
            }
        }
    }

    val Previous: ImageVector by lazy {
        icon("Previous") {
            filled {
                rect(6f, 6f, 2f, 12f)
                moveTo(18f, 6f)
                lineTo(9.5f, 12f)
                lineTo(18f, 18f)
                close()
            }
        }
    }

    val Next: ImageVector by lazy {
        icon("Next") {
            filled {
                moveTo(6f, 6f)
                lineTo(14.5f, 12f)
                lineTo(6f, 18f)
                close()
                rect(16f, 6f, 2f, 12f)
            }
        }
    }

    val Volume: ImageVector by lazy {
        icon("Volume") {
            filled { speaker() }
            stroked {
                moveTo(15f, 9f)
                arcTo(3.5f, 3.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 15f, 15f)
                moveTo(17f, 5.5f)
                arcTo(7f, 7f, 0f, isMoreThanHalf = false, isPositiveArc = true, 17f, 18.5f)
            }
        }
    }

    val Muted: ImageVector by lazy {
        icon("Muted") {
            filled { speaker() }
            stroked {
                moveTo(15.5f, 9f)
                lineTo(21.5f, 15f)
                moveTo(21.5f, 9f)
                lineTo(15.5f, 15f)
            }
        }
    }

    /** A note of music, for the audio tracks. */
    val Audio: ImageVector by lazy {
        icon("Audio") {
            filled {
                rect(13f, 4f, 2f, 12f)
                moveTo(15f, 4f)
                lineTo(20f, 6f)
                lineTo(20f, 9f)
                lineTo(15f, 7f)
                close()
                moveTo(7.5f, 16f)
                arcTo(3.5f, 3.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 14.5f, 16f)
                arcTo(3.5f, 3.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 7.5f, 16f)
                close()
            }
        }
    }

    /** A screen with two lines of text. */
    val Subtitles: ImageVector by lazy {
        icon("Subtitles") {
            filled(PathFillType.EvenOdd) {
                rect(2f, 5f, 20f, 14f)
                rect(4f, 7f, 16f, 10f)
            }
            filled {
                rect(6f, 13f, 5f, 2f)
                rect(12f, 13f, 6f, 2f)
                rect(6f, 9.5f, 8f, 2f)
            }
        }
    }

    /** Three sliders, for the qualities. */
    val Quality: ImageVector by lazy {
        icon("Quality") {
            stroked {
                moveTo(4f, 7f)
                lineTo(20f, 7f)
                moveTo(4f, 12f)
                lineTo(20f, 12f)
                moveTo(4f, 17f)
                lineTo(20f, 17f)
            }
            filled {
                rect(14f, 4.5f, 3f, 5f)
                rect(7f, 9.5f, 3f, 5f)
                rect(11f, 14.5f, 3f, 5f)
            }
        }
    }

    /** A dial with its needle, for the speeds. */
    val Speed: ImageVector by lazy {
        icon("Speed") {
            stroked {
                moveTo(4f, 17f)
                arcTo(8f, 8f, 0f, isMoreThanHalf = true, isPositiveArc = true, 20f, 17f)
                moveTo(12f, 15f)
                lineTo(16f, 9f)
            }
            filled {
                moveTo(10f, 15f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 14f, 15f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 10f, 15f)
                close()
            }
        }
    }

    val FullScreen: ImageVector by lazy {
        icon("FullScreen") {
            stroked {
                moveTo(4f, 9f)
                lineTo(4f, 4f)
                lineTo(9f, 4f)
                moveTo(15f, 4f)
                lineTo(20f, 4f)
                lineTo(20f, 9f)
                moveTo(20f, 15f)
                lineTo(20f, 20f)
                lineTo(15f, 20f)
                moveTo(9f, 20f)
                lineTo(4f, 20f)
                lineTo(4f, 15f)
            }
        }
    }

    val PictureInPicture: ImageVector by lazy {
        icon("PictureInPicture") {
            filled(PathFillType.EvenOdd) {
                rect(2f, 4f, 20f, 16f)
                rect(4f, 6f, 16f, 12f)
            }
            filled { rect(11f, 11f, 7f, 5f) }
        }
    }
}

private fun icon(name: String, paths: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply(paths).build()

private fun ImageVector.Builder.filled(type: PathFillType = PathFillType.NonZero, shape: PathBuilder.() -> Unit) {
    path(fill = SolidColor(Color.White), pathFillType = type, pathBuilder = shape)
}

private fun ImageVector.Builder.stroked(shape: PathBuilder.() -> Unit) {
    path(
        stroke = SolidColor(Color.White),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = shape,
    )
}

private fun PathBuilder.rect(x: Float, y: Float, width: Float, height: Float) {
    moveTo(x, y)
    lineTo(x + width, y)
    lineTo(x + width, y + height)
    lineTo(x, y + height)
    close()
}

private fun PathBuilder.speaker() {
    moveTo(3f, 9f)
    lineTo(7f, 9f)
    lineTo(12f, 4.5f)
    lineTo(12f, 19.5f)
    lineTo(7f, 15f)
    lineTo(3f, 15f)
    close()
}
