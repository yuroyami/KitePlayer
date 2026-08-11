package io.github.yuroyami.kiteplayer.rt

import io.github.yuroyami.kiteplayer.rt.cinterop.KPRT_COMMIT_NEEDS_SEGMENT
import io.github.yuroyami.kiteplayer.rt.cinterop.KPRT_COMMIT_PUBLISHED
import io.github.yuroyami.kiteplayer.rt.cinterop.KPRT_MAX_SEGMENTS
import io.github.yuroyami.kiteplayer.rt.cinterop.KPRT_SINK_BAD_ARGUMENT
import io.github.yuroyami.kiteplayer.rt.cinterop.KPRT_SINK_UNSUPPORTED_PLATFORM
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_anchor
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_frames_to_micros
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_ring_anchor
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_ring_begin_write
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_ring_buffered_frames
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_ring_channels
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_ring_commit_write
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_ring_create
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_ring_destroy
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_ring_flush
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_ring_free_frames
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_ring_read_stats
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_ring_render
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_ring_sample_rate
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_ring_stats
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_ring_underruns
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_ring_write_window
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_sink_create
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_sink_destroy
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_sink_format
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_sink_read_stats
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_sink_ring
import io.github.yuroyami.kiteplayer.rt.cinterop.kprt_sink_stats
import cnames.structs.kprt_ring
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import kotlinx.cinterop.usePinned
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The cinterop binding, checked at the seam rather than through the engine.
 *
 * This is a small suite on purpose. The ring's behaviour is proved by the eight C suites in
 * `native/tests`, under three build variants and a fourth run mode, and by the differential oracle in
 * `kiteplayer-core`'s
 * `nativeTest`, which compares it to the Kotlin ring case by case. What NEITHER of those can catch
 * is a mistake in the wiring between them: a stale archive inside the klib, a struct whose field
 * offsets Kotlin and C disagree about, a header macro that did not reach the Kotlin side, or a
 * `staticLibraries` line pointing at nothing. Every case here fails on exactly one of those.
 *
 * The macro assertions are the sharpest of them. `KPRT_MAX_SEGMENTS` and the commit verdicts are
 * compile-time constants on both sides, so if cinterop ever stopped exporting them, or the C header
 * changed one, this file would fail to compile or would compare the wrong number, and the
 * differential oracle would then be comparing two rings configured differently.
 */
class KiteRtBindingTest {

    private val sampleRate = 48_000
    private val channels = 2

    @Test
    fun `the archive inside the klib is the one the sources describe`() {
        // If cinterop had embedded a stale archive, or none, this call would either fail to link or
        // answer with something other than what create was asked for.
        val ring = assertNotNull(kprt_ring_create(sampleRate, channels, 1_024), "kprt_ring_create returned null")
        try {
            assertEquals(sampleRate, kprt_ring_sample_rate(ring))
            assertEquals(channels, kprt_ring_channels(ring))
            assertEquals(1_024, kprt_ring_free_frames(ring))
            assertEquals(0, kprt_ring_buffered_frames(ring))
            assertEquals(0L, kprt_ring_underruns(ring))
        } finally {
            kprt_ring_destroy(ring)
        }
    }

    @Test
    fun `samples written through the reservation come back through render`() {
        val ring = assertNotNull(kprt_ring_create(sampleRate, channels, 1_024))
        try {
            val frames = 256
            val accepted = writeRamp(ring, frames, from = 0, ptsUs = 0L)
            assertEquals(frames, accepted)

            val out = FloatArray(frames * channels) { Float.NaN }
            val real = out.usePinned { pinned ->
                kprt_ring_render(ring, pinned.addressOf(0), frames, 1_000_000_000L)
            }
            assertEquals(frames, real)
            for (frame in 0 until frames) {
                for (channel in 0 until channels) {
                    assertEquals(
                        frame.toFloat(),
                        out[frame * channels + channel],
                        "frame $frame channel $channel came back wrong across the cinterop seam",
                    )
                }
            }
        } finally {
            kprt_ring_destroy(ring)
        }
    }

    @Test
    fun `the anchor struct's field offsets agree between Kotlin and C`() {
        val ring = assertNotNull(kprt_ring_create(sampleRate, channels, 4_800))
        try {
            writeRamp(ring, 480, from = 0, ptsUs = 5_000_000L)
            FloatArray(480 * channels).usePinned { pinned ->
                kprt_ring_render(ring, pinned.addressOf(0), 480, 1_000_000_000L)
            }
            memScoped {
                val anchor = alloc<kprt_anchor>()
                kprt_ring_anchor(ring, anchor.ptr)
                // Three fields at three offsets. A disagreement about any of them would show up as
                // one of these reading another's value.
                assertEquals(1, anchor.valid)
                assertEquals(0, anchor.from_cache)
                assertEquals(5_000_000L + 10_000L, anchor.pts_us)
                assertEquals(1_000_000_000L, anchor.audible_at_nanos)
            }
        } finally {
            kprt_ring_destroy(ring)
        }
    }

