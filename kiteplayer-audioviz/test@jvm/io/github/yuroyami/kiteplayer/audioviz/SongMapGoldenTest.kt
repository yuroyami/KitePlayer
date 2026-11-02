package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.TrackId
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The stored bytes of one song map, built from a fixed synthetic song, against the analysis version
 * that made them (#659).
 *
 * A stored map is trusted by its [SongMap.VERSION] alone, so a change to what the analysis writes
 * that leaves the version where it is ships the old maps from every cache as if they were new.
 * This turns red on any such change. Raise [SongMap.VERSION] and then take the new hash; a new hash
 * with the old version is the mistake this exists to stop. On the JVM only, because a Float on
 * Kotlin/JS is a 64-bit number and its maps differ in their last bits.
 */
class SongMapGoldenTest {

    private val format = AudioFormat(48_000, 2, SampleFormat.F32)

    private fun build(mono: FloatArray): SongMap {
        val builder = SongMapBuilder(TrackId(1))
        val block = FloatArray(1024 * 2)
        var start = 0
        while (start < mono.size) {
            val frames = minOf(1024, mono.size - start)
            for (i in 0 until frames) {
                block[2 * i] = mono[start + i]
                block[2 * i + 1] = mono[start + i]
            }
            builder.feed(Pts(start * 1_000_000L / 48_000), block, frames, format)
            start += frames
        }
        return builder.build(complete = true)
    }

    @Test
    fun theMapOfAFixedSongIsTheOneThisAnalysisVersionWrites() {
        val map = build(SyntheticSong.calmPad(10f) + SyntheticSong.drumLoop(10f))
        val hash = MessageDigest.getInstance("SHA-256").digest(encodeSongMap(map)).joinToString("") { "%02x".format(it) }
        assertEquals(
            GOLDEN[SongMap.VERSION],
            hash,
            "the map changed: raise SongMap.VERSION, then record the new hash under the new version",
        )
    }

    private companion object {
        /** The hash of the stored map, by the analysis version that wrote it. */
        val GOLDEN = mapOf(
            2 to "c478a6b4cb1e6e70e451d63b280b88763f2d8b2598e17cd13348bc774ea2bd63",
        )
    }
}
