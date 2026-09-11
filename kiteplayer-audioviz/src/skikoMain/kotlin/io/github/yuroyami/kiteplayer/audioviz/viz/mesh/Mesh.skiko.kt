package io.github.yuroyami.kiteplayer.audioviz.viz.mesh

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import io.github.yuroyami.kiteplayer.audioviz.AudioVizAuthoringApi
import org.jetbrains.skia.Paint
import org.jetbrains.skia.VertexMode
import org.jetbrains.skia.BlendMode as SkiaBlendMode

/** Desktop and iOS, straight through Skia's own triangle call. */
@AudioVizAuthoringApi
public actual fun DrawScope.drawMesh(mesh: TriangleMesh, blendMode: BlendMode) {
    if (mesh.indexCount < 3) return
    // Two paints that are never changed, so drawings on different threads can share them.
    val paint = if (blendMode == BlendMode.Plus) addingPaint else coveringPaint
    drawIntoCanvas { canvas ->
        canvas.skiaCanvas.drawVertices(
            VertexMode.TRIANGLES,
            mesh.positionsExact(),
            mesh.colorsExact(),
            null,
            mesh.indicesExact(),
            // How corner colours combine with the paint's own colour. The paint is plain white,
            // so multiplying leaves the corner colours exactly as they were given.
            SkiaBlendMode.MODULATE,
            paint,
        )
    }
}

/** Reused paints. Making a new one each frame would leave a native object for the collector. */
private val coveringPaint: Paint by lazy {
    Paint().apply {
        isAntiAlias = true
        color = -1
        blendMode = SkiaBlendMode.SRC_OVER
    }
}

private val addingPaint: Paint by lazy {
    Paint().apply {
        isAntiAlias = true
        color = -1
        blendMode = SkiaBlendMode.PLUS
    }
}
