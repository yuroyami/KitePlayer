package io.github.yuroyami.kiteplayer

/**
 * How a 360 degree video's pictures cover the sphere around the viewer, as its file states it
 * (#460): in Google's spherical video boxes or Apple's video extension box in MP4 or MOV, or in a
 * Matroska track's `Projection` element. [TrackInfo.projection] is this for a 360 degree video and
 * null for a flat one.
 *
 * The pose turns the sphere so the view starts where the file asks, and a viewer's
 * [SphericalView] turns the view from there.
 */
public data class VideoProjection(
    /** The shape the picture takes on the sphere. */
    val kind: ProjectionKind,
    /** The pose's yaw, in degrees: a positive yaw puts the picture's front to the right of where the view starts. */
    val yaw: Double = 0.0,
    /** The pose's pitch, in degrees: a positive pitch puts the picture's front above where the view starts. */
    val pitch: Double = 0.0,
    /** The pose's roll, in degrees: a positive roll tilts the picture clockwise. */
    val roll: Double = 0.0,
)

/** The shape a 360 degree video's picture takes on the sphere (#460). See [VideoProjection]. */
public sealed interface ProjectionKind {
    /**
     * The whole sphere: longitude runs across the width, from 180 degrees left of the front at the
     * left edge to 180 degrees right of it at the right edge, and latitude down the height, from
     * straight up at the top to straight down at the bottom.
     */
    public data object Equirectangular : ProjectionKind

    /**
     * A part of an [ProjectionKind.Equirectangular] picture. Each bound is the share of the whole
     * picture that lies beyond this part's edge on that side, from 0 to 1, so a tile of the middle
     * half of the width and the whole height has a [left] and a [right] of 0.25 and a [top] and a
     * [bottom] of 0.
     */
    public data class EquirectangularTile(
        val left: Double,
        val top: Double,
        val right: Double,
        val bottom: Double,
    ) : ProjectionKind

    /** Six cube faces in one picture, with [padding] pixels at each face's edges. */
    public data class Cubemap(val padding: Int) : ProjectionKind

    /**
     * A projection with no shape of its own here, by its [name] as FFmpeg gives it: "half
     * equirectangular", "fisheye" or "parametric immersive", Apple's.
     */
    public data class Other(val name: String) : ProjectionKind
}

/**
 * Where the viewer looks into a 360 degree video, and how wide the view is (#460). Set with
 * [KitePlayer.setSphericalView] and published as [PlayerSnapshot.sphericalView]. A flat video
 * ignores it.
 */
public data class SphericalView(
    /** Degrees to the right of where the file starts the view. Any value: a whole turn is 360. */
    val yaw: Float = 0f,
    /** Degrees up, from -90, straight down, to 90, straight up. */
    val pitch: Float = 0f,
    /** Degrees the view is turned clockwise, as a phone held at an angle turns it. */
    val roll: Float = 0f,
    /** The angle the view spans across its shorter side, from 30 to 120 degrees. */
    val fieldOfView: Float = 80f,
    /** False draws the whole picture flat, as for a video that is not 360. */
    val projected: Boolean = true,
)
