package io.github.yuroyami.kiteplayer.audioviz.viz

import androidx.compose.ui.geometry.Size
import io.github.yuroyami.kite3d.math.Camera
import io.github.yuroyami.kite3d.math.Vector3
import io.github.yuroyami.kiteplayer.audioviz.AudioVizAuthoringApi
import kotlin.math.cos
import kotlin.math.sin

/**
 * Turns points in space into points on the canvas.
 *
 * This is the whole of the depth effect. A perspective projection divides a point's position by
 * how far away it is, so distant things land closer to the middle of the screen and closer things
 * spread towards the edges. Move the camera forward every frame and the geometry appears to rush
 * past. That is all a flight down a tunnel ever was.
 *
 * The maths comes from Kite3D's [Camera], which builds the view and projection matrices and
 * projects without allocating, so this can run for every vertex of every frame.
 *
 * Points behind the camera cannot be projected meaningfully: dividing by a negative depth turns
 * the picture inside out. [project] answers false for those, and for anything past the far plane.
 * Drawings here also wrap their geometry before it reaches the eye, so the case is rare.
 */
@AudioVizAuthoringApi
public class Scene3D {

    private val camera = Camera()
    private val eye = Vector3()
    private val focus = Vector3()
    private val upVector = Vector3(0.0, 1.0, 0.0)
    private val projected = FloatArray(3)

    private var halfWidth = 0f
    private var halfHeight = 0f
    private var nearPlane = 0.5f
    private var farPlane = 60f

    /** Where the last [project] landed, in canvas pixels. */
    public var screenX: Float = 0f
        private set

    public var screenY: Float = 0f
        private set

    /** How far away the last point was, 0 at the near plane and 1 at the far one. */
    public var depth: Float = 0f
        private set

    /**
     * Sets the lens for this frame. Call once, before any [project].
     *
     * A wide [fovDegrees] exaggerates the rush past the camera, which is what makes a tunnel feel
     * fast. Narrow it and the same geometry feels like a slow drift.
     */
    public fun lens(size: Size, fovDegrees: Float = 68f, near: Float = 0.6f, far: Float = 55f) {
        halfWidth = size.width / 2f
        halfHeight = size.height / 2f
        nearPlane = near
        farPlane = far
        camera.setPerspectiveFov(
            fovDegrees.toDouble(),
            (size.width / size.height.coerceAtLeast(1f)).toDouble(),
            near.toDouble(),
            far.toDouble(),
        )
    }

    /**
     * Points the camera. [roll] banks it around its own forward axis, in radians, which is the
     * cheapest way to make a flight feel like flying rather than like sliding.
     */
    public fun camera(
        eyeX: Float,
        eyeY: Float,
        eyeZ: Float,
        targetX: Float = eyeX,
        targetY: Float = eyeY,
        targetZ: Float = eyeZ - 1f,
        roll: Float = 0f,
    ) {
        eye.set(eyeX.toDouble(), eyeY.toDouble(), eyeZ.toDouble())
        focus.set(targetX.toDouble(), targetY.toDouble(), targetZ.toDouble())
        upVector.set(sin(roll).toDouble(), cos(roll).toDouble(), 0.0)
        camera.lookAt(eye, focus, upVector).updateMatrices()
    }

    /**
     * Projects one point. Returns false when it is behind the camera or beyond the far plane, in
     * which case [screenX], [screenY] and [depth] are left alone and nothing should be drawn.
     */
    public fun project(x: Float, y: Float, z: Float): Boolean {
        camera.project(x, y, z, projected)
        val ndcZ = projected[2]
        if (ndcZ < -1f || ndcZ > 1f) return false
        screenX = halfWidth + projected[0] * halfWidth
        // Screen y grows downward and the projection's y grows upward, so this flips.
        screenY = halfHeight - projected[1] * halfHeight
        depth = ((ndcZ + 1f) * 0.5f).coerceIn(0f, 1f)
        return true
    }

    /**
     * How solid something at [distance] world units away should look, 1 up close and 0 at the
     * far plane. Fog is what stops a tunnel looking like a flat spiral drawn on glass.
     */
    public fun fog(distance: Float): Float {
        val along = ((distance - nearPlane) / (farPlane - nearPlane)).coerceIn(0f, 1f)
        // Squared, so the near half of the shaft stays bright and the far end drops away quickly.
        val remaining = 1f - along
        return remaining * remaining
    }
}
