package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A 360 degree video's projection reaches the track list and the renderer, and the viewer's view
 * into it is the player's own setting (#460). Nothing here draws: the renderer is scripted, and the
 * drawings are proven in the renderers' own suites.
 */
class SphericalViewTest {

    private val sphere = VideoProjection(ProjectionKind.Equirectangular, yaw = 30.0, pitch = -10.0, roll = 5.0)

    private fun projecting() = RecordingRenderer().apply { projectedKinds = setOf(ProjectionKind.Equirectangular) }

    private fun CoreHarness.projectionWarnings() =
        core.warningHistory().map { it.warning }.filterIsInstance<PlaybackWarning.ProjectionUnavailable>()

    @Test
    fun `the video track lists the projection its stream declares`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(videoProjection = sphere))
        harness.openWithRenderer()
        assertEquals(sphere, harness.core.snapshots.value.tracks.selectedTrack(TrackKind.Video)?.projection)
        harness.close()

        val flat = CoreHarness(this)
        flat.openWithRenderer()
        assertEquals(null, flat.core.snapshots.value.tracks.selectedTrack(TrackKind.Video)?.projection)
        flat.close()
    }

    @Test
    fun `the view is published and a value out of range is refused`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(videoProjection = sphere), renderer = projecting())
        val player = KitePlayer(harness.core)
        harness.openWithRenderer()
        assertEquals(SphericalView(), player.state.value.sphericalView, "the view starts where the file starts it")

        val refused = listOf(
            SphericalView(yaw = Float.NaN),
            SphericalView(pitch = 90.5f),
            SphericalView(pitch = -91f),
            SphericalView(roll = Float.POSITIVE_INFINITY),
            SphericalView(fieldOfView = 29f),
            SphericalView(fieldOfView = 121f),
        )
        for (view in refused) assertFailsWith<IllegalArgumentException>("$view") { player.setSphericalView(view) }
        harness.run(100.milliseconds)
        assertEquals(SphericalView(), player.state.value.sphericalView, "a refused view changed the view")

        // A yaw past a whole turn is a direction like any other, and the ends of each range are in it.
        val turned = SphericalView(yaw = 725f, pitch = 90f, roll = -15f, fieldOfView = 30f)
        player.setSphericalView(turned)
        harness.run(100.milliseconds)
        assertEquals(turned, player.state.value.sphericalView)
        assertEquals(sphere to turned, assertNotNull(harness.renderer).projectionsTold.last(), "the renderer was told")
        harness.close()
    }

    @Test
    fun `the renderer is told on attach and at an open and the view on a change`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(videoProjection = sphere), renderer = null)
        val player = KitePlayer(harness.core)
        val view = SphericalView(yaw = 45f, fieldOfView = 100f)
        player.setSphericalView(view)

        // Nothing plays yet, so the renderer has no picture to project, and it has the view.
        val first = projecting()
        harness.core.attachRenderer(first)
        assertEquals(null to view, first.projectionsTold.last())

        player.open(MediaItem("scripted://sphere"))
        assertEquals(sphere to view, first.projectionsTold.last(), "the open told the picture's projection")

        val turned = view.copy(yaw = 90f, pitch = 20f)
        player.setSphericalView(turned)
        harness.run(100.milliseconds)
        assertEquals(sphere to turned, first.projectionsTold.last(), "the change told the new view")

        // A renderer attached while the item plays is told at once.
        val second = projecting()
        harness.core.attachRenderer(second)
        assertEquals(sphere to turned, second.projectionsTold.single())

        // A flat item that follows is told as flat, with the view it keeps.
        val flat = CoreHarness(this, renderer = null)
        val flatPlayer = KitePlayer(flat.core)
        val third = projecting()
        flat.core.attachRenderer(third)
        flatPlayer.setSphericalView(turned)
        flatPlayer.open(MediaItem("scripted://flat"))
        flat.run(100.milliseconds)
        assertEquals(null to turned, third.projectionsTold.last())
        harness.close()
        flat.close()
    }

    @Test
    fun `a picture that moves to a flat stream is told as flat`() = runTest {
        val harness = CoreHarness(
            this,
            script = MediaScript(durationUs = 12_000_000, videoEndUs = 4_000_000, movedVideoIndex = 5, videoProjection = sphere),
            renderer = projecting(),
        )
        harness.openWithRenderer()
        val renderer = assertNotNull(harness.renderer)
        assertEquals(sphere, renderer.projectionsTold.last().first)

        harness.core.play()
        harness.run(6.seconds)
        assertEquals(TrackId(5), harness.core.snapshots.value.tracks.selectedVideo, "the picture moved: ${harness.core.debugState}")
        assertEquals(null, renderer.projectionsTold.last().first, "the moved picture is flat")
        harness.close()
    }

    @Test
    fun `a renderer that draws only flat pictures is warned about once for the item`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 10_000_000, videoProjection = sphere))
        val player = KitePlayer(harness.core)
        harness.openWithRenderer()
        player.setSphericalView(SphericalView(yaw = 10f))
        player.play()
        harness.run(500.milliseconds)
        player.setSphericalView(SphericalView(yaw = 20f))
        harness.run(500.milliseconds)
        assertEquals(
            listOf(PlaybackWarning.ProjectionUnavailable(ProjectionKind.Equirectangular, "the renderer draws only flat pictures")),
            harness.projectionWarnings(),
        )

        // Another renderer that cannot project is another warning.
        harness.core.attachRenderer(RecordingRenderer())
        harness.run(100.milliseconds)
        assertEquals(2, harness.projectionWarnings().size)

        // So is the next item, on the same renderer.
        player.stop()
        player.open(MediaItem("scripted://again"))
        harness.run(100.milliseconds)
        assertEquals(3, harness.projectionWarnings().size)
        harness.close()
    }

    @Test
    fun `a renderer that projects the picture is not warned about and a flat picture never is`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(videoProjection = sphere), renderer = projecting())
        harness.openWithRenderer()
        harness.core.play()
        harness.run(500.milliseconds)
        assertEquals(emptyList(), harness.projectionWarnings())
        harness.close()

        val flat = CoreHarness(this)
        flat.openWithRenderer()
        flat.core.play()
        flat.run(500.milliseconds)
        assertEquals(emptyList(), flat.projectionWarnings())
        flat.close()
    }

    @Test
    fun `a renderer that projects a sphere and not a cube map names the cube map`() = runTest {
        val cube = VideoProjection(ProjectionKind.Cubemap(padding = 8))
        val harness = CoreHarness(this, script = MediaScript(videoProjection = cube), renderer = projecting())
        harness.openWithRenderer()
        val warning = harness.projectionWarnings().single()
        assertEquals(ProjectionKind.Cubemap(8), warning.kind)
        assertTrue("cube map" in warning.detail, warning.detail)
        assertEquals(cube, assertNotNull(harness.renderer).projectionsTold.last().first, "the renderer is told all the same")
        harness.close()
    }

    @Test
    fun `turning the view while it plays never seeks and drops no frame`() = runTest {
        val renderer = projecting()
        val harness = CoreHarness(
            this,
            script = MediaScript(durationUs = 20_000_000, videoProjection = sphere),
            config = PlayerConfig(statsInterval = 100.milliseconds),
            renderer = renderer,
        )
        val player = KitePlayer(harness.core)
        harness.openWithRenderer()
        player.play()
        harness.run(1.seconds)
        val before = harness.core.stats.value
        val seeksBefore = harness.source.seeks
        // Once round in three seconds, a step every 25 milliseconds, as a drag would turn it. In
        // virtual time, so a late frame here is the engine's doing and never a busy machine's.
        for (step in 0 until 120) {
            player.setSphericalView(SphericalView(yaw = step * 3f))
            harness.run(25.milliseconds)
            assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status, "the turn left Playing at step $step")
        }
        harness.run(200.milliseconds)
        // A view is drawn from the picture the renderer has, so turning it never moves the reader.
        assertEquals(seeksBefore, harness.source.seeks, "the turn sought")
        val after = harness.core.stats.value
        assertEquals(
            listOf(0L, 0L, 0L, 0L),
            listOf(
                after.droppedFramesLate - before.droppedFramesLate,
                after.droppedFramesDecode - before.droppedFramesDecode,
                after.refusedFrames - before.refusedFrames,
                after.rebuffers - before.rebuffers,
            ),
            "late, undecoded and refused frames and rebuffers while the view turned",
        )
        assertTrue(after.submittedFrames - before.submittedFrames > 60, "too few frames played to mean anything")
        assertEquals(SphericalView(yaw = 357f), renderer.projectionsTold.last().second, "the last view reached the renderer")
        harness.close()
    }

    @Test
    fun `a paused player redraws a turned view only where the renderer draws the view`() = runTest {
        // Neither renderer keeps a copy of its picture, as Android's do not while MediaCodec draws.
        suspend fun redrawsAfterTurn(renderer: RecordingRenderer): Boolean {
            renderer.redrawsHeldPictureOverride = false
            val harness = CoreHarness(this, script = MediaScript(durationUs = 20_000_000, videoProjection = sphere), renderer = renderer)
            val player = KitePlayer(harness.core)
            harness.openWithRenderer()
            player.play()
            harness.run(2.seconds)
            player.pause()
            harness.run(300.milliseconds)
            val before = renderer.count
            player.setSphericalView(SphericalView(yaw = 90f))
            harness.run(1.seconds)
            val redrew = renderer.count > before
            assertEquals(PlaybackStatus.Paused, harness.core.snapshots.value.status)
            harness.close()
            return redrew
        }
        assertTrue(redrawsAfterTurn(projecting()), "the turned view of a held picture was not drawn")
        assertEquals(false, redrawsAfterTurn(RecordingRenderer()), "a picture drawn flat was decoded again for nothing")
    }
}
