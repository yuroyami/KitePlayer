package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.Backends
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.Pts
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * The editions of a Matroska file, as the FFmpeg backend reports them and as the player lists them
 * (#501), on the fixtures `scripts/testmedia.sh` makes in `testmedia/editions`.
 */
class MatroskaEditionsTest {

    private fun fixture(name: String): File {
        val dir = formatMatrixMediaDir() ?: error("no testmedia directory")
        return File(dir, "editions/$name").takeIf { it.isFile }
            ?: error("testmedia is missing editions/$name; run scripts/testmedia.sh")
    }

    @Test
    fun theBackendReportsTheSegmentAndTheRangesAnOrderedEditionPlays() = runBlocking {
        KiteFFmpegMediaBackend().open(MediaItem(fixture("film.mkv").absolutePath)).use { session ->
            val film = session.source
            assertEquals("f11f0000000000000000000000000004", film.segmentUid)
            assertEquals(listOf("Theatrical", "Extended"), film.editions.map { it.title })
            val theatrical = film.editions[0]
            assertEquals(listOf(200L, true, true), listOf(theatrical.uid, theatrical.isDefault, theatrical.isOrdered))
            // The disabled chapter is not played, and the hidden one is.
            assertEquals(
                listOf(Triple("Act one", 0L, 3_000_000L), Triple("Act three", 5_000_000L, 8_000_000L)),
                theatrical.parts!!.map { Triple(it.title, it.start.micros, it.end.micros) },
            )
            assertEquals(listOf(false, true), theatrical.parts!!.map { it.isHidden })
            // As marks, the edition shows neither the disabled chapter nor the hidden one.
            assertEquals(listOf("Act one"), theatrical.chapters.map { it.title })
            assertEquals(3, film.editions[1].parts!!.size)
            assertEquals(Pts(5_000_000L), film.fromSegmentTime(Pts(5_000_000L)))
        }
        KiteFFmpegMediaBackend().open(MediaItem(fixture("season/episode.mkv").absolutePath)).use { session ->
            val episode = session.source
            val parts = episode.editions.single().parts!!
            assertEquals(
                listOf("0e0e0e00000000000000000000000002", null, "ed0ed000000000000000000000000003"),
                parts.map { it.segmentUid },
            )
            assertEquals(listOf(500_000L, 0L, 1_000_000L), parts.map { it.start.micros })
        }
        KiteFFmpegMediaBackend().open(MediaItem(fixture("recap.mkv").absolutePath)).use { session ->
            val recap = session.source
            val linked = recap.editions.single().parts!!.last()
            assertEquals("f11f0000000000000000000000000004" to 200L, linked.segmentUid to linked.segmentEditionUid)
        }
    }

    @Test
    fun theSnapshotListsTheEditionsWithTheDefaultPlaying() = runBlocking {
        val player = KitePlayer.create(PlayerConfig(backends = Backends(KiteFFmpegMediaBackend(), PacedOutput())))
        try {
            withTimeout(30.seconds) { player.open(MediaItem(fixture("film.mkv").absolutePath)) }
            val snapshot = player.state.value
            assertEquals(listOf("Theatrical", "Extended"), snapshot.editions.map { it.title })
            assertEquals(listOf(6.seconds, 8.seconds), snapshot.editions.map { it.duration })
            assertEquals(listOf(true, false), snapshot.editions.map { it.isDefault })
            assertEquals(0, snapshot.edition)
        } finally {
            player.closeAndAwait()
        }
    }

    @Test
    fun mediaWithNoEditionsListsNone() = runBlocking {
        val player = KitePlayer.create(PlayerConfig(backends = Backends(KiteFFmpegMediaBackend(), PacedOutput())))
        try {
            val dir = formatMatrixMediaDir() ?: error("no testmedia directory")
            withTimeout(30.seconds) { player.open(MediaItem(File(dir, "sync1080p30.mp4").absolutePath)) }
            assertEquals(emptyList(), player.state.value.editions)
            assertEquals(null, player.state.value.edition)
        } finally {
            player.closeAndAwait()
        }
    }
}
