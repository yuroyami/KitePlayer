package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.AudioTap
import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.KitePlayerPlatform
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.TrackId
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import javax.sound.sampled.AudioSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A scan decodes exactly what playback hands an audio tap: the same samples at the same
 * timestamps, including encoder delay, padding, a nonzero start time and an alternate track.
 * It plays through a real output device, so it skips itself where there is no mixer or no clip.
 */
class ScanAlignmentTest {
    private class Block(val pts: Long, val frames: Int, val format: AudioFormat, val samples: FloatArray)

    private fun clip(name: String): File? = sequenceOf(
        System.getenv("KITEPLAYER_TESTMEDIA")?.let { File(it, name) },
        File("../testmedia/$name"),
        File("testmedia/$name"),
    ).filterNotNull().firstOrNull { it.isFile }

    private fun compare(name: String, alternateTrack: Boolean = false) = runBlocking {
        val file = clip(name) ?: return@runBlocking println("SKIP: no $name")
        if (AudioSystem.getMixerInfo().isEmpty()) return@runBlocking println("SKIP: no audio mixer")
        val player = assertNotNull(KitePlayerPlatform.createOrNull(), "no default desktop player")
        val heard = ConcurrentLinkedQueue<Block>()
        val tap = object : AudioTap {
            override fun onAudio(pts: Pts, interleaved: FloatArray, frames: Int, format: AudioFormat) {}
            override fun onAudio(generation: Generation, pts: Pts, interleaved: FloatArray, frames: Int, format: AudioFormat) {
                heard += Block(pts.micros, frames, format, interleaved.copyOf(frames * format.channels))
            }
        }
        val media = MediaItem(file.absolutePath)
        val track: TrackId?
        try {
            player.attachAudioTap(tap)
            player.open(media)
            if (alternateTrack) {
                val audio = player.state.value.tracks.audio
                assertTrue(audio.size >= 2, "$name has one audio track")
                player.selectTrack(TrackKind.Audio, audio[1].id)
                heard.clear()
            }
            track = player.state.value.tracks.selectedAudio
            player.play()
            val enough = withTimeoutOrNull(20.seconds) {
                while (true) {
                    val first = heard.peek()?.pts ?: 0L
                    if (heard.sumOf { it.frames.toLong() } > 0 && heard.last().pts - first >= 2_000_000L) break
                    delay(20)
                }
                true
            }
            assertTrue(enough == true, "$name never played two seconds")
        } finally {
            player.detachAudioTap(tap)
            player.closeAndAwait()
        }
        val scanner = assertNotNull(KitePlayerPlatform.createOrNull())
        val scanned = HashMap<Long, Block>()
        try {
            val result = scanner.scanAudio(media, track) { pts, interleaved, frames, format ->
                scanned[pts.micros] = Block(pts.micros, frames, format, interleaved.copyOf(frames * format.channels))
            }
            assertEquals(track, result.track, "the scan decoded the track playback selected")
            assertTrue(result.reachedEnd, "the scan reached the end of $name")
        } finally {
            scanner.closeAndAwait()
        }
        val blocks = heard.toList()
        // A block trimmed at the start of playback is shorter than the decoder's; compare every other one exactly.
        var compared = 0
        for (block in blocks.drop(1)) {
            val twin = assertNotNull(scanned[block.pts], "$name: the scan has no block at ${block.pts} us")
            assertEquals(block.frames, twin.frames, "$name: block at ${block.pts} us")
            assertEquals(block.format, twin.format, "$name: format at ${block.pts} us")
            assertTrue(block.samples.contentEquals(twin.samples), "$name: samples differ at ${block.pts} us")
            compared++
        }
        assertTrue(compared >= 20, "$name: only $compared blocks were compared")
        println("$name: $compared blocks identical from ${blocks[1].pts} us, track $track")
    }

    @Test
    fun `aac with encoder delay`() = compare("audio-aac.m4a")

    @Test
    fun `mp3 with padding`() = compare("audio-mp3.mp3")

    @Test
    fun `transport stream with a nonzero start`() = compare("tsoffset1400.ts")

    @Test
    fun `an alternate audio track`() = compare("multitrack.mkv", alternateTrack = true)
}
