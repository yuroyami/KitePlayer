package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.StreamChoice
import io.github.yuroyami.kiteplayer.internal.resolveStreamChoice
import io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Asking for a stream that is not there is not the same as asking for no stream.
 *
 * Both used to resolve to null, side by side in one `when`, so a selection carried across a rebuild
 * into a container that no longer had that stream simply evaporated. Playback continued without it
 * and nothing in the engine or the API could tell that apart from a caller who had switched the
 * track off on purpose.
 *
 * A rebuild is the only way to reach it: a caller's own `selectTrack` is validated against the live
 * track set before it ever becomes a choice. So the answer is a warning rather than a refusal.
 * There is nobody to refuse; the caller made no mistake and the media changed underneath.
 */
class StreamChoiceResolutionTest {

    private fun stream(index: Int, kind: TrackKind) =
        PlayerStreamInfo(index = index, kind = kind, codec = "h264")

    private val streams = listOf(
        stream(0, TrackKind.Video),
        stream(1, TrackKind.Audio),
        stream(2, TrackKind.Subtitle),
    )

    @Test
    fun `an index the media has resolves to it and says nothing`() {
        val warnings = mutableListOf<PlaybackWarning>()
        val picked = resolveStreamChoice(
            choice = StreamChoice.At(1),
            streams = streams,
            kind = TrackKind.Audio,
            warn = { warnings += it },
        ) { error("auto must not be consulted for an explicit index") }

        assertEquals(1, picked?.index)
        assertTrue(warnings.isEmpty(), "a selection that resolved is not news: $warnings")
    }

    @Test
    fun `an index the media no longer has resolves to nothing and says so`() {
        val warnings = mutableListOf<PlaybackWarning>()
        val picked = resolveStreamChoice(
            choice = StreamChoice.At(7),
            streams = streams,
            kind = TrackKind.Audio,
            warn = { warnings += it },
        ) { error("auto must not be consulted for an explicit index") }

        assertNull(picked)
        val dropped = warnings.filterIsInstance<PlaybackWarning.TrackDeselected>()
        assertEquals(1, dropped.size, "a vanished selection must be reported once, got $warnings")
        assertEquals(TrackId(7), dropped[0].track)
        assertTrue(
            dropped[0].message.contains("7"),
            "the warning must name the index that went missing: ${dropped[0].message}",
        )
    }

    @Test
    fun `switching a kind off deliberately says nothing`() {
        // The case the missing index used to be indistinguishable from. It must stay silent, or
        // every caller who turns subtitles off gets a warning for doing what it asked for.
        val warnings = mutableListOf<PlaybackWarning>()
        val picked = resolveStreamChoice(
            choice = StreamChoice.None,
            streams = streams,
            kind = TrackKind.Subtitle,
            warn = { warnings += it },
        ) { error("auto must not be consulted for None") }

        assertNull(picked)
        assertTrue(warnings.isEmpty(), "turning a kind off is not a degradation: $warnings")
    }

    @Test
    fun `auto defers to the picker and says nothing either way`() {
        val warnings = mutableListOf<PlaybackWarning>()
        assertEquals(
            2,
            resolveStreamChoice(StreamChoice.Auto, streams, TrackKind.Subtitle, { warnings += it }) {
                streams.first { it.kind == TrackKind.Subtitle }
            }?.index,
        )
        // Auto finding nothing is the ordinary answer for media with no such stream, so it is
        // silent too. Only an EXPLICIT index going missing is worth saying.
        assertNull(
            resolveStreamChoice(StreamChoice.Auto, streams, TrackKind.Subtitle, { warnings += it }) { null },
        )
        assertTrue(warnings.isEmpty(), "auto is never a degradation: $warnings")
    }
}
