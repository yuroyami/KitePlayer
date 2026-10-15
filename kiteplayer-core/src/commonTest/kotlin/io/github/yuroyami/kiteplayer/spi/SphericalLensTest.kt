package io.github.yuroyami.kiteplayer.spi

import io.github.yuroyami.kiteplayer.KitePlayerInternalApi
import io.github.yuroyami.kiteplayer.ProjectionKind
import io.github.yuroyami.kiteplayer.SphericalView
import io.github.yuroyami.kiteplayer.VideoProjection
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The law of a view into a 360 degree picture (#460), read at the centre of a view and at its
 * edges, where each turn's direction can be worked out by hand.
 */
@OptIn(KitePlayerInternalApi::class)
class SphericalLensTest {

    private val whole = VideoProjection(ProjectionKind.Equirectangular)

    /** Where the centre of a 200 by 100 view reads the picture. */
    private fun centre(view: SphericalView, projection: VideoProjection = whole): PicturePoint? =
        SphericalLens(projection, view, 200f, 100f).pictureAt(100.0, 50.0)

    private fun assertAt(across: Double, down: Double, point: PicturePoint?, what: String) {
        assertNotNull(point, "$what reads nothing")
        // Across the seam, 0 and 1 are the same longitude.
        val acrossOff = abs(point.across - across).let { minOf(it, 1.0 - it) }
        assertTrue(acrossOff < 1e-9 && abs(point.down - down) < 1e-9, "$what reads $point, not ($across, $down)")
    }

    @Test
    fun theDefaultViewLooksAtThePicturesCentre() {
        assertAt(0.5, 0.5, centre(SphericalView()), "the default view")
    }

    @Test
    fun yawTurnsRightAndPitchTurnsUp() {
        assertAt(0.75, 0.5, centre(SphericalView(yaw = 90f)), "a quarter turn right")
        assertAt(0.25, 0.5, centre(SphericalView(yaw = -90f)), "a quarter turn left")
        assertAt(0.0, 0.5, centre(SphericalView(yaw = 180f)), "half a turn")
        assertAt(0.75, 0.5, centre(SphericalView(yaw = 450f)), "a turn and a quarter")
        assertAt(0.5, 0.25, centre(SphericalView(pitch = 45f)), "45 degrees up")
        assertAt(0.5, 1.0 / 3.0, centre(SphericalView(yaw = 0f, pitch = 30f)), "30 degrees up")
        assertEquals(0.0, centre(SphericalView(pitch = 90f))!!.down, 1e-9, "straight up")
        assertEquals(1.0, centre(SphericalView(pitch = -90f))!!.down, 1e-9, "straight down")
        // Up first and then right is up toward the right: the yaw turns about the world's up.
        assertAt(0.625, 0.25, centre(SphericalView(yaw = 45f, pitch = 45f)), "45 degrees right and up")
    }

    @Test
    fun aClockwiseRollPutsTheGroundOnTheRight() {
        val lens = SphericalLens(whole, SphericalView(roll = 90f), 200f, 100f)
        // The right edge looks down and the left edge up, as through a phone turned clockwise.
        assertTrue(lens.pictureAt(199.5, 50.0)!!.down > 0.6, "the right edge of a clockwise roll")
        assertTrue(lens.pictureAt(0.5, 50.0)!!.down < 0.4, "the left edge of a clockwise roll")
        assertEquals(0.5, lens.pictureAt(100.0, 50.0)!!.down, 1e-9, "its centre stays where it was")
    }

    @Test
    fun theFieldOfViewSpansTheShorterSide() {
        // 90 degrees across a 100 pixel high view: its top and bottom edges look 45 degrees up and down.
        val wide = SphericalLens(whole, SphericalView(fieldOfView = 90f), 200f, 100f)
        assertEquals(0.25, wide.pictureAt(100.0, 0.0)!!.down, 1e-9)
        assertEquals(0.75, wide.pictureAt(100.0, 100.0)!!.down, 1e-9)
        // Held upright, the width is the shorter side.
        val tall = SphericalLens(whole, SphericalView(fieldOfView = 90f), 100f, 200f)
        assertEquals(0.625, tall.pictureAt(100.0, 100.0)!!.across, 1e-9)
        assertEquals(0.375, tall.pictureAt(0.0, 100.0)!!.across, 1e-9)
        // The pixel half way to the edge sees half the tangent, not half the angle.
        val half = atan(0.5) / (2 * PI)
        assertEquals(0.5 + half, tall.pictureAt(75.0, 100.0)!!.across, 1e-9)
    }

    @Test
    fun theFilesPoseStartsTheView() {
        val posed = VideoProjection(ProjectionKind.Equirectangular, yaw = 30.0)
        // The picture's front is 30 degrees to the right of where the view starts.
        assertAt(0.5 - 30.0 / 360.0, 0.5, centre(SphericalView(), posed), "the view the file starts")
        assertAt(0.5, 0.5, centre(SphericalView(yaw = 30f), posed), "30 degrees to the right")
        val raised = VideoProjection(ProjectionKind.Equirectangular, pitch = 30.0)
        assertAt(0.5, 0.5, centre(SphericalView(pitch = 30f), raised), "30 degrees up into a picture raised 30")
        // A view turned as the pose turns the picture sees the picture as it was drawn, every pixel.
        val turned = VideoProjection(ProjectionKind.Equirectangular, yaw = 40.0, pitch = -20.0, roll = 15.0)
        val matched = SphericalLens(turned, SphericalView(yaw = 40f, pitch = -20f, roll = 15f), 200f, 100f)
        val plain = SphericalLens(whole, SphericalView(), 200f, 100f)
        for ((x, y) in listOf(0.5 to 0.5, 199.5 to 99.5, 37.25 to 81.5, 150.0 to 12.0)) {
            val expected = plain.pictureAt(x, y)!!
            assertAt(expected.across, expected.down, matched.pictureAt(x, y), "($x, $y) of the matched view")
        }
    }

    @Test
    fun aTileReadsItsPartAndNothingBeyond() {
        val tile = VideoProjection(ProjectionKind.EquirectangularTile(left = 0.25, top = 0.0, right = 0.25, bottom = 0.0))
        assertAt(0.5, 0.5, centre(SphericalView(), tile), "the tile's centre")
        // 45 degrees right of the front is three quarters of the way across the middle half.
        assertAt(0.75, 0.5, centre(SphericalView(yaw = 45f), tile), "45 degrees right")
        assertNull(centre(SphericalView(yaw = 135f), tile), "behind the tile")
        val lens = SphericalLens(tile, SphericalView(), 200f, 100f)
        assertEquals(false, lens.wraps)
        assertEquals(listOf(0.25, 0.0, 0.5, 1.0), listOf(lens.pictureLeft, lens.pictureTop, lens.pictureWidth, lens.pictureHeight))
    }

    @Test
    fun onlyAnEquirectangularPictureOrATileOfOneIsDrawnAsAView() {
        assertTrue(SphericalLens.draws(ProjectionKind.Equirectangular))
        assertTrue(SphericalLens.draws(ProjectionKind.EquirectangularTile(0.1, 0.1, 0.1, 0.1)))
        assertEquals(false, SphericalLens.draws(ProjectionKind.Cubemap(0)))
        assertEquals(false, SphericalLens.draws(ProjectionKind.Other("fisheye")))
        assertFailsWith<IllegalArgumentException> {
            SphericalLens(VideoProjection(ProjectionKind.Cubemap(0)), SphericalView(), 200f, 100f)
        }
        assertFailsWith<IllegalArgumentException> { SphericalLens(whole, SphericalView(), 0f, 100f) }
    }
}
