package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/**
 * An external subtitle at an http or https address gets the item's request headers only when the
 * address has the item's own scheme, host and port. Every other address is asked for with none.
 */
class SubtitleHeaderScopeTest {

    private val itemHeaders = mapOf("Authorization" to "Bearer item-token", "X-Session" to "42")

    /** A reader over one SubRip cue. */
    private class SrtReader : MediaIo {
        private val bytes = "1\n00:00:01,000 --> 00:00:03,000\nA line\n\n".encodeToByteArray()
        private var at = 0
        override val size: Long = bytes.size.toLong()
        override val seekable: Boolean = true

        override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (at >= bytes.size) return -1
            val n = minOf(length, bytes.size - at)
            bytes.copyInto(into, offset, at, at + n)
            at += n
            return n
        }

        override suspend fun seek(position: Long) {
            at = position.toInt()
        }

        override fun close() = Unit
    }

    /** Serves every subtitle address and records the headers each was asked with. */
    private class RecordingResolver : MediaIoResolver {
        val asked = mutableListOf<Pair<String, Map<String, String>>>()

        override suspend fun resolve(uri: String): MediaIo? = resolve(uri, emptyMap())

        override suspend fun resolve(uri: String, headers: Map<String, String>): MediaIo? {
            // The item itself goes to the scripted backend.
            if (!uri.endsWith(".srt")) return null
            asked += uri to headers
            return SrtReader()
        }

        fun headersFor(uri: String): Map<String, String> = asked.single { it.first == uri }.second
    }

    private fun harness(scope: TestScope, resolver: MediaIoResolver) = CoreHarness(
        scope,
        script = MediaScript(durationUs = 10_000_000),
        config = PlayerConfig(network = NetworkConfig(ioResolver = resolver)),
    )

    private fun subtitleCount(harness: CoreHarness) =
        harness.core.snapshots.value.tracks.all.count { it.kind == TrackKind.Subtitle }

    @Test
    fun onlyTheItemsOwnSchemeHostAndPortGetTheItemsHeaders() = runTest {
        val sameOrigin = listOf(
            "https://cdn.kite.test/show/ep1.srt",
            "https://CDN.Kite.TEST/ep1.srt",
            "https://cdn.kite.test:443/ep1.srt",
            "HTTPS://cdn.kite.test/ep2.srt",
        )
        val otherOrigin = listOf(
            "https://subs.kite.test/ep1.srt",
            "https://cdn.kite.test:8443/ep1.srt",
            "http://cdn.kite.test/ep1.srt",
            "https://cdn.kite.test.other.test/ep1.srt",
            "https://cdn.kite.test@other.test/ep1.srt",
            "https://other.test\\@cdn.kite.test/ep1.srt",
            "https://cdn.kite.test./ep1.srt",
            "https://cdn.Kite.test/ep1.srt",
        )
        val resolver = RecordingResolver()
        val harness = harness(this, resolver)
        harness.core.open(
            MediaItem(
                "https://cdn.kite.test/show/ep1.mkv",
                headers = itemHeaders,
                externalSubtitles = (sameOrigin + otherOrigin).map { SubtitleSource(uri = it) },
            ),
        )
        harness.run(100.milliseconds)
        for (uri in sameOrigin) assertEquals(itemHeaders, resolver.headersFor(uri), uri)
        for (uri in otherOrigin) assertEquals(emptyMap(), resolver.headersFor(uri), uri)
        // Only the headers differ: every file still loads.
        assertEquals(sameOrigin.size + otherOrigin.size, subtitleCount(harness))
        harness.close()
    }

    @Test
    fun anItemThatIsNotAtAnHttpAddressSendsItsHeadersToNoSubtitle() = runTest {
        for (itemUri in listOf("scripted://one", "/media/ep1.mkv")) {
            val resolver = RecordingResolver()
            val harness = harness(this, resolver)
            harness.core.open(
                MediaItem(
                    itemUri,
                    headers = itemHeaders,
                    externalSubtitles = listOf(SubtitleSource(uri = "https://cdn.kite.test/ep1.srt")),
                ),
            )
            harness.run(100.milliseconds)
            assertEquals(emptyMap(), resolver.headersFor("https://cdn.kite.test/ep1.srt"), itemUri)
            assertEquals(1, subtitleCount(harness), itemUri)
            harness.close()
        }
    }

    @Test
    fun aSubtitleAddedDuringPlaybackFollowsTheSameRule() = runTest {
        val resolver = RecordingResolver()
        val harness = harness(this, resolver)
        harness.attachRenderer()
        harness.core.open(MediaItem("https://cdn.kite.test/show/ep1.mkv", headers = itemHeaders))
        harness.core.addExternalSubtitle(SubtitleSource(uri = "https://cdn.kite.test/show/late.srt"))
        harness.core.addExternalSubtitle(SubtitleSource(uri = "https://subs.kite.test/late.srt"))
        assertEquals(itemHeaders, resolver.headersFor("https://cdn.kite.test/show/late.srt"))
        assertEquals(emptyMap(), resolver.headersFor("https://subs.kite.test/late.srt"))
        assertEquals(2, subtitleCount(harness))
        harness.close()
    }

    @Test
    fun aSubtitleWithItsOwnReaderIsReadThroughItAndTheResolverIsNotAsked() = runTest {
        // This is how an app sends its own headers to a subtitle on another server.
        val resolver = RecordingResolver()
        val harness = harness(this, resolver)
        var opened = 0
        harness.core.open(
            MediaItem(
                "https://cdn.kite.test/show/ep1.mkv",
                headers = itemHeaders,
                externalSubtitles = listOf(
                    SubtitleSource(uri = "https://subs.kite.test/ep1.srt", io = { opened++; SrtReader() }),
                ),
            ),
        )
        harness.run(100.milliseconds)
        assertEquals(1, opened)
        assertEquals(emptyList(), resolver.asked)
        assertEquals(1, subtitleCount(harness))
        harness.close()
    }
}
