package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.spi.AudioFormat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Decoding audio without playing it: order, stream choice, pacing, cancellation and closing. */
class AudioScanTest {
    private class Block(val pts: Long, val frames: Int, val first: Float, val format: AudioFormat)

    private suspend fun scan(backend: ScriptedBackend, track: TrackId? = null): Pair<AudioScanResult, List<Block>> {
        val blocks = ArrayList<Block>()
        val result = scanAudio(MediaItem("scripted://scan"), backend, track) { pts, interleaved, frames, format ->
            blocks += Block(pts.micros, frames, interleaved[0], format)
        }
        return result to blocks
    }

    /** The ledger counts sources and packets; sessions and decoders report their own close. */
    private fun assertAllClosed(backend: ScriptedBackend) {
        for (session in backend.sessions) {
            assertEquals(1, session.closeCount, "every scan session is closed exactly once")
            assertTrue(session.audioDecoderInstances.all { it.closed }, "every scan decoder is closed")
        }
    }

    @Test
    fun aScanDeliversEveryBlockOfTheDefaultTrackInOrderAndClosesEverything() = runTest {
        val script = MediaScript(durationUs = 2_000_000L)
        val ledger = LeakLedger()
        val backend = ScriptedBackend(script, ledger)
        val (result, blocks) = scan(backend)
        assertEquals(TrackId(script.audioIndex), result.track)
        assertTrue(result.reachedEnd, "the decoder reported the end of the stream")
        assertTrue(blocks.size >= 90, "about two seconds of 1024-frame buffers, got ${blocks.size}")
        for (index in 1 until blocks.size) {
            assertEquals(blocks[index - 1].pts + script.audioBufferDurationUs, blocks[index].pts, "block $index")
        }
        assertEquals(blocks.sumOf { it.frames.toLong() }, result.framesDecoded)
        assertEquals(blocks.first().pts, assertNotNull(result.firstPts).micros)
        assertTrue(blocks.all { it.first == 1f && it.format.channels == script.channels }, "the default track's samples")
        assertEquals(1, backend.openCalls, "a scan opens exactly one session of its own")
        assertEquals(0, ledger.liveCount, "the scan closed its packets and source")
        assertTrue(backend.sessions.single().audioDecoderInstances.isNotEmpty())
        assertAllClosed(backend)
    }

    @Test
    fun aNamedTrackIsScannedAndAMissingOneIsRefused() = runTest {
        val second = ScriptedAudioTrack(index = 5, marker = 2f, language = "fra")
        val script = MediaScript(durationUs = 1_000_000L, additionalAudioTracks = listOf(second))
        val ledger = LeakLedger()
        val backend = ScriptedBackend(script, ledger)
        val (result, blocks) = scan(backend, TrackId(5))
        assertEquals(TrackId(5), result.track)
        assertTrue(blocks.isNotEmpty() && blocks.all { it.first == 2f }, "the named track's own samples")
        assertFailsWith<IllegalArgumentException> { scan(backend, TrackId(0)) }
        assertEquals(0, ledger.liveCount, "a refused track leaves nothing open")
        assertAllClosed(backend)
    }

    @Test
    fun theSinkPacesTheScanAndCancellationClosesEverything() = runTest {
        val script = MediaScript(durationUs = 4_000_000L)
        val ledger = LeakLedger()
        val backend = ScriptedBackend(script, ledger)
        val held = CompletableDeferred<Unit>()
        var delivered = 0
        val job = async {
            scanAudio(MediaItem("scripted://scan"), backend) { _, _, _, _ ->
                delivered++
                if (delivered == 3) held.await()
            }
        }
        testScheduler.advanceUntilIdle()
        assertEquals(3, delivered, "the scan waits inside the third block")
        val reads = backend.sessions.single().scriptedSource.reads
        testScheduler.advanceUntilIdle()
        assertEquals(reads, backend.sessions.single().scriptedSource.reads, "nothing is read while the sink holds")
        job.cancel()
        testScheduler.advanceUntilIdle()
        assertEquals(0, ledger.liveCount, "a cancelled scan closed its packets and source")
        assertAllClosed(backend)
    }

    @Test
    fun aPlayerScanReadsThroughThePlayersResolverWithoutThePlaybackCache() = runTest {
        val supplied = object : MediaIo {
            override val size: Long = 1_000L
            override val seekable: Boolean = true
            override suspend fun read(into: ByteArray, offset: Int, length: Int): Int = -1
            override suspend fun seek(position: Long) {}
            override fun close() {}
        }
        val harness = CoreHarness(this, config = PlayerConfig(network = NetworkConfig(
            ioResolver = MediaIoResolver { uri -> if (uri.startsWith("https://")) supplied else null },
        )))
        val player = KitePlayer(harness.core)
        val result = player.scanAudio(MediaItem("https://example.test/song.flac")) { _, _, _, _ -> }
        assertTrue(result.reachedEnd)
        val opened = assertNotNull(harness.backend.lastOpenedItem?.io, "the resolved reader never reached the backend")
        assertSame(supplied, opened.open(), "a scan reads through the resolved reader, not the playback byte cache")
        harness.close()
    }
}
