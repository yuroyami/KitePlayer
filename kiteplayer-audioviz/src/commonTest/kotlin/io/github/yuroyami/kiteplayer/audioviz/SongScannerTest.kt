package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.AudioScanSink
import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.MonotonicClock
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.TrackId
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** When a scan starts, what it installs, and what it must never do. Times are virtual. */
class SongScannerTest {
    private val song = SyntheticSong.drumLoop(6f)

    private inner class FakeSource : SongScanSource {
        val flow = MutableStateFlow<ScanTarget?>(null)
        override val targets = flow
        var scans = 0
        var cancelled = 0
        var gate: CompletableDeferred<Unit>? = null
        val started = MutableStateFlow(0)

        override suspend fun scan(target: ScanTarget, sink: AudioScanSink): ScanOutcome {
            scans++
            started.value++
            val format = AudioFormat(48_000, 2, SampleFormat.F32)
            val block = FloatArray(2048)
            var start = 0
            try {
                while (start < song.size) {
                    val frames = minOf(1024, song.size - start)
                    for (i in 0 until frames) { block[2 * i] = song[start + i]; block[2 * i + 1] = song[start + i] }
                    sink.onAudio(Pts(start * 1_000_000L / 48_000), block, frames, format)
                    if (start == 0) gate?.await()
                    start += frames
                }
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                cancelled++
                throw cancellation
            }
            return ScanOutcome(target.track ?: TrackId(0), reachedEnd = true)
        }
    }

    private class Installs : SongMapTarget {
        val all = ArrayList<MapInstall?>()
        override fun install(install: MapInstall?) { all += install }
        val maps: List<MapInstall> get() = all.filterNotNull()
    }

    private fun target(uri: String, duration: Long? = 6_000_000L, seekable: Boolean = true,
        io: Boolean = false) = ScanTarget(
        if (io) MediaItem(uri, io = { NoRead }) else MediaItem(uri), TrackId(1), null, duration, seekable)

    private object NoRead : MediaIo {
        override val size: Long = 0L
        override val seekable: Boolean = true
        override suspend fun read(into: ByteArray, offset: Int, length: Int): Int = -1
        override suspend fun seek(position: Long) {}
        override fun close() {}
    }

    /** One scanner on a hand-run dispatcher. Its settle waits end only when [settleAll] releases them. */
    private inner class Rig(val source: FakeSource = FakeSource(), policy: SongScanPolicy = SongScanPolicy.Default) {
        val dispatcher = ManualVizDispatcher()
        val installs = Installs()
        val waits = ArrayList<CompletableDeferred<Unit>>()
        val scope = CoroutineScope(dispatcher + Job())

        init {
            SongScanner(source, installs, { policy }, dispatcher,
                pacerFactory = { ScanPacer(0.25, MonotonicClock.System) { } },
                wait = { CompletableDeferred<Unit>().also { waits += it }.await() },
            ).start(scope)
            dispatcher.runAll()
        }

        fun target(value: ScanTarget) {
            source.flow.value = value
            dispatcher.runAll()
        }

        fun settleAll() {
            waits.forEach { it.complete(Unit) }
            dispatcher.runAll()
        }

        fun close() = scope.cancel()
    }

    @BeforeTest @AfterTest
    fun clearCache() = SongMapCache.clear()

    @Test
    fun aSettledLocalItemIsScannedAndItsCompleteMapInstalled() {
        val rig = Rig()
        rig.target(target("file:///music/song.flac"))
        assertEquals(0, rig.source.scans, "no scan before the item has settled")
        rig.settleAll()
        assertEquals(1, rig.source.scans)
        val map = rig.installs.maps.single().map
        assertTrue(map.complete)
        assertNotNull(map.referencePower, "a complete scan carries a reference")
        assertNull(rig.installs.all.first(), "the previous map is removed before anything else")
        rig.close()
    }

