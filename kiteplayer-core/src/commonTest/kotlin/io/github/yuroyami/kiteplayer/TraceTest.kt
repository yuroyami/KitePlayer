package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** What the engine writes into an installed [KiteTrace] sink, and that it writes nothing without one. */
class TraceTest {

    private class Recorded(val kind: String, val category: String, val name: String, val begin: Long, val end: Long, val args: Map<String, String>)

    private class RecordingSink : KiteTrace.Sink {
        val events = mutableListOf<Recorded>()

        override fun span(category: String, name: String, beginNanos: Long, endNanos: Long, args: Map<String, String>) {
            events += Recorded("span", category, name, beginNanos, endNanos, args)
        }

        override fun instant(category: String, name: String, atNanos: Long, args: Map<String, String>) {
            events += Recorded("instant", category, name, atNanos, atNanos, args)
        }

        fun named(category: String, name: String) = events.filter { it.category == category && it.name == name }
    }

    @AfterTest
    fun uninstall() {
        KiteTrace.install(null)
    }

    @Test
    fun `an open records one open span that ends after it begins`() = runTest {
        val sink = RecordingSink().also { KiteTrace.install(it) }
        val harness = CoreHarness(this)
        harness.openWithRenderer("https://host/film.mkv?token=secret")

        val open = sink.named("session", "open").single()
        assertTrue(open.begin < open.end, "the open took time, so its span must end after it begins")
        assertEquals("film.mkv", open.args["uri"], "a URI reaches a trace only as its redacted basename")
        harness.close()
    }

    @Test
    fun `a seek that refines records the keyframe span and then the refine span`() = runTest {
        val sink = RecordingSink().also { KiteTrace.install(it) }
        val harness = CoreHarness(this)
        harness.openWithRenderer()

        // Keyframes every 400 ms, so 1.02 s first lands on the keyframe at 0.8 s.
        harness.core.seek(Pts(1_020_000), SeekMode.KeyframeThenRefine)

        val phases = sink.events.filter { it.category == "seek" }
        assertEquals(listOf("keyframe", "refine"), phases.map { it.name })
        assertTrue(phases[0].end <= phases[1].begin, "the refine starts after the keyframe phase ends")
        harness.close()
    }

    @Test
    fun `a track switch records one switch span`() = runTest {
        val sink = RecordingSink().also { KiteTrace.install(it) }
        val harness = CoreHarness(
            this,
            script = MediaScript(additionalAudioTracks = listOf(ScriptedAudioTrack(index = 2, marker = 2f))),
        )
        harness.openWithRenderer()

        harness.core.selectTrack(TrackKind.Audio, TrackId(2))

        val switch = sink.named("track", "switch").single()
        assertEquals("Audio", switch.args["kind"])
        assertEquals("2", switch.args["track"])
        harness.close()
    }

    @Test
    fun `per frame spans come only when asked for`() = runTest {
        val sink = RecordingSink().also { KiteTrace.install(it) }
        val harness = CoreHarness(this)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(500.milliseconds)
        assertEquals(emptyList(), sink.events.filter { it.category == "video" && it.name != "drop" }.map { it.name })

        KiteTrace.install(sink, perFrame = true)
        harness.run(500.milliseconds)

        val decodes = sink.named("video", "decode")
        val presents = sink.named("video", "present")
        assertTrue(decodes.isNotEmpty() && presents.isNotEmpty(), "each frame is decoded and presented")
        assertTrue(presents.all { it.args["pts"] != null }, "a present span names its frame")
        harness.close()
    }

    @Test
    fun `a late frame drop records an instant`() = runTest {
        val sink = RecordingSink().also { KiteTrace.install(it) }
        val harness = CoreHarness(
            this,
            script = MediaScript(durationUs = 6_000_000, videoFrameDurationUs = 40_000),
            renderer = RecordingRenderer(presentDuration = 80.milliseconds),
        )
        harness.openWithRenderer()
        harness.core.play()
        harness.run(3.seconds)

        assertTrue(sink.named("video", "drop").isNotEmpty(), "a display that cannot keep up drops frames")
        harness.close()
    }

    @Test
    fun `an audio underrun records an instant`() = runTest {
        val sink = RecordingSink().also { KiteTrace.install(it) }
        val harness = CoreHarness(
            this,
            script = MediaScript(hasVideo = false, durationUs = 3_000_000, readDelayUs = 60_000),
            renderer = null,
        )
        harness.open()
        harness.core.play()
        harness.run(3.seconds)

        assertTrue(sink.named("audio", "underrun").isNotEmpty(), "a starved ring runs dry")
        harness.close()
    }

    @Test
    fun `without a sink no emit site is entered`() = runTest {
        KiteTrace.install(null)
        val before = KiteTrace.entered.value
        val harness = CoreHarness(this, renderer = RecordingRenderer(presentDuration = 80.milliseconds))
        harness.openWithRenderer()
        harness.core.play()
        harness.run(1.seconds)
        harness.core.seek(Pts(1_020_000), SeekMode.KeyframeThenRefine)
        harness.run(1.seconds)

        assertEquals(before, KiteTrace.entered.value)
        harness.close()
    }
}
