package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A stream this source does not have is refused, and the refusal says so in the one way this
 * repository says it.
 *
 * Two holes, both about a caller naming a stream that is not there:
 *
 * `selectStreams` filtered with `mapNotNull`, so `{0, 999}` selected 0, opened a reader, and never
 * mentioned 999. The caller asked for two streams and got one, with no return value, no exception
 * and no warning to find out from. The `require` underneath only fired when EVERY index was
 * missing, which is the one case where the mistake was already obvious.
 *
 * The decoder factories reached `error("no stream at index N")`, which is an
 * IllegalStateException thrown from the bottom of the stack. The engine catches it and degrades to
 * a typed `NoPlayableStream`, so this was never visible through the player. It is visible to
 * anyone using the SPI directly, and there "the caller passed a bad argument" is spelled
 * IllegalArgumentException everywhere else in this codebase.
 */
class ForeignStreamRefusalTest {

    private suspend fun open(mediaDir: String) =
        KiteFFmpegSourceFactory().open(MediaItem("$mediaDir/sync1080p30.mp4")) as KiteFFmpegSource

    @Test
    fun selectStreamsRefusesAnIndexThisSourceDoesNotHave() = runBlocking {
        val mediaDir = formatMatrixMediaDir() ?: return@runBlocking
        val source = open(mediaDir)
        try {
            val video = source.streams.first { it.kind == TrackKind.Video }
            val refusal = assertFailsWith<IllegalArgumentException> {
                source.selectStreams(setOf(video.index, 999))
            }
            assertTrue(
                refusal.message?.contains("999") == true,
                "the refusal must name the index that is not there: ${refusal.message}",
            )
            // Refused whole. A half-applied selection would leave a reader open on one stream while
            // the caller believes it asked for two.
            source.selectStreams(setOf(video.index))
        } finally {
            source.close()
        }
    }

    @Test
    fun selectStreamsStillTakesEveryIndexThatIsThere() = runBlocking {
        val mediaDir = formatMatrixMediaDir() ?: return@runBlocking
        val source = open(mediaDir)
        try {
            source.selectStreams(source.streams.map { it.index }.toSet())
        } finally {
            source.close()
        }
    }

    @Test
    fun aDecoderFactoryRefusesAStreamFromSomewhereElse() = runBlocking {
        val mediaDir = formatMatrixMediaDir() ?: return@runBlocking
        val source = open(mediaDir)
        try {
            val video = source.streams.first { it.kind == TrackKind.Video }
            source.selectStreams(setOf(video.index))
            // A stream that could plausibly have come from another container: right shape, right
            // kind, an index this source does not carry.
            val foreign = PlayerStreamInfo(index = 999, kind = TrackKind.Video, codec = video.codec)
            val refusal = assertFailsWith<IllegalArgumentException> {
                source.videoDecoderFactories().first().create(foreign, HwdecPolicy.Off)
            }
            assertTrue(
                refusal.message?.contains("999") == true,
                "the refusal must name the index: ${refusal.message}",
            )
        } finally {
            source.close()
        }
    }
}