    @Test
    fun networkItemsAndCustomReadersNeedConsentAndLiveItemsNeverScan() {
        for ((target, policy, expected) in listOf(
            Triple(target("https://example.test/song.flac"), SongScanPolicy.Default, 0),
            Triple(target("https://example.test/song.flac"), SongScanPolicy(network = true), 1),
            Triple(target("file:///song.flac", io = true), SongScanPolicy.Default, 0),
            Triple(target("file:///song.flac", io = true), SongScanPolicy(customReaders = true), 1),
            Triple(target("/sdcard/song.flac", duration = null), SongScanPolicy.Default, 0),
            Triple(target("/sdcard/song.flac", seekable = false), SongScanPolicy.Default, 0),
            Triple(target("C:\\music\\song.flac"), SongScanPolicy.Default, 1),
            Triple(target("file:///song.flac"), SongScanPolicy.Off, 0),
        )) {
            SongMapCache.clear()
            val rig = Rig(policy = policy)
            rig.target(target)
            rig.settleAll()
            assertEquals(expected, rig.source.scans, "${target.media.uri} io=${target.media.io != null} " +
                "duration=${target.durationMicros} seekable=${target.seekable}")
            rig.close()
        }
    }

    @Test
    fun aNewItemCancelsTheOldScanWhoseMapIsNeverInstalled() {
        val rig = Rig()
        rig.source.gate = CompletableDeferred()
        rig.target(target("file:///first.flac"))
        rig.settleAll()
        assertEquals(1, rig.source.scans)
        rig.source.gate = null
        rig.target(target("file:///second.flac"))
        rig.settleAll()
        assertEquals(1, rig.source.cancelled, "the first scan was cancelled")
        assertEquals(2, rig.source.scans)
        assertEquals(1, rig.installs.maps.size, "only the second item's map was installed")
        rig.close()
    }

    @Test
    fun oneScanRunsAtATimeAcrossPlayers() {
        val first = Rig()
        val second = Rig()
        first.source.gate = CompletableDeferred()
        first.target(target("file:///a.flac"))
        second.target(target("file:///b.flac"))
        first.settleAll()
        second.settleAll()
        assertEquals(1, first.source.scans)
        assertEquals(0, second.source.scans, "the second player waits for the running scan")
        checkNotNull(first.source.gate).complete(Unit)
        first.dispatcher.runAll()
        second.dispatcher.runAll()
        assertEquals(1, second.source.scans)
        first.close()
        second.close()
    }

    @Test
    fun aCachedMapIsReusedAndARejectionForgetsIt() {
        val rig = Rig()
        for (uri in listOf("file:///song.flac", "file:///other.flac", "file:///song.flac")) {
            rig.target(target(uri))
            rig.settleAll()
        }
        assertEquals(2, rig.source.scans, "the returning song came from the cache")
        rig.installs.maps.last().onRejected()
        for (uri in listOf("file:///other.flac", "file:///song.flac")) {
            rig.target(target(uri))
            rig.settleAll()
        }
        assertEquals(3, rig.source.scans, "a rejected map is scanned again rather than reused")
        rig.close()
    }

    @Test
    fun theCacheKeyHoldsNoRawUriOrHeaders() {
        val secret = ScanTarget(MediaItem("https://cdn.example.test/a.flac?token=SECRET",
            headers = mapOf("Authorization" to "Bearer HIDDEN")), TrackId(1), null, 6_000_000L, true)
        val key = SongMapCache.key(secret)
        for (fragment in listOf("SECRET", "HIDDEN", "example", "Authorization", "flac")) {
            assertFalse(key.contains(fragment), "the cache key leaked $fragment: $key")
        }
        val other = ScanTarget(MediaItem("https://cdn.example.test/a.flac?token=OTHER"), TrackId(1), null, 6_000_000L, true)
        assertFalse(key == SongMapCache.key(other), "different URIs hash differently")
    }

    @Test
    fun thePacerHoldsAQuarterOfOneCore() {
        var now = 0L
        val clock = object : MonotonicClock { override fun nanos(): Long = now }
        val pacer = ScanPacer(0.25, clock) { nanos -> now += nanos }
        val dispatcher = ManualVizDispatcher()
        CoroutineScope(dispatcher).launch {
            repeat(200) {
                now += 3_000_000L
                pacer.pace()
            }
        }
        dispatcher.runAll()
        val busy = 200 * 3_000_000L
        assertTrue(busy.toDouble() / now <= 0.26, "busy share ${busy.toDouble() / now}")
        assertTrue(busy.toDouble() / now >= 0.2, "the pacer should not idle far below its share: ${busy.toDouble() / now}")
    }
}
