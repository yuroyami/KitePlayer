package io.github.yuroyami.kiteplayer.audioviz.viz.mesh

import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Build
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import io.github.yuroyami.kiteplayer.audioviz.AudioVizAuthoringApi

/**
 * Android's triangle call, with a way round the versions that cannot use it.
 *
 * Before Android 10 a hardware accelerated canvas silently ignored this call, which is every canvas
 * Compose draws on. Those versions get the triangles one at a time as filled paths, each in the
 * colour of its first corner. Slower and flatter, but visibly the same shape.
 */
@AudioVizAuthoringApi
public actual fun DrawScope.drawMesh(mesh: TriangleMesh, blendMode: BlendMode) {
    if (mesh.indexCount < 3) return
    val adding = blendMode == BlendMode.Plus
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        if (native.isHardwareAccelerated && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            drawOneByOne(native, mesh, adding)
            return@drawIntoCanvas
        }
        val paint = meshPaint
        paint.xfermode = if (adding) addLight else null
        native.drawVertices(
            AndroidCanvas.VertexMode.TRIANGLES,
            mesh.vertexCount * 2,
            mesh.positions,
            0,
            null,
            0,
            mesh.colors,
            0,
            mesh.indices,
            0,
            mesh.indexCount,
            paint,
        )
    }
}

private fun drawOneByOne(canvas: AndroidCanvas, mesh: TriangleMesh, adding: Boolean) {
    val path = scratchPath
    val paint = fillPaint
    paint.xfermode = if (adding) addLight else null
    var at = 0
    while (at + 2 < mesh.indexCount) {
        val a = mesh.indices[at].toInt()
        val b = mesh.indices[at + 1].toInt()
        val c = mesh.indices[at + 2].toInt()
        path.reset()
        path.moveTo(mesh.positions[a * 2], mesh.positions[a * 2 + 1])
        path.lineTo(mesh.positions[b * 2], mesh.positions[b * 2 + 1])
        path.lineTo(mesh.positions[c * 2], mesh.positions[c * 2 + 1])
        path.close()
        paint.color = mesh.colors[a]
        canvas.drawPath(path, paint)
        at += 3
    }
}

private val meshPaint: Paint by lazy { Paint(Paint.ANTI_ALIAS_FLAG) }
private val fillPaint: Paint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL } }
private val scratchPath: Path by lazy { Path() }
private val addLight: PorterDuffXfermode by lazy { PorterDuffXfermode(PorterDuff.Mode.ADD) }
