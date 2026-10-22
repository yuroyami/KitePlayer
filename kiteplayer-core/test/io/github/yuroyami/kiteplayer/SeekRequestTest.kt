package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.SeekRequest
import io.github.yuroyami.kiteplayer.internal.SeekTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class SeekRequestTest {

    private fun absolute(seconds: Long, mode: SeekMode = SeekMode.Precise) =
        SeekRequest(SeekTarget.Absolute(pts(seconds * 1_000)), mode)

    private fun relative(seconds: Long, mode: SeekMode = SeekMode.Precise) =
        SeekRequest(SeekTarget.Relative(seconds.seconds), mode)

    @Test
    fun `relative offsets accumulate`() {
        // Holding the forward key must move by the total, not by the last press.
        val merged = relative(10).merge(relative(10)).merge(relative(10))
        assertEquals(SeekTarget.Relative(30.seconds), merged.target)
    }

    @Test
    fun `negative and positive relative offsets cancel`() {
        val merged = relative(30).merge(relative(-10))
        assertEquals(SeekTarget.Relative(20.seconds), merged.target)
    }

    @Test
    fun `an absolute target absorbs pending relative offsets`() {
        // Dragging the seek bar supersedes whatever key presses came before it.
        val merged = relative(60).merge(absolute(5))
        assertEquals(SeekTarget.Absolute(pts(5_000)), merged.target)
    }

    @Test
    fun `a later relative offset after an absolute target replaces it`() {
        // There is nothing sensible to add a relative offset to yet, so the newest request wins.
        val merged = absolute(30).merge(relative(10))
        assertEquals(SeekTarget.Relative(10.seconds), merged.target)
    }

    @Test
    fun `the stricter mode survives a merge`() {
        // A precise request must never be silently downgraded by a later coarse one.
        assertEquals(
            SeekMode.Precise,
            absolute(1, SeekMode.Precise).merge(absolute(2, SeekMode.Keyframe)).mode,
        )
        assertEquals(
            SeekMode.Precise,
            absolute(1, SeekMode.Keyframe).merge(absolute(2, SeekMode.Precise)).mode,
        )
        assertEquals(
            SeekMode.KeyframeThenRefine,
            absolute(1, SeekMode.Keyframe).merge(absolute(2, SeekMode.KeyframeThenRefine)).mode,
        )
        assertEquals(
            SeekMode.Keyframe,
            absolute(1, SeekMode.Keyframe).merge(absolute(2, SeekMode.Keyframe)).mode,
        )
    }

    @Test
    fun `forty seek requests coalesce into one`() {
        // A seek bar drag produces one request per pointer move. All of them must fold into a single
        // seek, or the player spends the whole drag flushing.
        var request = absolute(0, SeekMode.KeyframeThenRefine)
        repeat(40) { i -> request = request.merge(absolute(i.toLong(), SeekMode.KeyframeThenRefine)) }
        assertEquals(SeekTarget.Absolute(pts(39_000)), request.target)
    }

    @Test
    fun `resolve turns a relative offset into a position`() {
        val resolved = relative(10).resolve(position = pts(30_000), duration = pts(120_000))
        assertEquals(pts(40_000), resolved)
    }

    @Test
    fun `resolve clamps to the start`() {
        val resolved = relative(-60).resolve(position = pts(10_000), duration = pts(120_000))
        assertEquals(Pts.Zero, resolved)
    }

    @Test
    fun `resolve clamps to the duration`() {
        val resolved = relative(600).resolve(position = pts(10_000), duration = pts(120_000))
        assertEquals(pts(120_000), resolved)
    }

    @Test
    fun `resolve leaves a live stream unclamped at the top`() {
        // A live stream has no duration, so there is nothing to clamp against.
        val resolved = relative(600).resolve(position = pts(10_000), duration = null)
        assertEquals(pts(610_000), resolved)
    }

    @Test
    fun `a factor target needs a duration`() {
        assertEquals(
            pts(60_000),
            SeekRequest(SeekTarget.Factor(0.5), SeekMode.Precise).resolve(pts(0), pts(120_000)),
        )
        assertEquals(
            pts(10_000),
            SeekRequest(SeekTarget.Factor(0.5), SeekMode.Precise).resolve(pts(10_000), null),
            "with no duration a fractional seek cannot be resolved, so the position is unchanged",
        )
    }
}