    @Test
    fun `the write window struct carries two spans when the reservation wraps`() {
        val ring = assertNotNull(kprt_ring_create(sampleRate, channels, 100))
        try {
            writeRamp(ring, 80, from = 0, ptsUs = 0L)
            FloatArray(80 * channels).usePinned { pinned ->
                kprt_ring_render(ring, pinned.addressOf(0), 80, 0L)
            }
            memScoped {
                val window = alloc<kprt_ring_write_window>()
                val granted = kprt_ring_begin_write(ring, 40, window.ptr)
                assertEquals(40, granted)
                assertEquals(20, window.first_frames)
                assertEquals(20, window.second_frames)
                assertEquals(80L, window.start_frame)
                assertNotNull(window.first)
                assertNotNull(window.second)
                assertEquals(KPRT_COMMIT_PUBLISHED.toInt(), kprt_ring_commit_write(ring, 40, 0, 0L))
            }
        } finally {
            kprt_ring_destroy(ring)
        }
    }

    @Test
    fun `the stats struct reads consistently through the binding`() {
        val ring = assertNotNull(kprt_ring_create(sampleRate, channels, 4_096))
        try {
            writeRamp(ring, 1_000, from = 0, ptsUs = 0L)
            FloatArray(512 * channels).usePinned { pinned ->
                kprt_ring_render(ring, pinned.addressOf(0), 512, 0L)
            }
            memScoped {
                val stats = alloc<kprt_ring_stats>()
                kprt_ring_read_stats(ring, stats.ptr)
                assertEquals(1_000L, stats.written_frames)
                assertEquals(512L, stats.consumed_frames)
                assertEquals(488, stats.buffered_frames)
                assertEquals(4_096 - 488, stats.free_frames)
                assertEquals(4_096, stats.capacity_frames)
                assertEquals(channels, stats.channels)
                assertEquals(sampleRate, stats.sample_rate)
                assertEquals(0, stats.ending)
                assertEquals(1L, stats.segments_appended)
                assertEquals(0L, stats.segment_giveups)
                assertEquals(0L, stats.anchor_giveups)
            }
        } finally {
            kprt_ring_destroy(ring)
        }
    }

    @Test
    fun `the segment limit macro reached Kotlin and the C ring enforces exactly that many`() {
        // KPRT_MAX_SEGMENTS must equal KotlinAudioRing.MAX_SEGMENTS or the differential oracle would
        // be comparing two rings with different capacities. Asserting the number here is what makes
        // that a checked fact rather than a comment in two files.
        assertEquals(4, KPRT_MAX_SEGMENTS)

        val ring = assertNotNull(kprt_ring_create(sampleRate, channels, 4_800))
        try {
            for (segment in 0 until KPRT_MAX_SEGMENTS) {
                val accepted = writeRamp(ring, 100, from = segment * 100L, ptsUs = segment * 10_000L)
                assertEquals(100, accepted, "segment $segment must be accepted")
            }
            // The fifth needs a slot and all four still date unplayed audio.
            memScoped {
                val window = alloc<kprt_ring_write_window>()
                assertEquals(100, kprt_ring_begin_write(ring, 100, window.ptr))
                assertEquals(
                    KPRT_COMMIT_NEEDS_SEGMENT.toInt(),
                    kprt_ring_commit_write(ring, 100, 1, 999_000L),
                    "a fifth segment must be refused while the other four still date queued audio",
                )
            }
        } finally {
            kprt_ring_destroy(ring)
        }
    }

    @Test
    fun `flush leaves the ring empty and the anchor invalid`() {
        val ring = assertNotNull(kprt_ring_create(sampleRate, channels, 4_800))
        try {
            writeRamp(ring, 1_000, from = 0, ptsUs = 3_000_000L)
            FloatArray(100 * channels).usePinned { pinned ->
                kprt_ring_render(ring, pinned.addressOf(0), 100, 0L)
            }
            kprt_ring_flush(ring)
            assertEquals(0, kprt_ring_buffered_frames(ring))
            memScoped {
                val anchor = alloc<kprt_anchor>()
                kprt_ring_anchor(ring, anchor.ptr)
                assertEquals(0, anchor.valid)
            }
        } finally {
            kprt_ring_destroy(ring)
        }
    }

