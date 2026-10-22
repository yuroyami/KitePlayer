package io.github.yuroyami.kiteplayer.compose

import io.github.yuroyami.kiteplayer.SphericalView
import io.github.yuroyami.kiteplayer.VideoProjection

/**
 * The 360 degree picture a [KiteVideo] draws a view into (#460): the picture's projection and the
 * viewer's view. The renderer hands one to the draw only when it draws the view, so none means the
 * flat picture.
 */
internal data class SphereView(val projection: VideoProjection, val view: SphericalView)
