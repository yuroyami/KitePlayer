package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.AudioScanRange
import io.github.yuroyami.kiteplayer.AudioScanSink
import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaItem
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

    private inner class FakeSource(private val audio: FloatArray = song) : SongScanSource {
        val flow = MutableStateFlow<ScanTarget?>(null)
        override val targets = flow
        var scans = 0
        var cancelled = 0
        var gate: CompletableDeferred<Unit>? = null
        val started = MutableStateFlow(0)
        val ranges = ArrayList<AudioScanRange?>()

        /** A reader that refuses the second concurrent open a range needs. */
        var refuseRanges = false

        override suspend fun scan(target: ScanTarget, range: AudioScanRange?, sink: AudioScanSink): ScanOutcome {
            scans++
            started.value++
            ranges += range
            if (refuseRanges && range != null) error("this reader cannot be opened twice")
            val format = AudioFormat(48_000, 2, SampleFormat.F32)
            val block = FloatArray(2048)
            // A seek lands on a block boundary at or before what was asked for, as a container does.
            var start = (((range?.from?.micros ?: 0L) * 48_000 / 1_000_000L) / 1024 * 1024)
                .toInt().coerceIn(0, audio.size)
            val until = range?.until?.micros
            var first = true
            var end = 0L
            try {
                while (start < audio.size) {
                    val frames = minOf(1024, audio.size - start)
                    for (i in 0 until frames) { block[2 * i] = audio[start + i]; block[2 * i + 1] = audio[start + i] }
                    sink.onAudio(Pts(start * 1_000_000L / 48_000), block, frames, format)
                    end = (start + frames) * 1_000_000L / 48_000
                    if (first) { first = false; gate?.await() }
                    start += frames
                    if (until != null && end >= until) break
                }
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                cancelled++
                throw cancellation
            }
            return ScanOutcome(target.track ?: TrackId(0), reachedEnd = start >= audio.size, end)
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
    private inner class Rig(
        val source: FakeSource = FakeSource(),
        policy: SongScanPolicy = SongScanPolicy.Default,
        val store: SongMapStore = SongMapStore.None,
        val workers: Int = 1,
    ) {
        val dispatcher = ManualVizDispatcher()
        val installs = Installs()
        val waits = ArrayList<CompletableDeferred<Unit>>()
        val scope = CoroutineScope(dispatcher + Job())

        init {
            SongScanner(source, installs, { policy }, dispatcher,
                store = { store },
                workers = { workers },
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


    private class MemoryStore : SongMapStore {
        val entries = HashMap<String, ByteArray>()
        var reads = 0
        var writes = 0
        override suspend fun read(key: String): ByteArray? { reads++; return entries[key] }
        override suspend fun write(key: String, bytes: ByteArray) { writes++; entries[key] = bytes }
        override suspend fun remove(key: String) { entries.remove(key) }
    }

    /**
     * The whole point of scanning ranges: it must answer what one pass answers.
     *
     * The fake source seeks the way a container does, to a block boundary at or before what was
     * asked for, so this also covers a seek that lands early.
     */
    @Test
    fun aLongSongScannedInRangesAnswersWhatOnePassAnswers() {
        val audio = SyntheticSong.drumLoop(130f)
        val item = ScanTarget(MediaItem("file:///long.flac"), TrackId(1), null, 130_000_000L, true)

        val one = Rig(FakeSource(audio), workers = 1)
        one.target(item)
        one.settleAll()
        val single = one.installs.maps.single().map
        one.close()

        SongMapCache.clear()
        val many = Rig(FakeSource(audio), workers = 3)
        many.target(item)
        many.settleAll()
        val merged = many.installs.maps.single().map

        // Three workers were offered, but a range shorter than a minute is not worth its
        // warm-up, so a 130 second song takes two.
        assertEquals(2, many.source.scans, "the song was cut into two ranges")
        assertTrue(many.source.ranges.all { it != null }, "every worker was given a range")
        assertTrue(merged.complete, "the merged map is complete")
        assertNotNull(merged.referencePower, "the merged map carries a reference")
        val reference = 10.0 * kotlin.math.log10(checkNotNull(merged.referencePower) /
            checkNotNull(single.referencePower))
        assertTrue(kotlin.math.abs(reference) <= 0.5,
            "the reference moved ${reference} dB away from one pass")
        assertTrue(kotlin.math.abs(merged.levelCurve.size - single.levelCurve.size) <= 2,
            "curve ${merged.levelCurve.size} against ${single.levelCurve.size}")
        var worst = 0f
        for (index in 0 until minOf(merged.levelCurve.size, single.levelCurve.size)) {
            val gap = kotlin.math.abs(merged.levelCurve[index] - single.levelCurve[index])
            if (gap > worst) worst = gap
        }
        assertTrue(worst <= 1.5f, "the level curves differ by up to $worst dB")
        many.close()
    }

    @Test
    fun aStoredMapIsInstalledWithoutDecodingAnything() {
        val store = MemoryStore()
        val first = Rig(store = store)
        first.target(target("file:///song.flac"))
        first.settleAll()
        assertEquals(1, first.source.scans)
        assertEquals(1, store.writes, "a complete map was kept")
        first.close()

        // A new process keeps the store but loses the memory cache.
        SongMapCache.clear()
        val second = Rig(store = store)
        second.target(target("file:///song.flac"))
        second.settleAll()
        assertEquals(0, second.source.scans, "the stored map was used instead of a scan")
        assertTrue(second.installs.maps.single().map.complete)
        second.close()
    }

    @Test
    fun aRejectedMapIsDroppedFromTheStoreSoItCannotComeBack() {
        val store = MemoryStore()
        val rig = Rig(store = store)
        rig.target(target("file:///song.flac"))
        rig.settleAll()
        assertEquals(1, store.entries.size)
        rig.installs.maps.last().onRejected()
        rig.dispatcher.runAll()
        assertTrue(store.entries.isEmpty(), "a map live audio disagreed with stayed on disk")
        rig.close()
    }

    /** A range needs a second concurrent open, and a reader may refuse it. The map still arrives. */
    @Test
    fun aReaderThatRefusesARangeStillGetsAWholeMap() {
        val source = FakeSource(SyntheticSong.drumLoop(130f))
        source.refuseRanges = true
        val rig = Rig(source, workers = 2)
        rig.target(ScanTarget(MediaItem("file:///long.flac"), TrackId(1), null, 130_000_000L, true))
        rig.settleAll()
        assertTrue(source.ranges.any { it == null }, "no whole-song pass was tried")
        val map = rig.installs.maps.single().map
        assertTrue(map.complete, "the fallback pass produced no complete map")
        assertNotNull(map.referencePower, "the fallback map carries a reference")
        rig.close()
    }

    @Test
    fun aShortSongIsNeverWorthCuttingUp() {
        val rig = Rig(workers = 4)
        rig.target(target("file:///song.flac"))
        rig.settleAll()
        assertEquals(1, rig.source.scans, "a six second song scanned in one pass")
        assertNull(rig.source.ranges.single(), "one pass asks for no range")
        rig.close()
    }
}