    @Test
    fun `the exported rescale divides before it multiplies`() {
        // Register item B1-18, checked through the binding as well as in C, because this is the one
        // exported function `KotlinAudioRing` has a twin of and the two must answer identically.
        assertEquals(10_000L, kprt_frames_to_micros(480L, 48_000))
        assertEquals(10_884L, kprt_frames_to_micros(480L, 44_100))
        // 1e13 frames: the naive `frames * 1000000` wraps to a negative number here.
        assertEquals(208_333_333_333_333L, kprt_frames_to_micros(10_000_000_000_000L, 48_000))
        assertEquals(0L, kprt_frames_to_micros(480L, 0))
    }

    @Test
    fun `a bad create is refused rather than half built`() {
        assertTrue(kprt_ring_create(0, channels, 1_024) == null)
        assertTrue(kprt_ring_create(sampleRate, 0, 1_024) == null)
        assertTrue(kprt_ring_create(sampleRate, channels, 0) == null)
    }


    @Test
    fun `the device surface is bound and refuses a bad argument without touching a device`() {
        // The wiring check for the second half of this library, and deliberately one that opens no
        // device and makes no sound. What it can fail on is exactly what this suite is for: a
        // `kprt_sink_*` symbol missing from the archive, a struct cinterop did not bind, or a verdict
        // constant that did not reach the Kotlin side. The device itself is driven by
        // `kiteplayer-output`'s appleTest, which is where a real AudioUnit belongs.
        memScoped {
            val accepted = alloc<kprt_sink_format>()
            val status = alloc<kotlinx.cinterop.IntVar>()
            // No out pointer for the sink, which is the one argument C refuses before it touches
            // CoreAudio at all.
            val verdict = kprt_sink_create(48_000, 2, null, accepted.ptr, status.ptr)
            assertEquals(
                KPRT_SINK_BAD_ARGUMENT.toInt(),
                verdict,
                "a create with nowhere to put the sink must be refused as a bad argument",
            )
            assertEquals(0, status.value, "no CoreAudio call was made, so there is no status to report")
        }
    }

    @Test
    fun `the sink readers tolerate a null sink rather than faulting`() {
        // A diagnostic call after close must not be a fault. The C readers answer zeroes for a NULL
        // sink, which is what lets `CoreAudioSink` read its counters without a liveness check at every
        // call site.
        assertNull(kprt_sink_ring(null), "a null sink has no ring")
        memScoped {
            val stats = alloc<kprt_sink_stats>()
            stats.callbacks = 7
            kprt_sink_read_stats(null, stats.ptr)
            assertEquals(0L, stats.callbacks, "the reader must zero the struct it was given")
            assertEquals(0, stats.has_ring)
            assertEquals(0L, stats.worst_callback_nanos)
        }
        // And destroy tolerates it too, which is what makes close idempotent one level up.
        kprt_sink_destroy(null)
    }

    @Test
    fun `the unsupported platform verdict exists and is not the success value`() {
        // The device glue is implemented for macOS and iOS. Every other target compiles the same header
        // and answers this verdict, so a caller gets a refusal instead of a link error. Compiling for a
        // target is level 7 evidence in the terms of plan section 2 and says nothing about behaviour
        // there; this case only proves the constant is real and distinct.
        assertTrue(KPRT_SINK_UNSUPPORTED_PLATFORM.toInt() != 0, "an unsupported platform is not a success")
    }

    /** Writes a ramp through the reservation API, the way `NativeAudioRing` does. */
    private fun writeRamp(ring: CPointer<kprt_ring>, frames: Int, from: Long, ptsUs: Long?): Int = memScoped {
        val window = alloc<kprt_ring_write_window>()
        val granted = kprt_ring_begin_write(ring, frames, window.ptr)
        if (granted <= 0) return@memScoped 0
        val first = window.first
        if (first != null) {
            for (frame in 0 until window.first_frames) {
                for (channel in 0 until channels) {
                    first[frame * channels + channel] = (from + frame).toFloat()
                }
            }
        }
        val second = window.second
        if (second != null) {
            for (frame in 0 until window.second_frames) {
                for (channel in 0 until channels) {
                    second[frame * channels + channel] = (from + window.first_frames + frame).toFloat()
                }
            }
        }
        val status = kprt_ring_commit_write(ring, granted, if (ptsUs != null) 1 else 0, ptsUs ?: 0L)
        if (status == KPRT_COMMIT_PUBLISHED.toInt()) granted else 0
    }
}
