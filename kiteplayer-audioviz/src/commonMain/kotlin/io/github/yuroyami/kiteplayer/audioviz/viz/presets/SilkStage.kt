package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.yuroyami.kiteplayer.audioviz.viz.Scene3D
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Fluctus's stage, kept for Silk: a 50 degree lens 20 degrees above a floor at y = -50, with a
 * horizon line and a shadow projection from a light at y = 1000.
 */
internal class SilkStage {
    val scene = Scene3D()

    /** The horizon's screen y after the last [look]. */
    var horizon = 0f
        private set

    /** Where the camera stands and where it looks, in world units, for the next [look]. */
    val eye = FloatArray(3)
    val aim = FloatArray(3)

    /**
     * Fills [eye] and [aim] for the stage view, without setting the camera. The camera sits where the
     * 155 unit sheet spans 70 percent of the frame's width, divided by [zoom], turned by
     * [orbitRadians] round the y axis and raised by [elevationDegrees].
     */
    fun stageView(width: Float, height: Float, zoom: Float, orbitRadians: Float, elevationDegrees: Float) {
        val focal = height * 0.5f / tan(FOV_DEGREES * 0.5f * PI.toFloat() / 180f)
        val distance = focal * SHEET_SPAN / (SHEET_SHARE * width.coerceAtLeast(1f)) / zoom.coerceAtLeast(0.1f)
        val angle = elevationDegrees * PI.toFloat() / 180f
        // The orbit stops above the floor; looking up from beneath it would put the shadow into the sky.
        eye[0] = sin(orbitRadians) * cos(angle) * distance
        eye[1] = (AIM_Y + sin(angle) * distance).coerceAtLeast(-35f)
        eye[2] = cos(orbitRadians) * cos(angle) * distance
        aim[0] = 0f
        aim[1] = AIM_Y
        aim[2] = 0f
    }

    /** Sets the lens and the camera from [eye] and [aim], then the horizon. */
    fun look(width: Float, height: Float, roll: Float = 0f) {
        scene.lens(Size(width, height), fovDegrees = FOV_DEGREES, near = 1f, far = 10_000f)
        scene.camera(eye[0], eye[1], eye[2], aim[0], aim[1], aim[2], roll)
        if (scene.project(0f, -50f, -4_000f)) horizon = scene.screenY
    }

    /** Projects a world point; false when it is outside the lens. */
    fun project(x: Float, y: Float, z: Float): Boolean = scene.project(x, y, z)

    /** Projects the floor shadow of a world point under the light at y = 1000. */
    fun shadow(x: Float, y: Float, z: Float): Boolean {
        val scale = 1_050f / (1_000f - y)
        return scene.project(x * scale, -50f, z * scale)
    }

    val screenX: Float get() = scene.screenX
    val screenY: Float get() = scene.screenY

    /** Fluctus's sky: a gradient from [paper] at the top to a pale blue at the horizon. */
    fun DrawScope.drawHorizon(light: Float, paper: Color) {
        val line = horizon.coerceIn(0f, size.height)
        if (line <= 0f) return
        drawRect(
            Brush.verticalGradient(0f to paper,
                0.80f to Color(0.953f * light, 0.946f * light, 0.994f * light),
                0.985f to Color(0.890f * light, 0.896f * light, 0.980f * light),
                1f to Color(0.710f * light, 0.748f * light, 0.936f * light), endY = line),
            size = Size(size.width, line),
        )
    }

    companion object {
        const val FOV_DEGREES = 50f
        const val SHEET_SHARE = 0.7f
        const val SHEET_SPAN = 155f
        const val AIM_Y = -8f
        const val ELEVATION_DEGREES = 20f

        /** Fluctus's floor, the paper the sky starts from. */
        fun paper(light: Float): Color = Color(0.977f * light, 0.969f * light, 0.994f * light)
    }
}
